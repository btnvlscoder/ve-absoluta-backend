package com.veabsoluta.ve_absoluta_backend.service


import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class AnalisisService(private val analisisRepository: AnalisisRepository) {
    private val mapper = ObjectMapper()

    fun procesarAnalisis(path: Path, fileName: String): String {
        // Llamamos al motor de IA en Python pasando la ruta de la imagen
        val jsonRespuesta = ejecutarMotorPython(path.toString())
        val rootNode = mapper.readTree(jsonRespuesta)

        // Si la IA respondió bien, guardamos el resultado en PostgreSQL
        if (!rootNode.has("error")) {
            val nuevoAnalisis = Analisis(
                nombreArchivo = fileName,
                prediccion = rootNode.get("prediction").asText(),
                confianza = rootNode.get("confidence").asDouble()
            )
            analisisRepository.save(nuevoAnalisis) // Guardado en la tabla historial_analisis
        }
        return jsonRespuesta
    }

    // Método para ejecutar el script de Python y capturar lo que nos diga
    private fun ejecutarMotorPython(rutaImagen: String): String {
        // Aquí se podría usar ProcessBuilder para llamar a: python ai_engine/detector.py
        return "{\"prediction\": \"CONTENIDO_IA_DETECTED\", \"confidence\": 0.89}"
    }
}