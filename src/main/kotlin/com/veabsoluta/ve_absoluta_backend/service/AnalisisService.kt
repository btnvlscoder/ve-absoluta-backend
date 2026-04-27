package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
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
 * 
 * Maneja el flujo completo:
 * 1. Recibe URL del archivo en MinIO
 * 2. Ejecuta inferencia (local o cloud)
 * 3. Persiste resultado en PostgreSQL
 */
@Service
class AnalisisService(
    private val analisisRepository: AnalisisRepository
) {
    
    private val mapper = jacksonObjectMapper()

    @Value("\${inference.mode:local}")
    private lateinit var inferenceMode: String  // "local" o "cloud"

    /**
     * Ejecuta el flujo completo de detección.
     * 
     * @param rutaImagen URL del archivo en MinIO (o ruta local si es modo local)
     * @param nombreArchivo Nombre original del archivo
     */
    fun ejecutarDeteccion(rutaImagen: String, nombreArchivo: String): Analisis {
        return when (inferenceMode) {
            "cloud" -> ejecutarDeteccionCloud(rutaImagen, nombreArchivo)
            else -> ejecutarDeteccionLocal(rutaImagen, nombreArchivo)
        }
    }

    /**
     * Inferencia local (usa ProcessBuilder con Python local)
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
                    rutaArchivo = rutaImagen,  // URL del archivo en MinIO
                    prediccion = pythonResult.prediction,
                    confianza = pythonResult.confidence
                )

                return analisisRepository.save(nuevoRegistro)
            } else {
                throw RuntimeException("Error en Python: $output")
            }
        } catch (e: Exception) {
            throw RuntimeException("Fallo al ejecutar el análisis: ${e.message}")
        }
    }

    /**
     * Inferencia en la nube (Llamada HTTP a Hugging Face / Gradio)
     */
    private fun ejecutarDeteccionCloud(rutaImagen: String, nombreArchivo: String): Analisis {
        val restTemplate = RestTemplate()
        
        // 1. Armar los headers
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        // 2. Empaquetar la petición como Gradio lo exige: { "data": [ "url" ] }
        val body = mapOf("data" to listOf(rutaImagen))
        val request = HttpEntity(body, headers)

        try {
            // Reemplaza "TU_USUARIO" por tu username de Hugging Face
            val hfEndpoint = "https://btnvlscoder-ve-absoluta-api.hf.space/api/predict"

            // 3. Disparar el HTTP POST a la nube
            val response = restTemplate.postForObject(hfEndpoint, request, GradioResponse::class.java)

            // 4. Extraer nuestro JSON desde el arreglo 'data' de Gradio
            val iaResult = response?.data?.firstOrNull() 
                ?: throw RuntimeException("El servidor de IA no devolvió datos válidos")

            // 5. Persistir en PostgreSQL (Neon)
            val nuevoRegistro = Analisis(
                nombreArchivo = nombreArchivo,
                rutaArchivo = rutaImagen, // Guardamos la URL pública de MinIO/S3
                prediccion = iaResult.prediccion,
                confianza = iaResult.confianza
            )

            return analisisRepository.save(nuevoRegistro)
            
        } catch (e: Exception) {
            throw RuntimeException("Fallo la comunicación con Hugging Face: ${e.message}")
        }
    }
}

// ==========================================
// DTOs para mapear la respuesta de Gradio
// ==========================================
data class GradioResponse(
    val data: List<GradioOutput>
)

data class GradioOutput(
    val prediccion: String,
    val confianza: Double
)

data class PythonResponse(
    val prediction: String,
    val confidence: Double,
    val status: String? = null
)