data class AnalisisCompletoDTO(
    val veredicto_final: String,
    val confianza_global: Double,
    val heatmap_base64: String,
    val desglose_pericial: DesglosePericialDTO,
    val metadata: Map<String, String>
)

data class DesglosePericialDTO(
    val analisis_ia_vit: DetalleAnalisisDTO?,
    val analisis_ela: DetalleAnalisisDTO?
)

data class DetalleAnalisisDTO(
    val estado: String,
    val detalle: String,
    val metricas: Map<String, Any>? = null
)