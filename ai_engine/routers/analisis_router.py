from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import requests
from io import BytesIO
from PIL import Image
import cv2
import numpy as np
import base64

from services.forensic_service import realizar_analisis_ela, extraer_huella_sensor
from services.vit_service import analizar_con_vit, detectar_sector_anomalia

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

# CORRECCIÓN APLICADA: Ahora recibe original_img_cv para la fusión visual
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

# MATEMÁTICA DE TESIS
def calcular_metricas_heuristicas(imagen_pil):
    img_np = np.array(imagen_pil)
    if img_np.shape[-1] == 4:
        img_np = cv2.cvtColor(img_np, cv2.COLOR_RGBA2RGB)
        
    gray = cv2.cvtColor(img_np, cv2.COLOR_RGB2GRAY)
    
    # 1. ENTROPÍA
    hist = cv2.calcHist([gray], [0], None, [256], [0, 256]).ravel()
    hist_prob = hist / hist.sum()
    non_zero_prob = hist_prob[hist_prob > 0]
    entropia = -np.sum(non_zero_prob * np.log2(non_zero_prob))
    entropia_norm = min(entropia / 8.0, 1.0)
    
    # 2. CORRELACIÓN
    pixeles_izq = gray[:, :-1].flatten()
    pixeles_der = gray[:, 1:].flatten()
    correlacion = np.corrcoef(pixeles_izq, pixeles_der)[0, 1]
    correlacion_norm = max(0.0, min(correlacion, 1.0))
    
    # 3. COLOR
    std_r = np.std(img_np[:,:,0])
    std_g = np.std(img_np[:,:,1])
    std_b = np.std(img_np[:,:,2])
    promedio_std = (std_r + std_g + std_b) / 3.0
    color_norm = min(promedio_std / 75.0, 1.0)
    
    return {
        "entropia_local": float(entropia_norm),
        "correlacion_pixeles": float(correlacion_norm),
        "distribucion_color": float(color_norm)
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

    # Métricas para radar chart
    val_patron_ruido = min(round(ruido_prom / 50.0, 2), 1.0)
    val_fourier = min(round(varianza_sensor / 100.0, 2), 1.0)
    val_compresion = min(round(dif_max / 255.0, 2), 1.0)
    
    metricas_reales = calcular_metricas_heuristicas(imagen)
    val_entropia = round(metricas_reales["entropia_local"], 2)
    val_correlacion = round(metricas_reales["correlacion_pixeles"], 2)
    val_color = round(metricas_reales["distribucion_color"], 2)

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