package com.veabsoluta.ve_absoluta_backend.controller

import com.veabsoluta.ve_absoluta_backend.service.AnalisisService
import com.veabsoluta.ve_absoluta_backend.service.CloudinaryService // IMPORT NUEVO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/deteccion")
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class DetectionController(
    private val analisisService: AnalisisService,
    private val cloudinaryService: CloudinaryService // INYECCIÓN ACTUALIZADA
) {

    @PostMapping("/upload")
    fun uploadImage(@RequestParam("file") file: MultipartFile): ResponseEntity<Any> {
        return try {
            // 1. Sube a la nube y obtiene la URL de Cloudinary (ej: https://res.cloudinary.com/...)
            val urlArchivo = cloudinaryService.subirArchivo(file)

            // 2. Envía esa URL al motor de IA en Hugging Face
            val resultadoBd = analisisService.ejecutarDeteccion(
                rutaImagen = urlArchivo,
                nombreArchivo = file.originalFilename ?: "desconocido.mp4"
            )
            
            ResponseEntity.ok(resultadoBd)
        } catch (e: Exception) {
            ResponseEntity.internalServerError().body(mapOf("error" to e.message))
        }
    }
}