import os
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from transformers import pipeline
from PIL import Image
import requests
from io import BytesIO
import uvicorn

app = FastAPI(title="VE ABSOLUTA - Motor Forense (ViT)")

# ==========================================
# 1. MLOps: VARIABLES DE ENTORNO DINÁMICAS
# ==========================================
MODEL_VERSION = os.getenv("VE_MODEL_VERSION", "umm-maybe/AI-image-detector")
DEFAULT_THRESHOLD = float(os.getenv("VE_THRESHOLD", "0.5"))

print(f"Iniciando motor VE ABSOLUTA con modelo Transformer: [{MODEL_VERSION}]")

# =======================================================
# 2. CARGA DEL MODELO VISION TRANSFORMER (ViT)
# =======================================================
try:
    # El pipeline de Hugging Face abstrae toda la complejidad matemática
    detector_hf = pipeline("image-classification", model=MODEL_VERSION) 
    print("✅ Pipeline de Hugging Face cargado y listo en memoria.")
except Exception as e:
    print(f"❌ Error crítico al cargar el modelo: {e}")

# ==========================================
# 3. ENDPOINT Y REGLAS DE NEGOCIO
# ==========================================
class PeticionAnalisis(BaseModel):
    url_imagen: str
    umbral: float = DEFAULT_THRESHOLD

@app.post("/api/v1/analizar")
async def procesar_evidencia(peticion: PeticionAnalisis):
    try:
        # Descarga la imagen directamente a la RAM (Sin tocar el disco duro)
        respuesta_http = requests.get(peticion.url_imagen)
        respuesta_http.raise_for_status()
        img = Image.open(BytesIO(respuesta_http.content)).convert('RGB')

        confianza_ia = 0.0
        confianza_real = 0.0

        # Inferencia directa con el Vision Transformer
        resultados = detector_hf(img)
        
        for res in resultados:
            etiqueta = res['label'].lower()
            if etiqueta == 'artificial' or 'fake' in etiqueta:
                confianza_ia = res['score']
            else:
                confianza_real = res['score']

        # Evaluación de la Regla de Negocio
        if confianza_ia >= peticion.umbral:
            return {"prediction": "CONTENIDO_IA_DETECTED", "confidence": confianza_ia}
        else:
            return {"prediction": "IMAGEN_REAL", "confidence": confianza_real}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)