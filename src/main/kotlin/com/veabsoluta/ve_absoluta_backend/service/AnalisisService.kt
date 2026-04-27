package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import com.veabsoluta.ve_absoluta_backend.dto.GradioResponse
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
     * Inferencia en la nube: Conecta con la API de Gradio 4 en Hugging Face.
     */
    private fun ejecutarDeteccionCloud(rutaImagen: String, nombreArchivo: String): Analisis {
        val restTemplate = RestTemplate()
        
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        
        val token = System.getenv("HF_TOKEN")
        if (!token.isNullOrBlank()) {
            headers.setBearerAuth(token)
        }

        // 4: Mandar la URL como un objeto FileData
        val fileData = mapOf(
            "url" to rutaImagen,
            "meta" to mapOf("_type" to "gradio.FileData")
        )
        
        // Empaquetamos en el arreglo 'data'
        val body = mapOf("data" to listOf(fileData))
        val request = HttpEntity(body, headers)

        try {
            // LA URL DEFINITIVA: Gradio 4 usa el prefijo /gradio_api/run/
            val hfEndpoint = "https://btnvlscoder-ve-absoluta-api.hf.space/gradio_api/run/predecir_imagen"

            val response = restTemplate.postForObject(hfEndpoint, request, GradioResponse::class.java)

            val iaResult = response?.data?.firstOrNull() 
                ?: throw RuntimeException("Hugging Face no devolvió una respuesta válida")

            val nuevoRegistro = Analisis(
                nombreArchivo = nombreArchivo,
                rutaArchivo = rutaImagen,
                prediccion = iaResult.valorPrediccion, // Usa el getter blindado
                confianza = iaResult.valorConfianza    // Usa el getter blindado
            )

            return analisisRepository.save(nuevoRegistro)
            
        } catch (e: Exception) {
            println("DETALLE ERROR CLOUD: ${e.message}")
            throw RuntimeException("Fallo la comunicación con el servicio de IA: ${e.message}")
        }
    }
}