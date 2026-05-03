package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClientResponseException
import java.time.Duration

// DTOs para comunicación con el servicio IA
data class AnalisisRequest(val url_imagen: String, val umbral: Double)
data class PythonResponse(val prediction: String, val confidence: Double)

/**
 * Servicio de orquestación para detección de deepfakes.
 * 
 * Responsabilidades:
 * - Comunicar con el motor IA (FastAPI)
 * - Persistir resultados en PostgreSQL
 * - Manejar errores y timeouts
 */
@Service
class AnalisisService(
    private val analisisRepository: AnalisisRepository,
    restClientBuilder: RestClient.Builder
) {
    
    private val log = LoggerFactory.getLogger(AnalisisService::class.java)
    
    // Configuración desde application.properties
    @Value("\${ai.service.url:http://localhost:8000/api/v1/analizar}")
    private lateinit var aiServiceUrl: String

    @Value("\${veabsoluta.ia.threshold:0.65}")
    private var umbralDeteccion: Double = 0.65

    // RestClient con timeouts configurados
    private val restClient: RestClient by lazy {
        val requestFactory = SimpleClientHttpRequestFactory()
        requestFactory.setConnectTimeout(5000) // 5s conexión
        requestFactory.setReadTimeout(30000) // 30s lectura
        
        restClientBuilder
            .baseUrl(aiServiceUrl)
            .requestFactory(requestFactory)
            .build()
    }

    // Circuit Breaker para proteger contra caídas del servicio IA
    private val circuitBreaker: CircuitBreaker by lazy {
        CircuitBreaker.of("iaService", CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f) // Abre si 50% de llamadas fallan
            .slowCallRateThreshold(50.0f) // Abre si 50% de llamadas son lentas
            .slowCallDurationThreshold(Duration.ofSeconds(30)) // Llamada lenta > 30s
            .waitDurationInOpenState(Duration.ofSeconds(60)) // Espera 60s en estado abierto
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10) // Últimas 10 llamadas
            .minimumNumberOfCalls(5) // Mínimo 5 llamadas para evaluar
            .permittedNumberOfCallsInHalfOpenState(3) // 3 llamadas de prueba en half-open
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build())
    }

    /**
     * Orquestador principal: delega la inferencia al microservicio de Python
     * @return Analisis con el resultado de la detección
     * @throws AnalisisServiceException si falla la comunicación
     */
    fun ejecutarDeteccion(rutaImagen: String, nombreArchivo: String): Analisis {
        log.info("Iniciando análisis para: {}", nombreArchivo)
        
        val request = AnalisisRequest(
            url_imagen = rutaImagen,
            umbral = umbralDeteccion
        )

        val pythonResult = try {
            realizarPeticionIA(request)
        } catch (e: AnalisisServiceException) {
            log.error("Error en comunicación con servicio IA para {}", nombreArchivo, e)
            throw e
        }

        // Persistir resultado en PostgreSQL
        val nuevoRegistro = Analisis(
            nombreArchivo = nombreArchivo,
            rutaArchivo = rutaImagen,
            prediccion = pythonResult.prediction,
            confianza = pythonResult.confidence
        )

        val resultado = analisisRepository.save(nuevoRegistro)
        log.info("Análisis completado: predicción={}, confianza={}", 
            pythonResult.prediction, pythonResult.confidence)
        
        return resultado
    }
    
    /**
     * Realiza la petición HTTP al servicio IA con Circuit Breaker
     */
    private fun realizarPeticionIA(request: AnalisisRequest): PythonResponse {
        return try {
            circuitBreaker.executeSupplier { realizarPeticionIAInternal(request) }
        } catch (e: Exception) {
            log.warn("Circuit Breaker activado o error en llamada IA: {}", e.message)
            throw AnalisisServiceException(
                message = "Servicio de análisis no disponible temporalmente",
                cause = e,
                codigo = ErrorCode.IA_SERVICE_UNAVAILABLE
            )
        }
    }

    /**
     * Implementación interna de la petición HTTP al servicio IA
     */
    private fun realizarPeticionIAInternal(request: AnalisisRequest): PythonResponse {
        return try {
            restClient.post()
                .uri("")
                .body(request)
                .retrieve()
                .body(PythonResponse::class.java)!!
        } catch (e: RestClientResponseException) {
            log.error("Error HTTP {} del servicio IA: {}", e.statusCode, e.responseBodyAsString)
            when {
                e.statusCode.is5xxServerError -> throw AnalisisServiceException(
                    message = "Error interno del servicio IA: ${e.statusCode}",
                    cause = e,
                    codigo = ErrorCode.IA_SERVICE_ERROR
                )
                e.statusCode.is4xxClientError -> throw AnalisisServiceException(
                    message = "Error del cliente al servicio IA: ${e.statusCode}",
                    cause = e,
                    codigo = ErrorCode.INVALID_IMAGE
                )
                else -> throw AnalisisServiceException(
                    message = "Error desconocido del servicio IA: ${e.statusCode}",
                    cause = e,
                    codigo = ErrorCode.IA_SERVICE_UNAVAILABLE
                )
            }
        } catch (e: Exception) {
            log.error("Error de conexión al servicio IA", e)
            throw AnalisisServiceException(
                message = "No se pudo conectar al servicio IA: ${e.message}",
                cause = e,
                codigo = ErrorCode.IA_SERVICE_UNAVAILABLE
            )
        }
    }
}

/**
 * Excepción personalizada para el servicio de análisis
 */
class AnalisisServiceException(
    message: String,
    cause: Throwable? = null,
    val codigo: ErrorCode = ErrorCode.UNKNOWN
) : RuntimeException(message, cause)

/**
 * Códigos de error para manejo estructurado
 */
enum class ErrorCode {
    IA_SERVICE_UNAVAILABLE,  // No se pudo conectar al servicio
    IA_SERVICE_ERROR,        // El servicio respondió con error
    INVALID_IMAGE,          // La imagen no es válida
    TIMEOUT,                // La petición excedió el tiempo
    STORAGE_ERROR,          // Error al almacenar en Cloudinary
    UNKNOWN                 // Error no categorizado
}

/**
 * Excepción personalizada para el servicio de Cloudinary
 */
class CloudinaryServiceException(
    message: String,
    cause: Throwable? = null,
    val codigo: ErrorCode = ErrorCode.STORAGE_ERROR
) : RuntimeException(message, cause)