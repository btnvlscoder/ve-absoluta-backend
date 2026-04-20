import torch
import torch.nn as nn
from torchvision import models, transforms
from PIL import Image
import json
import sys
import os

# --- CONFIGURACIÓN DE RUTAS INTELIGENTE ---
# Obtenemos la carpeta donde está este script (ai_engine/scripts)
BASE_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
# Aseguramos que la ruta al modelo sea correcta
MODEL_PATH = os.path.join(BASE_SCRIPTS_DIR, "..", "models", "ve_absoluta_v1.pth")
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

def cargar_modelo():
    # 1. Definimos la misma arquitectura que usamos en el entrenamiento
    modelo = models.resnet50()
    num_ftrs = modelo.fc.in_features
    modelo.fc = nn.Linear(num_ftrs, 2) # [fake, real]
    
    # 2. Cargamos los pesos guardados
    if not os.path.exists(MODEL_PATH):
        raise FileNotFoundError(f"No se encontró el modelo en {MODEL_PATH}")
    
    modelo.load_state_dict(torch.load(MODEL_PATH, map_location=DEVICE, weights_only=True))
    modelo.to(DEVICE)
    modelo.eval() # Modo evaluación (apaga el Dropout y Batchnorm)
    return modelo

def procesar_imagen(ruta_imagen, modelo):
    # 3. Transformaciones (Deben ser IGUALES a las del entrenamiento)
    transform = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    try:
        # 4. Inferencia
        imagen = Image.open(ruta_imagen).convert('RGB')
        imagen_t = transform(imagen).unsqueeze(0).to(DEVICE)

        with torch.no_grad():
            outputs = modelo(imagen_t)
            # Aplicamos Softmax para obtener probabilidades de 0 a 1
            probabilidades = torch.nn.functional.softmax(outputs, dim=1)[0]
            confianza, prediccion = torch.max(probabilidades, 0)

        # Mapeo de clases (Basado en lo que vimos en los logs de entrenamiento)
        clases = ["CONTENIDO_IA_DETECTED", "CONTENIDO_REAL"]
        resultado = {
            "prediction": clases[prediccion.item()],
            "confidence": round(float(confianza.item()), 4),
            "status": "success"
        }
    except Exception as e:
        resultado = {"error": str(e), "status": "error"}

    return resultado

if __name__ == "__main__":
    # El backend de Kotlin enviará la ruta como argumento de sistema
    if len(sys.argv) > 1:
        ruta = sys.argv[1]
        try:
            model = cargar_modelo()
            veredicto = procesar_imagen(ruta, model)
            # Imprimimos SOLO el JSON para que Kotlin lo capture sin ruido
            print(json.dumps(veredicto))
        except Exception as e:
            print(json.dumps({"error": str(e), "status": "error"}))
    else:
        print(json.dumps({"error": "No se proporcionó ruta de imagen"}))