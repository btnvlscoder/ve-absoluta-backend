package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

// 1. Contratos DTO claros (como pidió el auditor)
data class AnalisisRequest(val url_imagen: String, val umbral: Double)
data class PythonResponse(val prediction: String, val confidence: Double)

@Service
class AnalisisService(
    private val analisisRepository: AnalisisRepository,
    webClientBuilder: WebClient.Builder // Inyectamos el builder moderno de Spring
) {
    // Leemos la configuración desde application.properties
    @Value("\${ai.service.url:http://localhost:8000/api/v1/analizar}")
    private lateinit var aiServiceUrl: String

    @Value("\${veabsoluta.ia.threshold:0.65}")
    private var umbralDeteccion: Double = 0.65

    // Construimos el cliente HTTP reactivo
    private val webClient = webClientBuilder.build()

    /**
     * ORQUESTADOR PRINCIPAL: Delega la carga pesada al microservicio de Python
     */
    fun ejecutarDeteccion(rutaImagen: String, nombreArchivo: String): Analisis {
        
        // Empaquetamos la petición con nuestra regla de negocio
        val request = AnalisisRequest(
            url_imagen = rutaImagen,
            umbral = umbralDeteccion
        )

        try {
            // Conexión HTTP moderna y limpia hacia FastAPI
            val pythonResult = webClient.post()
                .uri(aiServiceUrl)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PythonResponse::class.java)
                .block() // Bloqueamos temporalmente hasta que Python responda (milisegundos)
                ?: throw RuntimeException("El motor IA devolvió una respuesta nula")

            // Guardamos el veredicto en PostgreSQL
            val nuevoRegistro = Analisis(
                nombreArchivo = nombreArchivo,
                rutaArchivo = rutaImagen,
                prediccion = pythonResult.prediction,
                confianza = pythonResult.confidence
            )

            return analisisRepository.save(nuevoRegistro)
            
        } catch (e: Exception) {
            throw RuntimeException("Fallo crítico de comunicación con el motor IA: ${e.message}")
        }
    }
}