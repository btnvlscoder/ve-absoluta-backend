package com.veabsoluta.ve_absoluta_backend.model 

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Entidad que representa un análisis de deepfake en la base de datos.
 * 
 * Mapeo ORM:
 * - Almacena la URL del archivo en MinIO (no el archivo mismo)
 * - Persiste metadatos del análisis para auditoría
 */
@Entity
@Table(name = "historial_analisis")
data class Analisis(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    val nombreArchivo: String,
    
    /** URL del archivo en MinIO/S3 - reemplaza ruta local */
    val rutaArchivo: String,
    
    val prediccion: String,
    val confianza: Double,
    
    // Se genera la fecha automáticamente al crear el registro
    val fecha: LocalDateTime = LocalDateTime.now()
)