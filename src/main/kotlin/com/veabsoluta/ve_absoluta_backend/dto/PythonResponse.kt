package com.veabsoluta.ve_absoluta_backend.dto

/**
 * DTO para mapear la salida del script local de Python (ai_engine).
 */
data class PythonResponse(
    val prediction: String,
    val confidence: Double,
    val status: String? = null
)