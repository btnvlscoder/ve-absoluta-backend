package com.veabsoluta.ve_absoluta_backend.controller

import com.veabsoluta.ve_absoluta_backend.service.AnalisisService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

@RestController
@RequestMapping("/api/v1/deteccion")
@CrossOrigin(origins = ["http://localhost:3000"]) // Permiso para que React pueda conectar
class DetectionController(private val analisisService: AnalisisService) {

    @PostMapping("/upload")
    fun subirImagen(@RequestParam("file") file: MultipartFile): ResponseEntity<Any> {
        return try {
            // Creamos un nombre único para que no se pisen las imágenes
            val fileName = "${UUID.randomUUID()}_${file.originalFilename}"
            val path = Paths.get("uploads/").resolve(fileName)
            
            // Creamos la carpeta si no existe y guardamos los bytes de la imagen
            Files.createDirectories(path.parent)
            Files.write(path, file.bytes)

            // Pasamos la pelota al servicio para que procese con la IA
            val resultado = analisisService.procesarAnalisis(path, fileName)
            ResponseEntity.ok(resultado)
        } catch (e: Exception) {
            // Si algo explota, avisamos al frontend
            ResponseEntity.internalServerError().body("Error al subir: ${e.message}")
        }
    }
}