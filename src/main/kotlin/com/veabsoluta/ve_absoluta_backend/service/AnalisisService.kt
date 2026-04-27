package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import com.veabsoluta.ve_absoluta_backend.dto.GradioResponse
import com.veabsoluta.ve_absoluta_backend.dto.GradioOutput // <-- ¡ESTE ERA EL IMPORT QUE FALTABA!
import com.veabsoluta.ve_absoluta_backend.dto.PythonResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import org.springframework.web.client.RestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

/**
 * Servicio de orquestación para detección de deepfakes.
 * Maneja la lógica de inferencia tanto local (Python) como en la nube (Hugging Face).
 */
@Service
class AnalisisService(
    private val analisisRepository: AnalisisRepository
) {
    
    private val mapper = jacksonObjectMapper()

    @Value("\${inference.mode:local}")
    private lateinit var inferenceMode: String

    /**
     * Punto de entrada principal para el análisis.
     */
    fun ejecutarDeteccion(rutaImagen: String, nombreArchivo: String): Analisis {
        return when (inferenceMode) {
            "cloud" -> ejecutarDeteccionCloud(rutaImagen, nombreArchivo)
            else -> ejecutarDeteccionLocal(rutaImagen, nombreArchivo)
        }
    }

    /**
     * Inferencia local: Llama al script de Python mediante ProcessBuilder.
     */
    private fun ejecutarDeteccionLocal(rutaImagen: String, nombreArchivo: String): Analisis {
        val pythonPath = File(".venv/Scripts/python.exe").absolutePath
        val scriptPath = File("ai_engine/scripts/detector.py").absolutePath
        
        try {
            val processBuilder = ProcessBuilder(pythonPath, scriptPath, rutaImagen)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val pythonResult = mapper.readValue<PythonResponse>(output)

                val nuevoRegistro = Analisis(
                    nombreArchivo = nombreArchivo,
                    rutaArchivo = rutaImagen,
                    prediccion = pythonResult.prediction,
                    confianza = pythonResult.confidence
                )

                return analisisRepository.save(nuevoRegistro)
            } else {
                throw RuntimeException("Error en el motor local de Python: $output")
            }
        } catch (e: Exception) {
            throw RuntimeException("Fallo al ejecutar el análisis local: ${e.message}")
        }
    }

    /**
     * Inferencia en la nube: Conecta con la API de Gradio 4 (Arquitectura de Eventos Asíncronos).
     */
    private fun ejecutarDeteccionCloud(rutaImagen: String, nombreArchivo: String): Analisis {
        val restTemplate = RestTemplate()
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        
        val token = System.getenv("HF_TOKEN")
        if (!token.isNullOrBlank()) {
            headers.setBearerAuth(token)
        }

        // Empaquetamos el archivo para Gradio 4
        val fileData = mapOf(
            "url" to rutaImagen,
            "meta" to mapOf("_type" to "gradio.FileData")
        )
        val body = mapOf("data" to listOf(fileData))
        val request = HttpEntity(body, headers)

        try {
            // ==========================================
            // PASO 1: Tomar un ticket en la cola de IA
            // ==========================================
            val callEndpoint = "https://btnvlscoder-ve-absoluta-api.hf.space/gradio_api/call/predecir_imagen"
            val callResponse = restTemplate.postForObject(callEndpoint, request, Map::class.java)
            
            val eventId = callResponse?.get("event_id") as? String 
                ?: throw RuntimeException("Hugging Face no nos dio un ticket de atención (event_id)")

            // ==========================================
            // PASO 2: Escuchar el evento hasta que termine
            // ==========================================
            val streamEndpoint = "$callEndpoint/$eventId"
            val streamRequest = HttpEntity<Any>(headers)
            
            // Hacemos un GET y nos quedamos esperando el stream de eventos
            val streamResponse = restTemplate.exchange(
                streamEndpoint, 
                org.springframework.http.HttpMethod.GET, 
                streamRequest, 
                String::class.java
            )
            
            val sseResult = streamResponse.body ?: throw RuntimeException("El servidor de IA no respondió datos")

            // ==========================================
            // PASO 3: Leer el veredicto final
            // ==========================================
            if (!sseResult.contains("event: complete")) {
                throw RuntimeException("El análisis fue interrumpido por Hugging Face: \n$sseResult")
            }

            // Extraemos la línea "data: [...]" que viene justo después de "event: complete"
            val dataLine = sseResult.substringAfter("event: complete")
                                    .lines()
                                    .find { it.startsWith("data:") }
                                    ?.removePrefix("data:")?.trim()
                ?: throw RuntimeException("No se encontró el JSON final en el stream")

            // Traducimos el JSON usando el módulo de Kotlin
            val resultList: List<GradioOutput> = mapper.readValue(dataLine)
            val iaResult = resultList.firstOrNull() 
                ?: throw RuntimeException("La IA no devolvió predicciones válidas")

            val nuevoRegistro = Analisis(
                nombreArchivo = nombreArchivo,
                rutaArchivo = rutaImagen,
                prediccion = iaResult.valorPrediccion,
                confianza = iaResult.valorConfianza
            )

            return analisisRepository.save(nuevoRegistro)
            
        } catch (e: Exception) {
            println("DETALLE ERROR CLOUD: ${e.message}")
            throw RuntimeException("Fallo la comunicación asíncrona con IA: ${e.message}")
        }
    }
}