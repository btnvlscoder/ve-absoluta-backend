package com.veabsoluta.ve_absoluta_backend.service.storage

import org.springframework.web.multipart.MultipartFile

/**
 * Interfaz para servicios de almacenamiento de archivos.
 *
 * Permite abstraer el almacenamiento subyacente (Cloudinary, S3, etc.)
 * facilitando cambios de proveedor sin afectar la lógica de negocio.
 */
interface StorageService {

    /**
     * Sube un archivo y retorna su URL pública segura (HTTPS).
     *
     * @param file Archivo multipart del request
     * @return URL pública del archivo almacenado
     * @throws StorageException si falla la subida
     */
    fun upload(file: MultipartFile): String

    fun delete(fileUrl: String)
}

/**
 * Excepción genérica para errores de almacenamiento
 */
class StorageException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)