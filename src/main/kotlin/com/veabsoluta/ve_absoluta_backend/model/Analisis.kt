package com.veabsoluta.ve_absoluta_backend.model 
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "historial_analisis")
data class Analisis(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    val nombreArchivo: String,
    val prediccion: String,
    val confianza: Double,
    
    // Se genera la fecha automáticamente al crear el registro
    val fecha: LocalDateTime = LocalDateTime.now()
)