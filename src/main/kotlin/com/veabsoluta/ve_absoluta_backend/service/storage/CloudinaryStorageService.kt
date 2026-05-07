package com.veabsoluta.ve_absoluta_backend.service.storage

import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.FileOutputStream
import java.util.*

@Service
class CloudinaryStorageService(
    private val cloudinary: Cloudinary
) : StorageService {

    private val log = LoggerFactory.getLogger(CloudinaryStorageService::class.java)

    override fun upload(file: MultipartFile): String {
        var tempFile: File? = null
        try {
            // 1. Convertir MultipartFile a un File temporal físico
            tempFile = convertMultiPartToFile(file)

            // 2. Parámetros de Cloudinary (carpeta y nombre único)
            val uploadParams = ObjectUtils.asMap(
                "folder", "ve-absoluta-uploads",
                "public_id", "img_${UUID.randomUUID()}",
                "resource_type", "auto" // Acepta cualquier tipo de imagen
            )

            // 3. Subir a Cloudinary
            log.debug("Iniciando subida a Cloudinary del archivo temporal...")
            val uploadResult = cloudinary.uploader().upload(tempFile, uploadParams)

            // 4. Extraer la URL segura (HTTPS)
            val secureUrl = uploadResult["secure_url"] as String
            log.info("Subida a Cloudinary exitosa. URL: {}", secureUrl)
            
            return secureUrl
            
        } catch (e: Exception) {
            log.error("Error crítico al subir a Cloudinary: ${e.message}", e)
            throw StorageException("Error al subir imagen a Cloudinary: ${e.message}", e)
        } finally {
            // 5. Limpieza: Borrar el archivo temporal SIEMPRE (incluso si falla)
            tempFile?.let {
                if (it.exists()) {
                    it.delete()
                    log.debug("Archivo temporal borrado exitosamente")
                }
            }
        }
    }

    // Función auxiliar para convertir el archivo
    private fun convertMultiPartToFile(file: MultipartFile): File {
        val convFile = File(System.getProperty("java.io.tmpdir") + "/" + (file.originalFilename ?: "temp_img"))
        FileOutputStream(convFile).use { fos ->
            fos.write(file.bytes)
        }
        return convFile
    }
}