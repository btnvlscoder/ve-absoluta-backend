import sys
import json
from transformers import pipeline
from PIL import Image

def procesar_forense(ruta_imagen):
    try:
        # Cargamos el mismo modelo de la nube
        detector = pipeline("image-classification", model="umm-maybe/AI-image-detector")
        
        img = Image.open(ruta_imagen).convert('RGB')
        resultados = detector(img)
        
        confianza_ia = 0.0
        confianza_real = 0.0
        
        for res in resultados:
            etiqueta = res['label'].lower()
            if etiqueta == 'artificial' or 'fake' in etiqueta:
                confianza_ia = res['score']
            else:
                confianza_real = res['score']
        
        # Formateamos el JSON para que Kotlin lo lea feliz
        if confianza_ia > 0.5:
            resultado = {"prediction": "CONTENIDO_IA_DETECTED", "confidence": confianza_ia}
        else:
            resultado = {"prediction": "IMAGEN_REAL", "confidence": confianza_real}
            
        print(json.dumps(resultado))
        
    except Exception as e:
        error = {"error": str(e)}
        print(json.dumps(error))
        sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) > 1:
        procesar_forense(sys.argv[1])