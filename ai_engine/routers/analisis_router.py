from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import requests
from io import BytesIO
from PIL import Image

# Importamos nuestros servicios
from services.forensic_service import realizar_analisis_ela
# from services.vit_service import analizar_con_vit (Este lo armaremos luego)

router = APIRouter()

class PeticionImagen(BaseModel):
    url: str

def _descargar_imagen(url: str) -> Image.Image:
    respuesta = requests.get(url, timeout=10)
    respuesta.raise_for_status()
    return Image.open(BytesIO(respuesta.content)).convert("RGB")

@router.post("/analizar-completo")
async def analisis_pericial_completo(peticion: PeticionImagen):
    try:
        # Descargamos la imagen una sola vez en RAM
        imagen = _descargar_imagen(peticion.url)
        
        # 1. Análisis Forense (ELA)
        resultado_ela = realizar_analisis_ela(imagen)
        
        # 2. Análisis ViT y Mapa de Calor (Simulado por ahora hasta que movamos tu código a vit_service)
        # resultado_vit = analizar_con_vit(imagen)
        
        # 3. Construimos el Súper JSON para React
        return {
            "veredicto_final": "PENDIENTE_VIT",
            "confianza_global": 0.0,
            "heatmap_base64": "",
            "desglose_pericial": {
                "analisis_ela": resultado_ela
                # Aquí sumaremos FFT, Bordes y EXIF
            }
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error en orquestación: {str(e)}")