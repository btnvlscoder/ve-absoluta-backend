package com.veabsoluta.ve_absoluta_backend.controller
import org.springframework.web.servlet.resource.NoResourceFoundException
import com.veabsoluta.ve_absoluta_backend.service.AnalisisServiceException
import com.veabsoluta.ve_absoluta_backend.service.storage.StorageException
import com.veabsoluta.ve_absoluta_backend.service.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.server.ResponseStatusException

/**
 * Manejador global de excepciones para toda la aplicación.
 * 
 * Garantiza respuestas consistentes al frontend independientemente
 * del tipo de error que ocurra.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * Maneja errores del servicio de análisis
     */
    @ExceptionHandler(AnalisisServiceException::class)
    fun handleAnalisisServiceException(ex: AnalisisServiceException): ResponseEntity<ErrorResponse> {
        log.error("Error en servicio de análisis: {} - {}", ex.codigo, ex.message)
        
        val (status, mensaje) = when (ex.codigo) {
            ErrorCode.IA_SERVICE_UNAVAILABLE -> 
                HttpStatus.SERVICE_UNAVAILABLE to "Servicio de análisis temporalmente no disponible"
            ErrorCode.IA_SERVICE_ERROR -> 
                HttpStatus.INTERNAL_SERVER_ERROR to "Error interno del servicio de análisis"
            ErrorCode.TIMEOUT -> 
                HttpStatus.GATEWAY_TIMEOUT to "La solicitud tardó demasiado tiempo"
            else -> 
                HttpStatus.INTERNAL_SERVER_ERROR to "Error inesperado: ${ex.message}"
        }
        
        return ResponseEntity.status(status)
            .body(ErrorResponse(codigo = ex.codigo.name, mensaje = mensaje))
    }

    /**
     * Maneja errores del servicio de almacenamiento
     */
    @ExceptionHandler(StorageException::class)
    fun handleStorageException(ex: StorageException): ResponseEntity<ErrorResponse> {
        log.error("Error en servicio de almacenamiento: {}", ex.message)
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse(
                codigo = "STORAGE_ERROR",
                mensaje = "Servicio de almacenamiento temporalmente no disponible"
            ))
    }

    /**
     * Maneja errores de validación de archivos
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        log.warn("Error de validación: {}", ex.reason)
        
        return ResponseEntity.status(ex.statusCode)
            .body(ErrorResponse(
                codigo = "VALIDATION_ERROR",
                mensaje = ex.reason ?: "Error de validación"
            ))
    }

    /**
     * Maneja excedencia de tamaño de archivo
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceeded(ex: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> {
        log.warn("Archivo excede tamaño máximo: {}", ex.message)
        
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse(
                codigo = "FILE_TOO_LARGE",
                mensaje = "El archivo excede el tamaño máximo permitido"
            ))
    }

    /**
     * Maneja cualquier otra excepción no prevista
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Error no manejado: ", ex)
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                codigo = "INTERNAL_ERROR",
                mensaje = "Error interno del servidor"
            ))
    }

    /**
     * Maneja rutas no encontradas (404) para no ensuciar el log
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFoundException(ex: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        // Usamos warn y NO le pasamos la excepción completa para evitar el stack trace
        log.warn("Ruta no encontrada: {}", ex.resourcePath) 
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                codigo = "NOT_FOUND",
                mensaje = "La ruta solicitada no existe en la API"
            ))
    }
}



/**
 * Respuesta de error estándar para el frontend
 */
data class ErrorResponse(
    val codigo: String,
    val mensaje: String,
    val timestamp: Long = System.currentTimeMillis()
)