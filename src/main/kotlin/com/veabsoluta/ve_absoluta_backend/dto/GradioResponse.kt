package com.veabsoluta.ve_absoluta_backend.dto

data class GradioResponse(
    val data: List<GradioOutput>
)

data class GradioOutput(
    val label: String,
    val confianza: Double? = null,
    val score: Double? = null
) {
    val valorConfianza: Double
        get() = confianza ?: score ?: 0.0
}