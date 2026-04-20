package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import java.io.File

@Service
class AnalisisService(private val analisisRepository: AnalisisRepository) { // Inyectamos el repo
    
    private val mapper = jacksonObjectMapper()

    fun ejecutarDeteccion(rutaImagenAbsoluta: String, nombreArchivo: String): Analisis {
        val pythonPath = File(".venv/Scripts/python.exe").absolutePath
        val scriptPath = File("ai_engine/scripts/detector.py").absolutePath
        
        try {
            val processBuilder = ProcessBuilder(pythonPath, scriptPath, rutaImagenAbsoluta)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                // 1. Atrapamos el JSON en el DTO
                val pythonResult = mapper.readValue<PythonResponse>(output)

                // 2. Creamos la Entidad para PostgreSQL
                val nuevoRegistro = Analisis(
                    nombreArchivo = nombreArchivo,
                    prediccion = pythonResult.prediction, // CONTENIDO_REAL o CONTENIDO_IA_DETECTED
                    confianza = pythonResult.confidence
                )

                // 3. ¡Guardamos en la base de datos y retornamos!
                return analisisRepository.save(nuevoRegistro)
            } else {
                throw RuntimeException("Error en Python: $output")
            }
        } catch (e: Exception) {
            // En caso de error, podríamos guardar un registro de fallo, 
            // pero por ahora lanzamos la excepción para que el Controller la maneje.
            throw RuntimeException("Fallo al ejecutar el análisis: ${e.message}")
        }
    }
}

// Data class temporal para atrapar el JSON de Python
data class PythonResponse(
    val prediction: String,
    val confidence: Double,
    val status: String
)