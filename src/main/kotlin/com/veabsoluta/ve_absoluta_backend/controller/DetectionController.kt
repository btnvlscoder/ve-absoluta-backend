package com.veabsoluta.ve_absoluta_backend.controller

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.service.AnalisisService
import com.veabsoluta.ve_absoluta_backend.service.storage.StorageService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

/**
 * Controlador REST para detección de deepfakes.
 * 
 * Validaciones implementadas:
 * - Tipo MIME: solo acepta image/jpeg, image/png, image/webp
 * - Tamaño máximo: 10MB
 * - Archivo no vacío
 */
@RestController
@RequestMapping("/api/v1/analizar")
@CrossOrigin(origins = ["http://localhost:3000"], allowedHeaders = ["*"])
class DetectionController(
    private val analisisService: AnalisisService,
    private val storageService: StorageService
) {
    
    private val log = LoggerFactory.getLogger(DetectionController::class.java)
    
    // Constantes de validación
    companion object {
        private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024 // 10MB
        private val ALLOWED_MIME_TYPES = setOf(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/webp"
        )
    }

    @PostMapping("/upload")
    fun upload(@RequestParam file: MultipartFile): ResponseEntity<Analisis> {
        log.info("Recibido request de upload: {} ({} bytes)", 
            file.originalFilename, file.size)
        
        // ========== VALIDACIONES ==========
        validarArchivo(file)
        // ==================================
        
        val nombreOriginal = file.originalFilename?.takeIf { it.isNotBlank() } 
            ?: "imagen_${System.currentTimeMillis()}.jpg"
        
        // 1. Subimos la foto a la nube
        val url = storageService.upload(file)
        log.debug("Archivo subido al storage: {}", url)
        
        // 2. Llamamos al orquestador IA
        val resultado = analisisService.ejecutarDeteccion(url, nombreOriginal)
        log.info("Análisis completado: predicción={}, confianza={}", 
            resultado.prediccion, resultado.confianza)
        
        // 3. Devolvemos el JSON al frontend
        return ResponseEntity.ok(resultado)
    }
    
    /**
     * Valida el archivo recibido.
     * @throws ResponseStatusException si la validación falla
     */
    internal fun validarArchivo(file: MultipartFile) {
        // Validación 1: Archivo no vacío
        require(!file.isEmpty) { 
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, 
                "El archivo no puede estar vacío"
            )
        }
        
        // Validación 2: Tamaño máximo
        require(file.size <= MAX_FILE_SIZE_BYTES) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "El archivo excede el tamaño máximo de 10MB"
            )
        }
        
        // Validación 3: Tipo MIME
        val contentType = file.contentType
        require(contentType != null && contentType in ALLOWED_MIME_TYPES) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Tipo de archivo no válido. Solo se aceptan: JPEG, PNG, WebP"
            )
        }
    }
}