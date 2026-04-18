import sys
import json
import os

def procesar_imagen(ruta_imagen):
    # Aquí es donde irá el modelo de Deep Learning después
    # Por ahora simulamos que procesamos la imagen que nos mando Kotlin
    
    if not os.path.exists(ruta_imagen):
        return {"error": "No se encontró la imagen en la ruta especificada"}

    # Simulamos el veredicto de la IA
    resultado = {
        "prediction": "CONTENIDO_IA_DETECTED",
        "confidence": 0.89
    }
    
    return resultado

if __name__ == "__main__":
    # Kotlin nos manda la ruta de la imagen como primer argumento
    if len(sys.argv) > 1:
        ruta = sys.argv[1]
        resultado = procesar_imagen(ruta)
        # IMPORTANTE: Imprimimos solo el JSON para que Kotlin lo pueda leer
        print(json.dumps(resultado))
    else:
        print(json.dumps({"error": "No se recibió ninguna ruta de imagen"}))