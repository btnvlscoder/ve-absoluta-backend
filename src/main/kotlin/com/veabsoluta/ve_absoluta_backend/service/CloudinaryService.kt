package com.veabsoluta.ve_absoluta_backend.service

import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * Servicio para gestionar almacenamiento de archivos multimedia en Cloudinary.
 * 
 * Ventajas implementadas:
 * - Retorna instantáneamente una URL pública (secure_url)
 * - Delega la gestión del almacenamiento y ancho de banda a la nube
 * - Manejo de errores estructurado con CloudinaryServiceException
 */
@Service
class CloudinaryService {

    private val log = LoggerFactory.getLogger(CloudinaryService::class.java)

    @Value("\${cloudinary.cloud-name}")
    private lateinit var cloudName: String

    @Value("\${cloudinary.api-key}")
    private lateinit var apiKey: String

    @Value("\${cloudinary.api-secret}")
    private lateinit var apiSecret: String

    // Inicialización Lazy del cliente de Cloudinary
    private val cloudinary: Cloudinary by lazy {
        Cloudinary(ObjectUtils.asMap(
            "cloud_name", cloudName,
            "api_key", apiKey,
            "api_secret", apiSecret
        ))
    }

    /**
     * Sube un archivo a Cloudinary y retorna su URL segura HTTPS.
     * 
     * @param file Archivo multipart del request
     * @return URL pública del archivo almacenado
     * @throws CloudinaryServiceException si falla la subida
     */
    fun subirArchivo(file: MultipartFile): String {
        return try {
            log.debug("Subiendo archivo a Cloudinary: {} ({} bytes)", 
                file.originalFilename, file.size)
            
            val uploadOptions = ObjectUtils.asMap(
                "public_id", file.originalFilename?.substringBeforeLast(".") ?: "upload",
                "resource_type", "auto"
            )
            
            val uploadResult = cloudinary.uploader().upload(
                file.inputStream, 
                uploadOptions
            )

            val secureUrl = uploadResult["secure_url"].toString()
            log.info("Archivo subido exitosamente: {}", secureUrl)
            
            secureUrl
            
        } catch (e: Exception) {
            log.error("Error al subir archivo a Cloudinary: {}", e.message, e)
            throw CloudinaryServiceException(
                message = "Error al subir imagen a Cloudinary: ${e.message}",
                cause = e,
                codigo = ErrorCode.STORAGE_ERROR
            )
        }
    }
}