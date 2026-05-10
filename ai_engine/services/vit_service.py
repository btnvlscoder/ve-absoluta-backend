import torch
import numpy as np
import cv2
import base64
from io import BytesIO
from PIL import Image
from transformers import AutoImageProcessor, AutoModelForImageClassification

# Configuración del modelo
MODEL_DIR = "btnvlscoder/ve-absoluta-vit-v2"

# Cargamos el procesador y el modelo globalmente para que persistan en el contenedor
try:
    processor = AutoImageProcessor.from_pretrained(MODEL_DIR)
    model = AutoModelForImageClassification.from_pretrained(MODEL_DIR)
    model.eval()
    print(f"✅ Motor ViT cargado exitosamente desde {MODEL_DIR}")
except Exception as e:
    print(f"❌ Error al cargar el modelo ViT: {e}")
    model = None

def analizar_con_vit(imagen_pil: Image.Image) -> dict:
    """
    Realiza la clasificación de la imagen y genera el mapa de calor de atención.
    """
    if model is None:
        return {"error": "Modelo no cargado"}

    try:
        # 1. Preprocesamiento
        inputs = processor(images=imagen_pil, return_tensors="pt")

        # 2. Inferencia con extracción de atenciones
        with torch.no_grad():
            outputs = model(**inputs, output_attentions=True)

        logits = outputs.logits
        probs = torch.nn.functional.softmax(logits, dim=-1)
        pred_idx = logits.argmax(-1).item()
        label = model.config.id2label[pred_idx]
        confianza = probs[0][pred_idx].item()

        # 3. Generación del Mapa de Atención (Heatmap)
        # Extraemos la atención de la última capa
        attentions = outputs.attentions[-1]
        # Promediamos las cabezas de atención del token [CLS]
        cls_attn = attentions[0, :, 0, 1:].mean(dim=0)
        
        # Redimensionar a grid cuadrado (ej. 14x14)
        grid_size = int(np.sqrt(cls_attn.shape[0]))
        grid_attn = cls_attn.reshape(grid_size, grid_size).numpy()
        
        # Normalizar para visualización
        grid_attn = (grid_attn - grid_attn.min()) / (grid_attn.max() - grid_attn.min())
        grid_attn = np.uint8(255 * grid_attn)

        # 4. Superposición sobre la imagen original
        img_np = np.array(imagen_pil)
        h, w = img_np.shape[:2]
        attn_resized = cv2.resize(grid_attn, (w, h))
        heatmap_color = cv2.applyColorMap(attn_resized, cv2.COLORMAP_JET)
        
        img_bgr = cv2.cvtColor(img_np, cv2.COLOR_RGB2BGR)
        overlay = cv2.addWeighted(img_bgr, 0.6, heatmap_color, 0.4, 0)
        overlay_rgb = cv2.cvtColor(overlay, cv2.COLOR_BGR2RGB)

        # 5. Conversión a Base64
        pil_heatmap = Image.fromarray(overlay_rgb)
        buffered = BytesIO()
        pil_heatmap.save(buffered, format="JPEG")
        heatmap_b64 = base64.b64encode(buffered.getvalue()).decode("utf-8")

        return {
            "prediccion": label.upper(),
            "confianza": round(confianza * 100, 2),
            "heatmap": f"data:image/jpeg;base64,{heatmap_b64}"
        }
    except Exception as e:
        return {"error": f"Fallo en motor ViT: {str(e)}"}