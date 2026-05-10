from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import requests
from io import BytesIO
from PIL import Image

# Importamos los servicios ahora que existen
from services.forensic_service import realizar_analisis_ela
from services.vit_service import analizar_con_vit

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
    # Descarga única
    imagen = _descargar_imagen(peticion.url)
    
    # Ejecutamos ambos análisis
    res_vit = analizar_con_vit(imagen)
    res_ela = realizar_analisis_ela(imagen)

    if "error" in res_vit:
        raise HTTPException(status_code=500, detail=res_vit["error"])

    # Armamos el Súper JSON para el Panel Forense
    return {
        "veredicto_final": res_vit["prediccion"],
        "confianza_global": res_vit["confianza"],
        "heatmap_base64": res_vit["heatmap"],
        "desglose_pericial": {
            "analisis_ia_vit": {
                "estado": "COMPLETO",
                "detalle": "Análisis de patrones de atención profundo realizado."
            },
            "analisis_ela": res_ela
        },
        "metadata": {
            "sistema": "VE ABSOLUTA Enterprise",
            "version": "2.1.0-clean"
        }
    }