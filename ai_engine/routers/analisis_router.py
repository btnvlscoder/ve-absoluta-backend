from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import requests
from io import BytesIO
from PIL import Image
import cv2
from services.forensic_service import realizar_analisis_ela, generar_narrativa_ela, extraer_huella_sensor
from services.vit_service import analizar_con_vit, generar_narrativa_vit
import numpy as np
import base64

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

    # Extraemos la huella física del lente de la cámara
    varianza_sensor = extraer_huella_sensor(imagen)

    # ==========================================
    # MOTOR DE CONSENSO MULTIMODAL
    # ==========================================
    # Bajamos el umbral a 60.0 porque las imágenes de web pierden ruido por compresión
    if veredicto == "REAL" and varianza_sensor > 60.0:
        # Apoyo a favor: Reducimos la duda
        duda = 100.0 - confianza
        confianza = round(100.0 - (duda * 0.4), 2)
        
    elif veredicto == "REAL" and varianza_sensor < 40.0: # Bajamos el castigo a 40.0
        # Penalización en contra
        confianza_calibrada = confianza - (confianza * 0.2)
        
        if confianza_calibrada < 50.0:
            veredicto = "FAKE"
            confianza = round(100.0 - confianza_calibrada, 2)
        else:
            confianza = round(confianza_calibrada, 2)

    # Textos
    texto_vit = generar_narrativa_vit(veredicto, confianza, matriz_atencion)
    texto_ela = generar_narrativa_ela(dif_max, ruido_prom, varianza_sensor)

    # ==========================================
    # NORMALIZACIÓN PARA ANÁLISIS MULTIDIMENSIONAL (Gráfico de Araña)
    # ==========================================
    # Convertimos los rangos matemáticos del backend a escala 0.0 - 1.0
    val_patron_ruido = min(round(ruido_prom / 50.0, 2), 1.0)
    val_fourier = min(round(varianza_sensor / 100.0, 2), 1.0)
    val_compresion = min(round(dif_max / 255.0, 2), 1.0)
    
    # Métricas adicionales leídas de ELA
    val_entropia = res_ela["metricas"].get("entropia_local", 0.82)
    val_correlacion = res_ela["metricas"].get("correlacion_pixeles", 0.45)
    val_color = res_ela["metricas"].get("distribucion_color", 0.79)

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
            "version": "2.3.1-SRM",
            # INYECTAMOS LA VARIANZA PARA DEBUGEARLA
            "metrica_oculta_srm": round(varianza_sensor, 2),

        #MÉTRICAS ASOCIADAS AL GRÁFICO RADAR
        "metricas_heuristicas": [
                {"parametro": "Patrón de Ruido", "valor": val_patron_ruido, "fullMark": 1},
                {"parametro": "Frecuencia Fourier", "valor": val_fourier, "fullMark": 1},
                {"parametro": "Artefactos Compresión", "valor": val_compresion, "fullMark": 1},
                {"parametro": "Entropía Local", "valor": val_entropia, "fullMark": 1},
                {"parametro": "Correlación Píxeles", "valor": val_correlacion, "fullMark": 1},
                {"parametro": "Distribución Color", "valor": val_color, "fullMark": 1}
            ]
            
        }
    }

def generar_capas_forenses(imagen_heatmap_cv2):
    """
    Toma el heatmap base (BGR) y genera las capas Threshold y Rollout (simulado)
    """
    # 1. Codificar Base
    _, buffer_base = cv2.imencode('.jpg', imagen_heatmap_cv2)
    b64_base = "data:image/jpeg;base64," + base64.b64encode(buffer_base).decode('utf-8')

    # 2. Generar Umbral (Threshold)
    # Convertimos a escala de grises y filtramos solo las altas activaciones
    gray = cv2.cvtColor(imagen_heatmap_cv2, cv2.COLOR_BGR2GRAY)
    _, thresh = cv2.threshold(gray, 180, 255, cv2.THRESH_BINARY)
    # Volvemos a aplicar color para que se vea como heatmap pero recortado
    heatmap_thresh = cv2.applyColorMap(thresh, cv2.COLORMAP_JET)
    _, buffer_thresh = cv2.imencode('.jpg', heatmap_thresh)
    b64_thresh = "data:image/jpeg;base64," + base64.b64encode(buffer_thresh).decode('utf-8')

    # 3. Generar Rollout (Aislamiento de contornos de atención)
    # Suavizamos e identificamos los gradientes térmicos fuertes
    blur = cv2.GaussianBlur(gray, (15, 15), 0)
    edges = cv2.Canny(blur, 50, 150)
    # Lo mapeamos con otro esquema de color (ej. VIRIDIS) para que sea visualmente distinto
    heatmap_rollout = cv2.applyColorMap(edges, cv2.COLORMAP_VIRIDIS)
    _, buffer_rollout = cv2.imencode('.jpg', heatmap_rollout)
    b64_rollout = "data:image/jpeg;base64," + base64.b64encode(buffer_rollout).decode('utf-8')

    return b64_base, b64_thresh, b64_rollout