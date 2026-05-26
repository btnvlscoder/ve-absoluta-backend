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

        // 1. Paso 1: Subir al Storage
        val url = storageService.upload(file)
        log.debug("Archivo subido al storage - traceId: {}, url: {}", traceId, url)

        try {
            // 2. Paso 2: Ejecutar Detección (IA + Guardado en DB)
            val resultado = analisisService.ejecutarDeteccion(url, nombreOriginal)

            log.info("Análisis completado - traceId: {}, prediction: {}, confidence: {}",
                traceId, resultado.veredicto_final, resultado.confianza_global)

            return ResponseEntity.ok(resultado)
            
        } catch (e: Exception) {
            // Si Falló el motor IA o la base de datos.
            // Ejecutamos la transacción compensatoria (Saga Pattern)
            log.warn("Fallo detectado en la cadena de análisis. Ejecutando Rollback en Cloudinary para url: {} - traceId: {}", url, traceId)
            
            try {
                storageService.delete(url)
            } catch (deleteEx: Exception) {
                log.error("Fallo crítico durante el rollback en Cloudinary. Archivo huérfano: $url", deleteEx)
            }
            throw e
        }
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

        @GetMapping("/historial")
        fun obtenerHistorial(): ResponseEntity<Any> {
            log.info("Request recibido para obtener historial de casos")
            // Llamamos al servicio para que traiga todo de la base de datos
            val historial = analisisService.obtenerTodosLosCasos() 
            return ResponseEntity.ok(historial)
        }

        @GetMapping("/estadisticas")
        fun obtenerEstadisticas(): ResponseEntity<Any> {
            log.info("Request recibido para obtener estadísticas globales")
            val stats = analisisService.obtenerEstadisticasGlobales()
            return ResponseEntity.ok(stats)
        }

    }