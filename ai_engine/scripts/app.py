import gradio as gr
import torch
from PIL import Image
from torchvision import transforms
# IMPORTANTE: Si usaste una clase personalizada para tu ResNet, debes pegarla aquí
# class MiModeloResNet(nn.Module): ...

# 1. Cargar el modelo a la CPU (Recuerda que HF Free usa CPU, no CUDA)
device = torch.device("cpu")
modelo = torch.load("ve_absoluta.pth", map_location=device)
modelo.eval()

# 2. Definir cómo se procesa la imagen
def predecir_imagen(imagen_pil):
    # Transformaciones clásicas de ResNet (Ajusta según tu entrenamiento)
    transform = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
    ])
    
    img_tensor = transform(imagen_pil).unsqueeze(0).to(device)
    
    with torch.no_grad():
        salida = modelo(img_tensor)
        # Aquí calculas tu probabilidad usando Softmax o Sigmoid
        probabilidad = torch.nn.functional.softmax(salida[0], dim=0)
        
        # Supongamos que el índice 1 es IA y el 0 es REAL
        confianza_ia = float(probabilidad[1])
        
        if confianza_ia > 0.5:
            return {"prediccion": "CONTENIDO_IA_DETECTED", "confianza": confianza_ia}
        else:
            return {"prediccion": "IMAGEN_REAL", "confianza": float(probabilidad[0])}

# 3. Levantar el servidor de Gradio
interfaz = gr.Interface(
    fn=predecir_imagen,
    inputs=gr.Image(type="pil"), # Recibe la imagen y la pasa a formato PIL
    outputs=gr.JSON(),           # Devuelve nuestro hermoso JSON
    title="VE ABSOLUTA - Motor de Inferencia"
)

if __name__ == "__main__":
    interfaz.launch()