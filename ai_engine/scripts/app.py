import gradio as gr
import torch
from PIL import Image
from torchvision import transforms

# 1. Cargar el modelo a la CPU
device = torch.device("cpu")
# OJO: Asegúrate de que el nombre del archivo aquí coincida con el que subiste a Hugging Face
# Según tu repo se llama "ve_absoluta_v1.pth", ajustalo si allá se llama distinto.
modelo = torch.load("ve_absoluta_v1.pth", map_location=device) 
modelo.eval()

# 2. Definir cómo se procesa la imagen
def predecir_imagen(imagen_pil):
    
    # SEGURIDAD CRÍTICA: Convertir siempre a RGB para evitar crash con PNGs transparentes
    imagen_rgb = imagen_pil.convert('RGB')
    
    # Transformaciones clásicas de ResNet
    transform = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
    ])
    
    img_tensor = transform(imagen_rgb).unsqueeze(0).to(device)
    
    with torch.no_grad():
        salida = modelo(img_tensor)
        probabilidad = torch.nn.functional.softmax(salida[0], dim=0)
        
        # FIX DE ÍNDICES: Basado en el entrenamiento (0: Fake/IA, 1: Real)
        confianza_ia = float(probabilidad[0])
        confianza_real = float(probabilidad[1])
        
        # Si la confianza de que es IA supera el 50%
        if confianza_ia > 0.5:
            return {"prediccion": "CONTENIDO_IA_DETECTED", "confianza": confianza_ia}
        else:
            return {"prediccion": "IMAGEN_REAL", "confianza": confianza_real}

# 3. Levantar el servidor de Gradio
interfaz = gr.Interface(
    fn=predecir_imagen,
    inputs=gr.Image(type="pil"),
    outputs=gr.JSON(),
    title="VE ABSOLUTA - Motor de Inferencia"
)

if __name__ == "__main__":
    interfaz.launch()