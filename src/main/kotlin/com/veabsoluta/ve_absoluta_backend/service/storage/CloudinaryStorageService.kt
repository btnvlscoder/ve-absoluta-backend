package com.veabsoluta.ve_absoluta_backend.service.storage

import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.FileOutputStream
import java.util.*

@Service
class CloudinaryStorageService(
    @Value("\${cloudinary.cloud-name}") private val cloudName: String,
    @Value("\${cloudinary.api-key}") private val apiKey: String,
    @Value("\${cloudinary.api-secret}") private val apiSecret: String
) : StorageService {

    private val log = LoggerFactory.getLogger(CloudinaryStorageService::class.java)

    private val cloudinary: Cloudinary by lazy {
        Cloudinary(ObjectUtils.asMap(
            "cloud_name", cloudName,
            "api_key", apiKey,
            "api_secret", apiSecret
        ))
    }

    override fun upload(file: MultipartFile): String {
        var tempFile: File? = null
        try {
            tempFile = convertMultiPartToFile(file)

            val uploadParams = ObjectUtils.asMap(
                "folder", "ve-absoluta-uploads",
                "public_id", "img_${UUID.randomUUID()}",
                "resource_type", "auto"
            )

            log.debug("Iniciando subida a Cloudinary del archivo temporal...")
            val uploadResult = cloudinary.uploader().upload(tempFile, uploadParams)

            val secureUrl = uploadResult["secure_url"] as String
            log.info("Subida a Cloudinary exitosa. URL: {}", secureUrl)

            return secureUrl

        } catch (e: Exception) {
            log.error("Error crítico al subir a Cloudinary: ${e.message}", e)
            throw StorageException("Error al subir imagen a Cloudinary: ${e.message}", e)
        } finally {
            tempFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
        }
    }
    
    override fun delete(fileUrl: String) {
        try {
            // Ejemplo URL: https://res.cloudinary.com/.../ve-absoluta-uploads/img_uuid.jpg
            // Extraemos "ve-absoluta-uploads/img_uuid" para decírselo a Cloudinary
            val urlParts = fileUrl.split("/")
            val folder = urlParts[urlParts.size - 2]
            val fileName = urlParts.last().substringBeforeLast(".")
            val publicId = "$folder/$fileName"

            log.debug("Intentando eliminar imagen huérfana (Rollback): {}", publicId)
            
            val result = cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap())
            
            log.info("Rollback en Cloudinary ejecutado. Resultado: {}", result["result"])
        } catch (e: Exception) {
            log.error("Fallo al intentar hacer rollback de la imagen en Cloudinary: ${e.message}", e)
        }
    }

    private fun convertMultiPartToFile(file: MultipartFile): File {
        val convFile = File(System.getProperty("java.io.tmpdir") + "/" + (file.originalFilename ?: "temp_img"))
        FileOutputStream(convFile).use { fos ->
            fos.write(file.bytes)
        }
        return convFile
    }
}