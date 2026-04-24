package com.veabsoluta.ve_absoluta_backend.service

import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * Servicio para gestionar almacenamiento de archivos multimedia en Cloudinary.
 * * Ventajas implementadas:
 * - Retorna instantáneamente una URL pública (secure_url)
 * - Delega la gestión del almacenamiento y ancho de banda a la nube
 */
@Service
class CloudinaryService {

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
     * * @param file Archivo multipart del request
     * @return URL pública del archivo almacenado
     */
    fun subirArchivo(file: MultipartFile): String {
        try {
            // Sube el archivo y recibe un mapa con toda la metadata
            val uploadResult = cloudinary.uploader().upload(
                file.bytes, 
                ObjectUtils.emptyMap() // Aquí podríamos añadir carpetas o transformaciones futuras
            )

            // Retornamos específicamente la URL segura (HTTPS)
            return uploadResult["secure_url"].toString()
            
        } catch (e: Exception) {
            throw RuntimeException("Error crítico al subir imagen a Cloudinary: ${e.message}")
        }
    }
}