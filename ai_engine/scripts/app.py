import gradio as gr
from transformers import pipeline
from PIL import Image

# 1. Cargamos un modelo generalista 100% público y abierto
detector_ia = pipeline("image-classification", model="umm-maybe/AI-image-detector")

def predecir_imagen(imagen_pil):
    # Seguridad crítica: Convertir siempre a RGB
    imagen_rgb = imagen_pil.convert('RGB')
    
    # 2. Pasamos la imagen por el pipeline generalista
    resultados = detector_ia(imagen_rgb)
    
    # Este modelo devuelve etiquetas como 'artificial' (IA) y 'human' (Real)
    confianza_ia = 0.0
    confianza_real = 0.0
    
    for res in resultados:
        etiqueta = res['label'].lower()
        if etiqueta == 'artificial' or 'fake' in etiqueta:
            confianza_ia = res['score']
        else:
            confianza_real = res['score']
            
    # 3. Formateamos la salida EXACTAMENTE como tu backend en Kotlin la espera
    if confianza_ia > 0.5:
        return {"prediccion": "CONTENIDO_IA_DETECTED", "confianza": confianza_ia}
    else:
        return {"prediccion": "IMAGEN_REAL", "confianza": confianza_real}

# 4. Levantar el servidor de Gradio
interfaz = gr.Interface(
    fn=predecir_imagen,
    inputs=gr.Image(type="pil"),
    outputs=gr.JSON(),
    title="VE ABSOLUTA - Motor Forense Generalista"
)

if __name__ == "__main__":
    interfaz.launch()