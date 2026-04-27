package com.veabsoluta.ve_absoluta_backend.dto

data class GradioResponse(
    val data: List<GradioOutput>
)

data class GradioOutput(
    val prediccion: String? = null,
    val label: String? = null,
    val confianza: Double? = null,
    val score: Double? = null
) {
    // Getter inteligente para la predicción
    val valorPrediccion: String
        get() = prediccion ?: label ?: "Desconocido"

    // Getter inteligente para la confianza
    val valorConfianza: Double
        get() = confianza ?: score ?: 0.0
}