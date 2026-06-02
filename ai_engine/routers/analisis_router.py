from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import requests
from io import BytesIO
from PIL import Image
import cv2
import numpy as np
import base64

from services.forensic_service import realizar_analisis_ela, extraer_huella_sensor
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

def generar_capas_forenses(b64_string: str, original_img_cv: np.ndarray):
    print("[DEBUG] Iniciando generación de capas forenses...")
    try:
        if "," in b64_string:
            b64_data = b64_string.split(",")[1]
        else:
            b64_data = b64_string
            
        b64_data = b64_data + "=" * ((4 - len(b64_data) % 4) % 4)
            
        img_data = base64.b64decode(b64_data)
        np_arr = np.frombuffer(img_data, np.uint8)
        heatmap_cv2 = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

        if heatmap_cv2 is None:
            b64_base = b64_string if "," in b64_string else "data:image/jpeg;base64," + b64_string
            return b64_base, None, None

        # --- 3. UMBRAL ---
        gray = cv2.cvtColor(heatmap_cv2, cv2.COLOR_BGR2GRAY)
        _, thresh = cv2.threshold(gray, 160, 255, cv2.THRESH_BINARY)
        capa_color_jet = cv2.applyColorMap(thresh, cv2.COLORMAP_JET)
        
        # FUSIÓN: Usamos la foto real de fondo
        umbral_final = capa_color_jet.copy()
        umbral_final[thresh == 0] = original_img_cv[thresh == 0] 
        
        _, buffer_thresh = cv2.imencode('.jpg', umbral_final)
        b64_thresh = "data:image/jpeg;base64," + base64.b64encode(buffer_thresh).decode('utf-8')

        # --- 4. ROLLOUT ---
        blur = cv2.GaussianBlur(gray, (15, 15), 0)
        edges = cv2.Canny(blur, 50, 150)
        capa_color_viridis = cv2.applyColorMap(edges, cv2.COLORMAP_VIRIDIS)
        
        # FUSIÓN: Oscurecemos la foto real de fondo
        fondo_oscurecido = cv2.addWeighted(original_img_cv, 0.4, np.zeros_like(original_img_cv), 0.6, 0)
        rollout_final = capa_color_viridis.copy()
        rollout_final[edges == 0] = fondo_oscurecido[edges == 0]

        _, buffer_rollout = cv2.imencode('.jpg', rollout_final)
        b64_rollout = "data:image/jpeg;base64," + base64.b64encode(buffer_rollout).decode('utf-8')

        b64_base = b64_string if "," in b64_string else "data:image/jpeg;base64," + b64_string
        return b64_base, b64_thresh, b64_rollout

    except Exception as e:
        print(f"[ERROR CRÍTICO] Fallo al generar las capas: {e}")
        b64_base = b64_string if "," in b64_string else "data:image/jpeg;base64," + b64_string
        return b64_base, None, None

# MATEMÁTICA FORENSE: CÁLCULO DE ANOMALÍAS
def calcular_metricas_heuristicas(imagen_pil):
    img_np = np.array(imagen_pil)
    if img_np.shape[-1] == 4:
        img_np = cv2.cvtColor(img_np, cv2.COLOR_RGBA2RGB)
        
    gray = cv2.cvtColor(img_np, cv2.COLOR_RGB2GRAY)
    
    # 1. ENTROPÍA (Normal en fotografía ~ 7.2 - 7.6)
    hist = cv2.calcHist([gray], [0], None, [256], [0, 256]).ravel()
    hist_prob = hist / (hist.sum() + 1e-7)
    non_zero_prob = hist_prob[hist_prob > 0]
    entropia = -np.sum(non_zero_prob * np.log2(non_zero_prob))
    
    # Anomalía: Medimos la distancia desde una entropía fotográfica natural (7.4)
    dist_entropia = abs(entropia - 7.4)
    anomalia_entropia = min(dist_entropia / 1.5, 1.0)
    
    # 2. CORRELACIÓN (Normal ~ 0.90 - 0.98)
    pixeles_izq = gray[:, :-1].flatten()
    pixeles_der = gray[:, 1:].flatten()
    
    if len(pixeles_izq) > 0 and len(pixeles_der) > 0:
        correlacion = np.corrcoef(pixeles_izq, pixeles_der)[0, 1]
    else:
        correlacion = 0.95
        
    if np.isnan(correlacion):
        correlacion = 0.95
        
    # Anomalía: Desviaciones extremas respecto a correlación natural (0.95)
    dist_corr = abs(correlacion - 0.95)
    anomalia_correlacion = min(dist_corr * 12.0, 1.0) 
    
    # 3. DISTRIBUCIÓN COLOR (Normal std dev ~ 40 - 70)
    std_r = np.std(img_np[:,:,0])
    std_g = np.std(img_np[:,:,1])
    std_b = np.std(img_np[:,:,2])
    promedio_std = (std_r + std_g + std_b) / 3.0
    
    # Anomalía: Distancia respecto a contraste natural (55)
    dist_color = abs(promedio_std - 55.0)
    anomalia_color = min(dist_color / 50.0, 1.0)
    
    return {
        "anomalia_entropia": float(anomalia_entropia),
        "anomalia_correlacion": float(anomalia_correlacion),
        "anomalia_color": float(anomalia_color)
    }

@router.post("/analizar-completo")
async def analisis_pericial_completo(peticion: PeticionImagen):
    imagen = _descargar_imagen(peticion.url)
    original_cv = cv2.cvtColor(np.array(imagen), cv2.COLOR_RGB2BGR)
    
    res_vit = analizar_con_vit(imagen)
    res_ela = realizar_analisis_ela(imagen)

    if "error" in res_vit:
        raise HTTPException(status_code=500, detail=res_vit["error"])
    if "error" in res_ela:
        raise HTTPException(status_code=500, detail=res_ela["error"])

    veredicto = res_vit["prediccion"]
    confianza = res_vit["confianza"]
    matriz_atencion = res_vit["grid_attn"]
    sector_ia = res_vit.get("sector", "indeterminado") 
    
    dif_max = res_ela["diferencia_maxima"]
    ruido_prom = res_ela["ruido_promedio"]
    varianza_sensor = extraer_huella_sensor(imagen)

    # --- CALIBRACIÓN DE CONFIANZA ---
    if veredicto == "REAL" and varianza_sensor > 60.0:
        duda = 100.0 - confianza
        confianza = round(100.0 - (duda * 0.4), 2)
    elif veredicto == "REAL" and varianza_sensor < 40.0: 
        confianza_calibrada = confianza - (confianza * 0.2)
        if confianza_calibrada < 50.0:
            veredicto = "FAKE"
            confianza = round(100.0 - confianza_calibrada, 2)
        else:
            confianza = round(confianza_calibrada, 2)

# --- MÉTRICAS PARA RADAR CHART (Transformadas a Anomalías 0-1) ---
    
# 1. Patrón de Ruido (Ruido ELA): El ruido promedio natural es ~2.0. 
    # Saturamos al llegar a 15.0 (nivel de manipulación claro).
    val_patron_ruido = min(max((ruido_prom - 1.0) / 14.0, 0.0), 1.0)
    
    # 2. Frecuencia Fourier (Varianza Laplaciana): 
    # Una cámara real tiene varianza > 60. Si baja de 40, detectamos anomalía.
    # Escala: 0 (Sano) a 1 (Totalmente plano/artificial)
    if varianza_sensor > 60:
        val_fourier = 0.0 # Valor sano
    else:
        val_fourier = min(max((60 - varianza_sensor) / 60.0, 0.0), 1.0)
    
    # 3. Artefactos Compresión (Delta Max ELA): 
    # Un delta de 30 es aceptable. A partir de 80 es manipulación.
    val_compresion = min(max((dif_max - 30) / 50.0, 0.0), 1.0)
        
    # 4, 5 y 6. Métricas Heurísticas (Entropía, Correlación, Color)
    metricas_reales = calcular_metricas_heuristicas(imagen)
    val_entropia = round(metricas_reales["anomalia_entropia"], 2)
    val_correlacion = round(metricas_reales["anomalia_correlacion"], 2)
    val_color = round(metricas_reales["anomalia_color"], 2)

    b64_base, b64_thresh, b64_rollout = generar_capas_forenses(res_vit["heatmap"], original_cv)

    return {
        "veredicto_final": veredicto,
        "confianza_global": confianza,
        "heatmap_base64": b64_base,
        "heatmap_threshold": b64_thresh,
        "heatmap_rollout": b64_rollout,
        # BLOQUE CRUDO QUE LEE REACT AHORA
        "datos_crudos_frontend": {
            "vit_prediccion": veredicto,
            "vit_confianza": confianza,
            "vit_sector": sector_ia,
            "ela_max_diff": dif_max,
            "ela_ruido_prom": ruido_prom,
            "sensor_variance": varianza_sensor
        },
        "metadata": {
            "sistema": "VE ABSOLUTA Enterprise",
            "version": "2.3.1-SRM",
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