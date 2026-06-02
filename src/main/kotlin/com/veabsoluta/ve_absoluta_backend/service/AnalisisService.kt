package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.DTO.*
import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.slf4j.MDC
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID

@Service
class AnalisisService(
    private val analisisRepository: AnalisisRepository,
    webClientBuilder: WebClient.Builder
) {
    @Value("\${ai.service.url}")
    private lateinit var aiServiceUrl: String

    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(aiServiceUrl)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)
            }
            .build()
    }

    private val log = LoggerFactory.getLogger(AnalisisService::class.java)

    private val circuitBreaker: CircuitBreaker by lazy {
        CircuitBreaker.of("iaService", CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofSeconds(45))
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build())
    }

    fun ejecutarDeteccion(rutaImagen: String, nombreArchivo: String): AnalisisForenseResponse {
        val request = AnalisisRequest(url = rutaImagen)
        val pythonResult = realizarPeticionIAInternal(request).block()

        val nuevoRegistro = Analisis(
            nombreArchivo = nombreArchivo,
            rutaArchivo = rutaImagen,
            prediccion = pythonResult?.veredicto_final ?: "ERROR",
            confianza = (pythonResult?.confianza_global ?: 0.0) / 100.0
        )
        val analisisGuardado = analisisRepository.save(nuevoRegistro)

        return AnalisisForenseResponse(
            id = analisisGuardado.id,
            nombreArchivo = analisisGuardado.nombreArchivo,
            veredicto_final = pythonResult?.veredicto_final ?: "ERROR",
            confianza_global = pythonResult?.confianza_global ?: 0.0,
            heatmap_base64 = pythonResult?.heatmap_base64,
            heatmap_threshold = pythonResult?.heatmap_threshold,
            heatmap_rollout = pythonResult?.heatmap_rollout,
            desglose_pericial = pythonResult?.desglose_pericial,
            metadata = pythonResult?.metadata,
            datos_crudos_frontend = pythonResult?.datos_crudos_frontend
        )
    }

    internal fun realizarPeticionIAInternal(request: AnalisisRequest): Mono<PythonResponse> {
        val traceId = MDC.get("traceId") ?: UUID.randomUUID().toString()
        MDC.put("traceId", traceId)

        log.info("IA request iniciada - traceId: {}, url: {}", traceId, request.url)

        return webClient.post()
            .uri("")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .onStatus({ status -> status.is4xxClientError }) { clientResponse ->
                log.warn("IA response 4xx - traceId: {}, status: {}", traceId, clientResponse.statusCode())
                clientResponse.bodyToMono(String::class.java).flatMap { body ->
                    Mono.error(AnalisisServiceException("Error 4xx IA: $body", codigo = ErrorCode.INVALID_IMAGE))
                }
            }
            .onStatus({ status -> status.is5xxServerError }) { clientResponse ->
                log.error("IA response 5xx - traceId: {}, status: {}", traceId, clientResponse.statusCode())
                clientResponse.bodyToMono(String::class.java).flatMap { body ->
                    Mono.error(AnalisisServiceException("Error 5xx IA: $body", codigo = ErrorCode.IA_SERVICE_ERROR))
                }
            }
            .bodyToMono(PythonResponse::class.java)
            .timeout(Duration.ofSeconds(90))
            .retryWhen(reactor.util.retry.Retry.backoff(1, Duration.ofMillis(1000))
                .filter { throwable -> throwable is WebClientResponseException && throwable.statusCode.is5xxServerError }
            )
            .map { response -> response.validate() }
            .map { pythonResult ->
                val prediccion = pythonResult.veredicto_final ?: throw IllegalStateException("Veredicto nulo")
                val prediccionNormalizada = normalizarPrediccion(prediccion)
                pythonResult.copy(veredicto_final = prediccionNormalizada)
            }
            .onErrorMap { e ->
                AnalisisServiceException("Fallo en la comunicación con el motor forense: ${e.message}", e, ErrorCode.IA_SERVICE_UNAVAILABLE)
            }
    }

    private fun normalizarPrediccion(prediccion: String): String {
        val normalized = prediccion.trim().lowercase()
        return when {
            normalized.contains("fake") || normalized.contains("artificial") -> "FAKE"
            normalized.contains("real") || normalized.contains("authentic") -> "REAL"
            else -> throw AnalisisServiceException("Predicción IA no reconocida: '$prediccion'", null, ErrorCode.IA_SERVICE_ERROR)
        }
    }

    fun obtenerTodosLosCasos(): List<Analisis> { 
        return analisisRepository.findAll().sortedByDescending { it.fecha } 
    }

    fun obtenerEstadisticasGlobales(): Map<String, Long> {
    val total = analisisRepository.count()
    val reales = analisisRepository.countByPrediccion("REAL")
    val fakes = analisisRepository.countByPrediccion("FAKE")
    
    return mapOf(
        "total" to total,
        "reales" to reales,
        "fake" to fakes
        )
    }
}



class AnalisisServiceException(
    message: String,
    cause: Throwable? = null,
    val codigo: ErrorCode = ErrorCode.UNKNOWN
) : RuntimeException(message, cause)

enum class ErrorCode {
    IA_SERVICE_UNAVAILABLE,
    IA_SERVICE_ERROR,
    INVALID_IMAGE,
    TIMEOUT,
    STORAGE_ERROR,
    UNKNOWN
}