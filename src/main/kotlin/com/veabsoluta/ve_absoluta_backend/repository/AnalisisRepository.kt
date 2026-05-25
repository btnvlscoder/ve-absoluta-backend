package com.veabsoluta.ve_absoluta_backend.repository

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AnalisisRepository : JpaRepository<Analisis, Long> {
    fun countByPrediccion(prediccion: String): Long 
}