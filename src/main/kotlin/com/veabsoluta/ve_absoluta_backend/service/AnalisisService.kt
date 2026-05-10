package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.slf4j.MDC
import reactor.core.publisher.Mono
import java.io.IOException
import java.time.Duration
import java.util.UUID

// ==========================================
// 1. DTOs PARA LA IA (EL SÚPER JSON)
// ==========================================
data class AnalisisRequest(
    val url: String,
    val umbral: Double = 0.65,
    val model_version: String = "VE_ABSOLUTA_ViT_V2"
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PythonResponse(
    val veredicto_final: String?,
    val confianza_global: Double?,
    val heatmap_base64: String?,
    val desglose_pericial: DesglosePericialDTO?,
    val metadata: Map<String, Any>? = null
) {
    fun validate(): PythonResponse {
        require(!veredicto_final.isNullOrBlank()) { "Respuesta IA inválida: veredicto vacío" }
        require(confianza_global != null) { "Respuesta IA inválida: confianza nula" }
        return this
    }
}

data class DesglosePericialDTO(
    val analisis_ia_vit: DetalleAnalisisDTO?,
    val analisis_ela: DetalleAnalisisDTO?
)

data class DetalleAnalisisDTO(
    val estado: String?,
    val detalle: String?,
    val metricas: Map<String, Any>? = null
)

// DTO para enviar al Frontend (Combina datos de la BD y de la IA)
data class AnalisisForenseResponse(
    val id: Any?, // ID de la base de datos
    val nombreArchivo: String,
    val veredicto_final: String,
    val confianza_global: Double,
    val heatmap_base64: String?,
    val desglose_pericial: DesglosePericialDTO?
)

// ==========================================
// 2. EL SERVICIO ORQUESTADOR
// ==========================================
@Service
class AnalisisService(
    private val analisisRepository: AnalisisRepository,
    webClientBuilder: WebClient.Builder
) {
    // Apuntando al endpoint modularizado en tu Hugging Face
    @Value("\${ai.service.url}")
    private lateinit var aiServiceUrl: String

    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(aiServiceUrl)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .codecs { configurer -> 
                // Aumentamos el límite de memoria del WebClient para soportar el Base64 gigante del mapa de calor (10MB)
                configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) 
            }
            .build()
    }
    
    private val log = LoggerFactory.getLogger(AnalisisService::class.java)
    
    private val circuitBreaker: CircuitBreaker by lazy {
        CircuitBreaker.of("iaService", CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofSeconds(45)) // Aumentado por el análisis matemático
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build())
    }

    /**
     * Orquestador principal: delega la inferencia al microservicio de Python
     */
    fun ejecutarDeteccion(rutaImagen: String, nombreArchivo: String): AnalisisForenseResponse {
        val request = AnalisisRequest(
            url = rutaImagen
        )

        // 1. Llamamos a la IA (Puede tardar por el cálculo de ELA y ViT)
        val pythonResult = realizarPeticionIAInternal(request).block()

        // 2. Persistimos los datos básicos en PostgreSQL para el historial
        val nuevoRegistro = Analisis(
            nombreArchivo = nombreArchivo,
            rutaArchivo = rutaImagen,
            prediccion = pythonResult?.veredicto_final ?: "ERROR",
            confianza = (pythonResult?.confianza_global ?: 0.0) / 100.0 // BD guarda 0.0 a 1.0
        )
        val analisisGuardado = analisisRepository.save(nuevoRegistro)

        // 3. Empaquetamos todo (Datos de BD + Evidencia Forense) para React
        return AnalisisForenseResponse(
            id = analisisGuardado.id,
            nombreArchivo = analisisGuardado.nombreArchivo,
            veredicto_final = pythonResult?.veredicto_final ?: "ERROR",
            confianza_global = pythonResult?.confianza_global ?: 0.0,
            heatmap_base64 = pythonResult?.heatmap_base64,
            desglose_pericial = pythonResult?.desglose_pericial
        )
    }
    
    internal fun realizarPeticionIAInternal(request: AnalisisRequest): Mono<PythonResponse> {
        val traceId = MDC.get("traceId") ?: UUID.randomUUID().toString()
        MDC.put("traceId", traceId)
        
        log.info("IA request iniciada - traceId: {}, url: {}", traceId, request.url)

        return webClient.post()
            .uri("") // Asumimos que aiServiceUrl ya incluye el /api/v1/analizar-completo
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
            .timeout(Duration.ofSeconds(90)) // Timeout holgado para procesamiento profundo
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