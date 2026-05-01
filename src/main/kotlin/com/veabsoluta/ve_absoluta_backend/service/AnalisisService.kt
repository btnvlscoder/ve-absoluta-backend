package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

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
    webClientBuilder: WebClient.Builder
) {
    
    private val log = LoggerFactory.getLogger(AnalisisService::class.java)
    
    // Configuración desde application.properties
    @Value("\${ai.service.url:http://localhost:8000/api/v1/analizar}")
    private lateinit var aiServiceUrl: String

    @Value("\${veabsoluta.ia.threshold:0.65}")
    private var umbralDeteccion: Double = 0.65

    // WebClient con timeouts configurados
    private val webClient: WebClient by lazy {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000) // 5s conexión
            .responseTimeout(Duration.ofSeconds(30)) // 30s respuesta
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(30, TimeUnit.SECONDS))
                conn.addHandlerLast(WriteTimeoutHandler(30, TimeUnit.SECONDS))
            }
        
        webClientBuilder
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
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
        } catch (e: Exception) {
            log.error("Error en comunicación con servicio IA para {}", nombreArchivo, e)
            throw AnalisisServiceException(
                message = "Error al comunicarse con el servicio de análisis: ${e.message}",
                cause = e,
                codigo = ErrorCode.IA_SERVICE_UNAVAILABLE
            )
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
     * Realiza la petición HTTP al servicio IA con manejo de errores específico
     */
    private fun realizarPeticionIA(request: AnalisisRequest): PythonResponse {
        return webClient.post()
            .uri(aiServiceUrl)
            .bodyValue(request)
            .retrieve()
            .onStatus(HttpStatusCode::is5xx) { response ->
                log.error("Error 5xx del servicio IA: {}", response.statusCode())
                response.createException()
            }
            .bodyToMono(PythonResponse::class.java)
            .block() 
            ?: throw AnalisisServiceException(
                message = "El servicio IA devolvió una respuesta nula",
                codigo = ErrorCode.IA_SERVICE_ERROR
            )
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