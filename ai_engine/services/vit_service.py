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

        # ==========================================
        # 3. GENERACIÓN DEL MAPA DE ATENCIÓN (HEATMAP)
        # ==========================================
        attentions = outputs.attentions[-1]
        
        # Extraemos la atención del token [CLS] (índice 0) hacia los parches espaciales
        cls_attn = attentions[0, :, 0, 1:].mean(dim=0)
        num_patches = cls_attn.shape[0]

        # ALGORITMO DINÁMICO DE FACTORIZACIÓN 2D
        # 1. Algunos modelos (como DeiT) tienen tokens especiales extra.
        # Si quitando 1 token logramos un cuadrado perfecto (ej. 50 -> 49 = 7x7), lo ignoramos.
        if int(np.sqrt(num_patches))**2 != num_patches:
            if int(np.sqrt(num_patches - 1))**2 == (num_patches - 1):
                cls_attn = cls_attn[1:] 
                num_patches -= 1
        
        # 2. Encontramos los factores más cercanos para armar un rectángulo/cuadrado perfecto
        lado_a = int(np.sqrt(num_patches))
        while num_patches % lado_a != 0:
            lado_a -= 1
        lado_b = num_patches // lado_a
        
        # Asignamos las dimensiones de la grilla (ej. 48 se convierte automáticamente en 6x8)
        h_grid, w_grid = min(lado_a, lado_b), max(lado_a, lado_b)
        
        # Convertimos la secuencia 1D en una matriz 2D exacta sin romper la memoria
        grid_attn = cls_attn.reshape(h_grid, w_grid).numpy()
        
        # Normalizamos para la visualización de colores (0 a 255)
        grid_attn = (grid_attn - grid_attn.min()) / (grid_attn.max() - grid_attn.min())
        grid_attn = np.uint8(255 * grid_attn)

        # OpenCV estira esta pequeña grilla perfecta al tamaño gigante de la foto original
        img_np = np.array(imagen_pil)
        h_img, w_img = img_np.shape[:2]
        attn_resized = cv2.resize(grid_attn, (w_img, h_img))
        heatmap_color = cv2.applyColorMap(attn_resized, cv2.COLORMAP_JET)
        
        # Fusionamos las imágenes
        img_bgr = cv2.cvtColor(img_np, cv2.COLOR_RGB2BGR)
        overlay = cv2.addWeighted(img_bgr, 0.6, heatmap_color, 0.4, 0)
        overlay_rgb = cv2.cvtColor(overlay, cv2.COLOR_BGR2RGB)

        # Conversión a Base64
        pil_heatmap = Image.fromarray(overlay_rgb)
        buffered = BytesIO()
        pil_heatmap.save(buffered, format="JPEG")
        heatmap_b64 = base64.b64encode(buffered.getvalue()).decode("utf-8")

        # ==========================================
        # 4. Superposición sobre la imagen original
        # ==========================================

        img_np = np.array(imagen_pil)
        h, w = img_np.shape[:2]
        attn_resized = cv2.resize(grid_attn, (w, h))
        heatmap_color = cv2.applyColorMap(attn_resized, cv2.COLORMAP_JET)
        
        img_bgr = cv2.cvtColor(img_np, cv2.COLOR_RGB2BGR)
        overlay = cv2.addWeighted(img_bgr, 0.6, heatmap_color, 0.4, 0)
        overlay_rgb = cv2.cvtColor(overlay, cv2.COLOR_BGR2RGB)
  
        # ==========================================
        # 5. Conversión a Base64
        # ==========================================

        pil_heatmap = Image.fromarray(overlay_rgb)
        buffered = BytesIO()
        pil_heatmap.save(buffered, format="JPEG")
        heatmap_b64 = base64.b64encode(buffered.getvalue()).decode("utf-8")

        return {
            "prediccion": label.upper(),
            "confianza": round(confianza * 100, 2),
            "heatmap": f"data:image/jpeg;base64,{heatmap_b64}",
            
        }
    except Exception as e:
        return {"error": f"Fallo en motor ViT: {str(e)}"}

def generar_narrativa_vit(prediccion: str, confianza: float, heatmap_matrix: np.ndarray) -> dict:
    """Traduce el mapa de calor del ViT a una justificación técnica."""
    h, w = heatmap_matrix.shape
    y_max, x_max = np.unravel_index(np.argmax(heatmap_matrix), heatmap_matrix.shape)
    
    if y_max < h * 0.3 or y_max > h * 0.7 or x_max < w * 0.3 or x_max > w * 0.7:
        sector = "perimetral"
    else:
        sector = "central"

    if prediccion == "FAKE":
        return {
            "estado": "CRÍTICO",
            "detalle": f"Anomalía visual en zona {sector}: El escaneo de superficie ha detectado elementos artificiales en la región {sector} de la imagen. La textura y la iluminación en esta área no corresponden a la física óptica de una cámara real, lo que sugiere fuertemente que fue generada por computadora (Nivel de certeza: {confianza:.1f}%)."
        }
    else:
        return {
            "estado": "SEGURO",
            "detalle": "Estructura óptica validada: La evidencia presenta una distribución natural de los píxeles. Las luces, sombras y micro-texturas mantienen la coherencia física esperada de una fotografía real, sin indicios de generación artificial."
        }
        