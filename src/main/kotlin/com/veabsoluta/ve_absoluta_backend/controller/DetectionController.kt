package com.veabsoluta.ve_absoluta_backend.controller

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.service.AnalisisService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

@RestController
@RequestMapping("/api/v1/deteccion")
@CrossOrigin(origins = ["*"])
class DetectionController(private val analisisService: AnalisisService) {

    private val uploadDir = "uploads"

    @PostMapping("/upload")
    fun uploadImage(@RequestParam("file") file: MultipartFile): ResponseEntity<Any> {
        return try {
            val path = Paths.get(uploadDir)
            if (!Files.exists(path)) Files.createDirectories(path)

            val originalName = file.originalFilename ?: "imagen_desconocida.jpg"
            val uniqueFileName = "${UUID.randomUUID()}_$originalName"
            val filePath = path.resolve(uniqueFileName)
            
            Files.copy(file.inputStream, filePath)

            // Llamamos al servicio pasando la ruta física y el nombre original para la BD
            val resultadoBd = analisisService.ejecutarDeteccion(filePath.toAbsolutePath().toString(), originalName)
            
            ResponseEntity.ok(resultadoBd)
        } catch (e: Exception) {
            ResponseEntity.internalServerError().body(mapOf("error" to e.message))
        }
    }
}