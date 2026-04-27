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
     * Inferencia en la nube: Conecta con la API de Gradio en Hugging Face.
     */
    private fun ejecutarDeteccionCloud(rutaImagen: String, nombreArchivo: String): Analisis {
        val restTemplate = RestTemplate()
        
        // 1. Configuración de encabezados
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        
        // EL ARREGLO 1: Le pasamos tu token de seguridad directamente desde Render
        val token = System.getenv("HF_TOKEN")
        if (!token.isNullOrBlank()) {
            headers.setBearerAuth(token)
        }

        // 2. Definición del cuerpo siguiendo el estándar de Gradio
        val body = mapOf("data" to listOf(rutaImagen))
        val request = HttpEntity(body, headers)

        try {
            // EL ARREGLO 2: Cambiamos /api/predict por /run/predict
            val hfEndpoint = "https://btnvlscoder-ve-absoluta-api.hf.space/api/predecir_imagen"

            // 3. Ejecución de la petición POST
            val response = restTemplate.postForObject(hfEndpoint, request, GradioResponse::class.java)

            // 4. Extracción segura del resultado
            val iaResult = response?.data?.firstOrNull() 
                ?: throw RuntimeException("Hugging Face no devolvió una respuesta válida")

            // 5. Creación y persistencia
            val nuevoRegistro = Analisis(
                nombreArchivo = nombreArchivo,
                rutaArchivo = rutaImagen,
                prediccion = iaResult.label,
                confianza = iaResult.valorConfianza
            )

            return analisisRepository.save(nuevoRegistro)
            
        } catch (e: Exception) {
            println("DETALLE ERROR CLOUD: ${e.message}")
            throw RuntimeException("Fallo la comunicación con el servicio de IA: ${e.message}")
        }
    }
}