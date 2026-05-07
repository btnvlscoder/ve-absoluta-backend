from fastapi import FastAPI, HTTPException, File, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import requests
from io import BytesIO
from PIL import Image
import numpy as np
import torch
from transformers import AutoImageProcessor, AutoModelForImageClassification
# Importamos el script de contingencia de Miguel
from advanced_features import analyze_image_advanced

# =====================================================================
# INICIALIZACIÓN DE LA API Y CARGA DEL MODELO
# =====================================================================
app = FastAPI(
    title="VE ABSOLUTA - AI Engine Integrado",
    description="API Híbrida: Inferencia (ViT) + Análisis Estadístico Avanzado",
    version="2.0"
)

# El middleware de Miguel para evitar bloqueos de navegador
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# IMPORTANTE: Apuntamos a tu REPOSITORIO DE MODELOS en la nube
MODEL_DIR = "btnvlscoder/ve-absoluta-vit-v2" 

procesador = None
modelo = None
error_inicializacion = None

print(f"Descargando motor de IA desde: {MODEL_DIR}...")
try:
    procesador = AutoImageProcessor.from_pretrained(MODEL_DIR)
    modelo = AutoModelForImageClassification.from_pretrained(MODEL_DIR)
    modelo.eval() 
    print("✅ Vision Transformer cargado y listo para la acción.")
except Exception as e:
    # Tu escudo de errores maestro
    error_inicializacion = str(e)
    print(f"❌ Error CRÍTICO al cargar el modelo ViT: {e}")

# =====================================================================
# ESQUEMA DE DATOS Y FUNCIONES AUXILIARES (Aporte de Miguel)
# =====================================================================
class PeticionImagen(BaseModel):
    url: str

def _descargar_imagen_rgb(url: str) -> Image.Image:
    """Descarga y convierte la imagen de Cloudinary a RAM de forma segura."""
    respuesta_http = requests.get(url, timeout=10)
    respuesta_http.raise_for_status()
    return Image.open(BytesIO(respuesta_http.content)).convert("RGB")

def _analisis_avanzado_desde_imagen(imagen_rgb: Image.Image) -> dict:
    """Pasa la imagen al motor estadístico de Miguel."""
    imagen_bgr = np.array(imagen_rgb)[:, :, ::-1].copy()
    analisis = analyze_image_advanced(imagen_bgr)
    return {
        "status": "success",
        "analysis_type": "advanced_statistical",
        **analisis,
        "metadata": {
            "engine": "advanced_features_v1",
            "note": "Score heuristico basado en metricas estadisticas",
        },
    }

# =====================================================================
# ENDPOINTS
# =====================================================================
@app.get("/")
def health_check():
    """Ruta raíz combinada para monitorear ambos motores."""
    if error_inicializacion:
        return {"status": "degraded", "motor_vit": "Error", "detalle": error_inicializacion, "motor_estadistico": "Online"}
    return {"status": "online", "motor_vit": "ViT V2 - Cloud", "motor_estadistico": "Online"}

# --- RUTA 1: DEEP LEARNING (Tu ruta original) ---
@app.post("/api/v1/detect")
async def analizar_imagen_vit(peticion: PeticionImagen):
    if error_inicializacion:
        raise HTTPException(status_code=500, detail=f"Fallo crítico al arrancar la IA: {error_inicializacion}")
        
    try:
        # Usamos la funcion limpia de Miguel
        imagen = _descargar_imagen_rgb(peticion.url)
        inputs = procesador(images=imagen, return_tensors="pt")
        
        # Tu inferencia rápida y sin gradientes
        with torch.no_grad():
            outputs = modelo(**inputs)
            
        logits = outputs.logits
        probabilidades = torch.nn.functional.softmax(logits, dim=-1)
        clase_predicha_idx = logits.argmax(-1).item()
        
        clase_predicha = modelo.config.id2label[clase_predicha_idx]
        confianza = probabilidades[0][clase_predicha_idx].item()
        
        return {
            "status": "success",
            "prediccion": clase_predicha.upper(), 
            "confianza": round(confianza * 100, 2),
            "metadata": {
                "modelo_usado": "VE_ABSOLUTA_ViT_V2",
                "infraestructura": "Hugging Face Spaces (Docker)"
            }
        }
    except requests.exceptions.RequestException as e:
        raise HTTPException(status_code=400, detail=f"Error al descargar imagen: {str(e)}")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error en el motor de IA: {str(e)}")

# --- RUTAS 2 Y 3: ANÁLISIS ESTADÍSTICO (Las rutas de Miguel) ---
@app.post("/api/v1/analyze-advanced")
async def analizar_imagen_avanzado(peticion: PeticionImagen):
    try:
        imagen = _descargar_imagen_rgb(peticion.url)
        return _analisis_avanzado_desde_imagen(imagen)
    except requests.exceptions.RequestException as e:
        raise HTTPException(status_code=400, detail=f"Error al descargar imagen: {str(e)}")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error en analisis avanzado: {str(e)}")

@app.post("/api/v1/analyze-advanced-file")
async def analizar_imagen_avanzado_archivo(file: UploadFile = File(...)):
    try:
        if not file.content_type or not file.content_type.startswith("image/"):
            raise HTTPException(status_code=400, detail="El archivo debe ser una imagen valida.")
        content = await file.read()
        if not content:
            raise HTTPException(status_code=400, detail="El archivo esta vacio.")
        imagen = Image.open(BytesIO(content)).convert("RGB")
        return _analisis_avanzado_desde_imagen(imagen)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error procesando archivo: {str(e)}")