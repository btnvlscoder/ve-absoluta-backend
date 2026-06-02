package com.veabsoluta.ve_absoluta_backend.DTO

data class AnalisisRequest(
    val url: String,
    val umbral: Double = 0.65,
    val model_version: String = "VE_ABSOLUTA_ViT_V2"
)

data class PythonResponse(
    val veredicto_final: String?,
    val confianza_global: Double?,
    val heatmap_base64: String?,
    val heatmap_threshold: String?,
    val heatmap_rollout: String?,
    val desglose_pericial: DesglosePericialDTO?,
    val metadata: Map<String, Any>? = null,
    val datos_crudos_frontend: Map<String, Any>? = null
) {
    fun validate(): PythonResponse {
        require(!veredicto_final.isNullOrBlank()) { "Respuesta IA inválida: veredicto vacío" }
        require(confianza_global != null) { "Respuesta IA inválida: confianza nula" }
        return this
    }
}

data class AnalisisForenseResponse(
    val id: Any?,
    val nombreArchivo: String,
    val veredicto_final: String,
    val confianza_global: Double,
    val heatmap_base64: String?,
    val heatmap_threshold: String?,
    val heatmap_rollout: String?,
    val desglose_pericial: DesglosePericialDTO?,
    val metadata: Map<String, Any>? = null,
    val datos_crudos_frontend: Map<String, Any>? = null
)

data class DesglosePericialDTO(
    val analisis_ia_vit: DetalleAnalisisDTO?,
    val analisis_ela: DetalleAnalisisDTO?
)

data class DetalleAnalisisDTO(
    val estado: String?,
    val detalle: String?,
    val metricas: Map<String, Any>? = null
)