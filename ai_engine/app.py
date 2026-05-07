from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import requests
from io import BytesIO
from PIL import Image
import torch
from transformers import AutoImageProcessor, AutoModelForImageClassification

# =====================================================================
# INICIALIZACIÓN DE LA API Y CARGA DEL MODELO
# =====================================================================
app = FastAPI(
    title="VE ABSOLUTA - AI Engine",
    description="API de Inferencia con Vision Transformer",
    version="2.0"
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
    print("...Vision Transformer cargado y listo para la acción.")
except Exception as e:
    error_inicializacion = str(e)
    print(f"❌ Error CRÍTICO al cargar el modelo: {e}")

# =====================================================================
# ESQUEMA DE DATOS
# =====================================================================
class PeticionImagen(BaseModel):
    url: str

# =====================================================================
# ENDPOINTS
# =====================================================================
@app.get("/")
def health_check():
    if error_inicializacion:
        return {"status": "error", "detalle": f"El modelo no cargó: {error_inicializacion}"}
    return {"status": "online", "motor": "ViT V2 - Cloud", "ready": True}

@app.post("/api/v1/detect")
async def analizar_imagen(peticion: PeticionImagen):
    # Si el modelo falló al inicio, le avisamos a Kotlin inmediatamente
    if error_inicializacion:
        raise HTTPException(status_code=500, detail=f"Fallo crítico al arrancar la IA: {error_inicializacion}")
        
    try:
        # 1. Descargamos la imagen directo a RAM
        respuesta_http = requests.get(peticion.url, timeout=10)
        respuesta_http.raise_for_status() 
        
        # 2. Procesamiento de imagen
        imagen = Image.open(BytesIO(respuesta_http.content)).convert("RGB")
        inputs = procesador(images=imagen, return_tensors="pt")
        
        # 3. Inferencia (Cálculo en la CPU de Hugging Face)
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