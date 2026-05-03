package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.io.IOException
import java.time.Duration
import java.util.function.Supplier

// DTOs para comunicación con el servicio IA
data class AnalisisRequest(
    val url_imagen: String,
    val umbral: Double,
    val model_version: String,
    val api_version: String
)

@JsonIgnoreProperties(ignoreUnknown = false)
data class PythonResponse(
    val prediction: String?,
    val confidence: Double?,
    val model_version: String? = null
) {
    fun validate(): PythonResponse {
        if (prediction.isNullOrBlank()) {
            throw IllegalArgumentException("Respuesta IA inválida: prediction vacío")
        }
        if (confidence == null || confidence.isNaN() || confidence < 0.0 || confidence > 1.0) {
            throw IllegalArgumentException("Respuesta IA inválida: confidence debe estar entre 0.0 y 1.0")
        }
        return this
    }
}

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
    webClientBuilder: WebClient.Builder
) {
    
    private val log = LoggerFactory.getLogger(AnalisisService::class.java)
    
    // Configuración desde application.properties
    @Value("\${ai.service.url:http://localhost:8000/api/v1/analizar}")
    private var aiServiceUrl: String = "http://localhost:8000/api/v1/analizar"

    @Value("\${veabsoluta.ia.threshold:0.65}")
    private var umbralDeteccion: Double = 0.65

    @Value("\${ai.model.version:veabsoluta-model-v1}")
    private var aiModelVersion: String = "veabsoluta-model-v1"

    @Value("\${ai.api.version:v1}")
    private var aiApiVersion: String = "v1"

    // WebClient con timeouts configurados
    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(aiServiceUrl)
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) // 10MB
            }
            .build()
    }

    private val retry: Retry by lazy {
        Retry.of("iaServiceRetry", RetryConfig.custom<Mono<PythonResponse>>()
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(500))
            .retryOnException { throwable ->
                throwable is IOException ||
                (throwable is WebClientResponseException && throwable.statusCode.is5xxServerError)
            }
            .build())
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
            umbral = umbralDeteccion,
            model_version = aiModelVersion,
            api_version = aiApiVersion
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
            prediccion = pythonResult.prediction!!,
            confianza = pythonResult.confidence!!
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
            val supplier: Supplier<PythonResponse> = Supplier {
                circuitBreaker.executeSupplier { realizarPeticionIAInternal(request) }
            }
            Retry.decorateSupplier(retry, supplier).get()
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
     * Implementación interna de la petición HTTP al servicio IA usando WebClient
     */
    private fun realizarPeticionIAInternal(request: AnalisisRequest): PythonResponse {
        val startedAt = System.nanoTime()
        log.info("Enviando request a IA: url={}, umbral={}, model_version={}",
            request.url_imagen, request.umbral, request.model_version)

        return try {
            val response = webClient.post()
                .uri("")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus({ status -> status.is4xxClientError }) { clientResponse ->
                    log.warn("Error 4xx del servicio IA: {}", clientResponse.statusCode())
                    clientResponse.bodyToMono(String::class.java)
                        .flatMap { body ->
                            Mono.error(AnalisisServiceException(
                                message = "Error del cliente al servicio IA: ${clientResponse.statusCode()} - $body",
                                codigo = ErrorCode.INVALID_IMAGE
                            ))
                        }
                }
                .onStatus({ status -> status.is5xxServerError }) { clientResponse ->
                    log.error("Error 5xx del servicio IA: {}", clientResponse.statusCode())
                    clientResponse.bodyToMono(String::class.java)
                        .flatMap { body ->
                            Mono.error(AnalisisServiceException(
                                message = "Error interno del servicio IA: ${clientResponse.statusCode()} - $body",
                                codigo = ErrorCode.IA_SERVICE_ERROR
                            ))
                        }
                }
                .bodyToMono(PythonResponse::class.java)
                .block(Duration.ofSeconds(10)) // Timeout de 10 segundos
                ?.validate()
                ?: throw AnalisisServiceException(
                    message = "El servicio IA devolvió una respuesta nula",
                    codigo = ErrorCode.IA_SERVICE_ERROR
                )

            val elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
            val normalizedPrediction = normalizePrediction(response.prediction!!)
            log.info(
                "IA response recibida en {} ms - prediction={}, confidence={}, model_version={}",
                elapsedMs,
                normalizedPrediction,
                response.confidence,
                response.model_version ?: aiModelVersion
            )

            response.copy(prediction = normalizedPrediction)
        } catch (e: WebClientResponseException) {
            val elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
            log.error("Error HTTP {} del servicio IA en {} ms: {}",
                e.statusCode, elapsedMs, e.responseBodyAsString)
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
        } catch (e: IllegalArgumentException) {
            val elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
            log.error("Validación de respuesta IA fallida en {} ms: {}", elapsedMs, e.message)
            throw AnalisisServiceException(
                message = "Respuesta inválida del servicio IA: ${e.message}",
                cause = e,
                codigo = ErrorCode.IA_SERVICE_ERROR
            )
        } catch (e: Exception) {
            val elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
            log.error("Error de conexión al servicio IA en {} ms", elapsedMs, e)
            throw AnalisisServiceException(
                message = "No se pudo conectar al servicio IA: ${e.message}",
                cause = e,
                codigo = ErrorCode.IA_SERVICE_UNAVAILABLE
            )
        }
    }

    private fun normalizePrediction(prediction: String): String {
        val normalized = prediction.trim().lowercase()
        return when {
            normalized.contains("fake") || normalized.contains("artificial") || normalized.contains("manipulated") -> "FAKE"
            normalized.contains("real") || normalized.contains("authentic") || normalized.contains("genuine") -> "REAL"
            else -> throw AnalisisServiceException(
                message = "Predicción IA no reconocida: '$prediction'",
                codigo = ErrorCode.IA_SERVICE_ERROR
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
    STORAGE_ERROR,          // Error de almacenamiento
    UNKNOWN                 // Error no categorizado
}