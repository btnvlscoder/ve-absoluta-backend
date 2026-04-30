import os
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from transformers import pipeline
from PIL import Image
import requests
from io import BytesIO
import uvicorn

# 1. Inicializamos el servidor FastAPI
app = FastAPI(title="VE ABSOLUTA - Motor Forense Local")

# =====================================================================
# CARGA EN MEMORIA (SINGLETON)
# Esto se ejecuta UNA SOLA VEZ cuando arranca el servidor.
# =====================================================================
print("Cargando modelo neuronal en memoria RAM... (Esto tomará unos segundos)")
try:
    detector = pipeline("image-classification", model="umm-maybe/AI-image-detector")
    print("Modelo cargado exitosamente y listo para procesar en milisegundos!")
except Exception as e:
    print(f"Error al cargar el modelo: {e}")

# Definimos el formato JSON que esperamos recibir desde Spring Boot
class PeticionAnalisis(BaseModel):
    url_imagen: str
    umbral: float = 0.5

@app.post("/api/v1/analizar")
async def procesar_evidencia(peticion: PeticionAnalisis):
    try:
        # 1. Descargamos la imagen desde la URL de Cloudinary a la memoria temporal
        respuesta_http = requests.get(peticion.url_imagen)
        respuesta_http.raise_for_status()
        img = Image.open(BytesIO(respuesta_http.content)).convert('RGB')
        
        # 2. INFERENCIA INMEDIATA (El modelo ya está cargado, esto tomará milisegundos)
        resultados = detector(img)
        
        confianza_ia = 0.0
        confianza_real = 0.0
        
        for res in resultados:
            etiqueta = res['label'].lower()
            if etiqueta == 'artificial' or 'fake' in etiqueta:
                confianza_ia = res['score']
            else:
                confianza_real = res['score']
                
        # 3. Devolvemos el veredicto en formato JSON para que Kotlin lo lea
        if confianza_ia >= peticion.umbral:
            return {"prediction": "CONTENIDO_IA_DETECTED", "confidence": confianza_ia}
        else:
            return {"prediction": "IMAGEN_REAL", "confidence": confianza_real}
            
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    # Levantamos el servidor en el puerto 8000
    uvicorn.run(app, host="0.0.0.0", port=8000)