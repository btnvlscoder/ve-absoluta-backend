package com.veabsoluta.ve_absoluta_backend.controller

import com.veabsoluta.ve_absoluta_backend.service.AnalisisService
import com.veabsoluta.ve_absoluta_backend.service.storage.StorageService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/analizar")
class AnalisisController(  
    private val analisisService: AnalisisService,
    private val storageService: StorageService
) {
    
    private val log = LoggerFactory.getLogger(AnalisisController::class.java)
    
    companion object {
        private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024 
        private val ALLOWED_MIME_TYPES = setOf(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/webp"
        )
    }

    @PostMapping("/upload")
    fun upload(@RequestParam("file") file: MultipartFile): ResponseEntity<Any> {
        val traceId = UUID.randomUUID().toString()
        MDC.put("traceId", traceId)
        
        log.info("Request upload recibido - traceId: {}, filename: {}, size: {}", 
            traceId, file.originalFilename, file.size)
        
        validarArchivo(file)
        
        val nombreOriginal = file.originalFilename?.takeIf { it.isNotBlank() } 
            ?: "imagen_${System.currentTimeMillis()}.jpg"
        
        // 1. Subimos la foto a la nube
        val url = storageService.upload(file)
        log.debug("Archivo subido al storage - traceId: {}, url: {}", traceId, url)
        
        // 2. Llamamos al orquestador IA 
        // El resultado ahora es un AnalisisForenseResponse
        val resultado = analisisService.ejecutarDeteccion(url, nombreOriginal)
        
        //Los nombres de los campos en el log ahora coinciden con el nuevo DTO
        log.info("Análisis completado - traceId: {}, prediction: {}, confidence: {}", 
            traceId, resultado.veredicto_final, resultado.confianza_global)
        
        // 3. Devolvemos el Súper JSON al frontend
        return ResponseEntity.ok(resultado)
    }
    
    internal fun validarArchivo(file: MultipartFile) {
        if (file.isEmpty) { 
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo no puede estar vacío")
        }
        
        if (file.size > MAX_FILE_SIZE_BYTES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo excede el tamaño máximo de 10MB")
        }
        
        val contentType = file.contentType
        if (contentType == null || contentType !in ALLOWED_MIME_TYPES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de archivo no válido. Solo se aceptan: JPEG, PNG, WebP")
        }
    }
}