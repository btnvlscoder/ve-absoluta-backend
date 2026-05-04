import os
import torch
import torch.nn as nn
from torchvision import models, transforms
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from transformers import pipeline
from PIL import Image
import requests
from io import BytesIO
import uvicorn

app = FastAPI(title="VE ABSOLUTA - Motor Forense Local")

# ==========================================
# 1. MLOps: VARIABLES DE ENTORNO DINÁMICAS
# ==========================================
# PUNTO 1 CORREGIDO: Ahora usamos esta variable de verdad.
MODEL_VERSION = os.getenv("VE_MODEL_VERSION", "umm-maybe/AI-image-detector")

# PUNTO 2 CORREGIDO: Umbral por defecto dinámico desde el entorno operativo.
DEFAULT_THRESHOLD = float(os.getenv("VE_THRESHOLD", "0.5"))

print(f"Iniciando motor VE ABSOLUTA con versión: [{MODEL_VERSION}]")

# =======================================================
# 2. CARGA INTELIGENTE HF vs CUSTOM
# =======================================================
detector_hf = None
modelo_custom = None
transformaciones_custom = None
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

try:
    if MODEL_VERSION.endswith(".pth"):
        # MODO A: Inferencia con tu propio modelo entrenado (ResNet50)
        print("Detectado archivo .pth. Cargando red neuronal propia...")
        
        # Reconstruimos la arquitectura que usaste en train.py
        modelo_custom = models.resnet50(weights=None)
        num_ftrs = modelo_custom.fc.in_features
        modelo_custom.fc = nn.Linear(num_ftrs, 2) # 2 clases: Real vs Fake
        
        # Cargamos tus pesos entrenados
        modelo_custom.load_state_dict(torch.load(MODEL_VERSION, map_location=DEVICE, weights_only=True))
        modelo_custom.to(DEVICE)
        modelo_custom.eval() # Modo inferencia
        
        # Transformaciones estándar de PyTorch
        transformaciones_custom = transforms.Compose([
            transforms.Resize((224, 224)),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
        ])
        print("✅ Modelo propio (ResNet50) cargado y listo en memoria.")
        
    else:
        # MODO B: Inferencia con Hugging Face Pipeline
        print("Detectado formato de nube. Cargando pipeline de Hugging Face...")
        # AQUI USAMOS LA VARIABLE CORRECTAMENTE
        detector_hf = pipeline("image-classification", model=MODEL_VERSION) 
        print("✅ Pipeline de HF cargado y listo en memoria.")
        
except Exception as e:
    print(f"❌ Error crítico al cargar el modelo: {e}")


# ==========================================
# 3. ENDPOINT Y REGLAS DE NEGOCIO
# ==========================================
class PeticionAnalisis(BaseModel):
    url_imagen: str
    umbral: float = DEFAULT_THRESHOLD  # Usamos la variable de entorno

@app.post("/api/v1/analizar")
async def procesar_evidencia(peticion: PeticionAnalisis):
    try:
        # Descarga en memoria RAM
        respuesta_http = requests.get(peticion.url_imagen)
        respuesta_http.raise_for_status()
        img = Image.open(BytesIO(respuesta_http.content)).convert('RGB')

        confianza_ia = 0.0
        confianza_real = 0.0

        # Ejecutamos inferencia dependiendo de qué motor cargamos
        if modelo_custom:
            # Lógica para tu modelo propio .pth
            tensor_img = transformaciones_custom(img).unsqueeze(0).to(DEVICE)
            with torch.no_grad():
                outputs = modelo_custom(tensor_img)
                probabilidades = torch.nn.functional.softmax(outputs[0], dim=0)
                # Asumimos clase 0: Real, clase 1: Fake (basado en orden alfabético de carpetas)
                confianza_ia = probabilidades[1].item()
                confianza_real = probabilidades[0].item()
        else:
            # Lógica para Hugging Face
            resultados = detector_hf(img)
            for res in resultados:
                etiqueta = res['label'].lower()
                if etiqueta == 'artificial' or 'fake' in etiqueta:
                    confianza_ia = res['score']
                else:
                    confianza_real = res['score']

        # Retornamos evaluación
        if confianza_ia >= peticion.umbral:
            return {"prediction": "CONTENIDO_IA_DETECTED", "confidence": confianza_ia}
        else:
            return {"prediction": "IMAGEN_REAL", "confidence": confianza_real}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)