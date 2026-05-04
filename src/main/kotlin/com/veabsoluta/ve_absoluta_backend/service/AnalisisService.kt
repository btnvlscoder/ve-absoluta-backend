package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.slf4j.MDC
import reactor.core.publisher.Mono
import java.io.IOException
import java.time.Duration
import java.util.UUID

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
        require(!prediction.isNullOrBlank()) { "Respuesta IA inválida: prediction vacío" }
        require(confidence != null && !confidence.isNaN() && confidence in 0.0..1.0) { "Respuesta IA inválida: confidence debe estar entre 0.0 y 1.0" }
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
        val startedAt = System.nanoTime()
        log.info("Iniciando análisis para: {}", nombreArchivo)
        
        val request = AnalisisRequest(
            url_imagen = rutaImagen,
            umbral = umbralDeteccion,
            model_version = aiModelVersion,
            api_version = aiApiVersion
        )

        val pythonResult = try {
            realizarPeticionIAInternal(request).block()
        } catch (e: AnalisisServiceException) {
            log.error("Error en comunicación con servicio IA para {}", nombreArchivo, e)
            throw e
        }

        // Log estructurado de la response exitosa
        val elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
        log.info("IA response completada - traceId: {}, duration_ms: {}, prediction: {}, confidence: {}, model_version: {}", 
            MDC.get("traceId"), elapsedMs, pythonResult?.prediction, pythonResult?.confidence, 
            pythonResult?.model_version ?: aiModelVersion)

        // Persistir resultado en PostgreSQL
        val nuevoRegistro = Analisis(
            nombreArchivo = nombreArchivo,
            rutaArchivo = rutaImagen,
            prediccion = pythonResult?.prediction ?: throw IllegalStateException("Prediction should not be null"),
            confianza = pythonResult?.confidence ?: throw IllegalStateException("Confidence should not be null")
        )

        val resultado = analisisRepository.save(nuevoRegistro)
        log.info("Análisis completado: predicción={}, confianza={}", 
            pythonResult?.prediction, pythonResult?.confidence)
        
        return resultado
    }
    
    /**
     * Implementación interna de la petición HTTP al servicio IA usando WebClient reactivo
     */
    internal fun realizarPeticionIAInternal(request: AnalisisRequest): Mono<PythonResponse> {
        val traceId = MDC.get("traceId") ?: UUID.randomUUID().toString()
        MDC.put("traceId", traceId)
        
        // Log estructurado del request
        log.info("IA request iniciada - traceId: {}, url: {}, umbral: {}, model_version: {}", 
            traceId, request.url_imagen, request.umbral, request.model_version)

        return webClient.post()
            .uri("")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .onStatus({ status -> status.is4xxClientError }) { clientResponse ->
                log.warn("IA response 4xx - traceId: {}, status: {}, no se reintentará", 
                    traceId, clientResponse.statusCode())
                clientResponse.bodyToMono(String::class.java)
                    .flatMap { body ->
                        Mono.error(AnalisisServiceException(
                            message = "Error del cliente al servicio IA: ${clientResponse.statusCode()} - $body",
                            codigo = ErrorCode.INVALID_IMAGE
                        ))
                    }
            }
            .onStatus({ status -> status.is5xxServerError }) { clientResponse ->
                log.error("IA response 5xx - traceId: {}, status: {}, se reintentará", 
                    traceId, clientResponse.statusCode())
                clientResponse.bodyToMono(String::class.java)
                    .flatMap { body ->
                        Mono.error(AnalisisServiceException(
                            message = "Error interno del servicio IA: ${clientResponse.statusCode()} - $body",
                            codigo = ErrorCode.IA_SERVICE_ERROR
                        ))
                    }
            }
            .bodyToMono(PythonResponse::class.java)
            .timeout(Duration.ofSeconds(15)) // Response timeout total
            .retryWhen(reactor.util.retry.Retry.backoff(1, Duration.ofMillis(500))
                .filter { throwable ->
                    when (throwable) {
                        is IOException -> true
                        is WebClientResponseException -> throwable.statusCode.is5xxServerError
                        else -> false
                    }
                })
            .map { response -> response.validate() }
            .map { pythonResult ->
                val prediction = pythonResult.prediction ?: throw IllegalStateException("prediction should not be null after validation")
                val confidence = pythonResult.confidence ?: throw IllegalStateException("confidence should not be null after validation")
                
                val normalizedPrediction = normalizePrediction(prediction)
                pythonResult.copy(prediction = normalizedPrediction)
            }
            .onErrorResume(WebClientResponseException::class.java) { e ->
                log.error("IA request fallida - traceId: {}, status: {}, message: {}", 
                    traceId, e.statusCode, e.responseBodyAsString)
                Mono.error(e)
            }
            .onErrorResume(IllegalArgumentException::class.java) { e ->
                log.error("IA response validación fallida - traceId: {}, error: {}", 
                    traceId, e.message)
                Mono.error(AnalisisServiceException(
                    message = "Respuesta inválida del servicio IA: ${e.message}",
                    cause = e,
                    codigo = ErrorCode.IA_SERVICE_ERROR
                ))
            }
            .onErrorResume(Exception::class.java) { e ->
                log.error("IA request error de conexión - traceId: {}, error: {}", 
                    traceId, e.message)
                Mono.error(AnalisisServiceException(
                    message = "No se pudo conectar al servicio IA: ${e.message}",
                    cause = e,
                    codigo = ErrorCode.IA_SERVICE_UNAVAILABLE
                ))
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