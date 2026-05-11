from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import requests
from io import BytesIO
from PIL import Image

from services.forensic_service import realizar_analisis_ela, generar_narrativa_ela, extraer_huella_sensor
from services.vit_service import analizar_con_vit, generar_narrativa_vit

router = APIRouter()

class PeticionImagen(BaseModel):
    url: str

def _descargar_imagen(url: str) -> Image.Image:
    try:
        respuesta = requests.get(url, timeout=10)
        respuesta.raise_for_status()
        return Image.open(BytesIO(respuesta.content)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Error al obtener imagen: {e}")

@router.post("/analizar-completo")
async def analisis_pericial_completo(peticion: PeticionImagen):
    imagen = _descargar_imagen(peticion.url)
    
    res_vit = analizar_con_vit(imagen)
    res_ela = realizar_analisis_ela(imagen)

    if "error" in res_vit:
        raise HTTPException(status_code=500, detail=res_vit["error"])
    if "error" in res_ela:
        raise HTTPException(status_code=500, detail=res_ela["detalle"])

    veredicto = res_vit["prediccion"]
    confianza = res_vit["confianza"]
    matriz_atencion = res_vit["grid_attn"]
    
    dif_max = res_ela["metricas"]["diferencia_maxima"]
    ruido_prom = res_ela["metricas"]["ruido_promedio"]

    # 🚀 NUEVO: Extraemos la huella física del lente de la cámara
    varianza_sensor = extraer_huella_sensor(imagen)

    # Le pasamos la varianza a la narrativa para que decida inteligentemente
    texto_vit = generar_narrativa_vit(veredicto, confianza, matriz_atencion)
    texto_ela = generar_narrativa_ela(dif_max, ruido_prom, varianza_sensor) # <--- OJO AQUÍ

    return {
        "veredicto_final": veredicto,
        "confianza_global": confianza,
        "heatmap_base64": res_vit["heatmap"],
        "desglose_pericial": {
            "analisis_ia_vit": {
                "estado": texto_vit["estado"],
                "detalle": texto_vit["detalle"]
            },
            "analisis_ela": {
                "estado": texto_ela["estado"],
                "detalle": texto_ela["detalle"]
            }
        },
        "metadata": {
            "sistema": "VE ABSOLUTA Enterprise",
            "version": "2.3.0-SRM" # Actualizamos versión
        }
    }