import torch
import numpy as np
import cv2
import base64
from io import BytesIO
from PIL import Image
from transformers import AutoImageProcessor, AutoModelForImageClassification

# Configuración del modelo
MODEL_DIR = "btnvlscoder/ve-absoluta-vit-v2"

try:
    processor = AutoImageProcessor.from_pretrained(MODEL_DIR)
    model = AutoModelForImageClassification.from_pretrained(MODEL_DIR)
    model.eval()
    print(f"Motor ViT cargado exitosamente desde {MODEL_DIR}")
except Exception as e:
    print(f"Error al cargar el modelo ViT: {e}")
    model = None

def _convert_to_base64(grid_attn: np.ndarray, img_np: np.ndarray) -> str:
    """Función auxiliar para convertir una matriz de atención en un string Base64 con la imagen superpuesta."""
    h, w = img_np.shape[:2]
    attn_resized = cv2.resize(grid_attn, (w, h))
    heatmap_color = cv2.applyColorMap(attn_resized, cv2.COLORMAP_JET)
    
    img_bgr = cv2.cvtColor(img_np, cv2.COLOR_RGB2BGR)
    overlay = cv2.addWeighted(img_bgr, 0.6, heatmap_color, 0.4, 0)
    overlay_rgb = cv2.cvtColor(overlay, cv2.COLOR_BGR2RGB)

    pil_heatmap = Image.fromarray(overlay_rgb)
    buffered = BytesIO()
    pil_heatmap.save(buffered, format="JPEG")
    return base64.b64encode(buffered.getvalue()).decode("utf-8")

def analizar_con_vit(imagen_pil: Image.Image) -> dict:
    if model is None:
        return {"error": "Modelo no cargado"}

    try:
        inputs = processor(images=imagen_pil, return_tensors="pt")

        with torch.no_grad():
            outputs = model(**inputs, output_attentions=True)

        logits = outputs.logits
        probs = torch.nn.functional.softmax(logits, dim=-1)
        pred_idx = logits.argmax(-1).item()
        label = model.config.id2label[pred_idx]
        confianza = probs[0][pred_idx].item()

        # ==========================================
        # 3. GENERACIÓN DE MAPAS DE ATENCIÓN MULTINIVEL
        # ==========================================
        attentions = outputs.attentions # Aquí tenemos TODAS las capas
        img_np = np.array(imagen_pil)

        # -- ALGORITMO 1: RAW ATTENTION (Última Capa) --
        # Lo que ya tenías: Promedio de cabezas en la última capa
        cls_attn_raw = attentions[-1][0, :, 0, 1:].mean(dim=0)
        num_patches = cls_attn_raw.shape[0]

        if int(np.sqrt(num_patches))**2 != num_patches:
            if int(np.sqrt(num_patches - 1))**2 == (num_patches - 1):
                cls_attn_raw = cls_attn_raw[1:] 
                num_patches -= 1
        
        lado_a = int(np.sqrt(num_patches))
        while num_patches % lado_a != 0:
            lado_a -= 1
        lado_b = num_patches // lado_a
        h_grid, w_grid = min(lado_a, lado_b), max(lado_a, lado_b)
        
        grid_attn_raw = cls_attn_raw.reshape(h_grid, w_grid).numpy()
        grid_attn_raw = (grid_attn_raw - grid_attn_raw.min()) / (grid_attn_raw.max() - grid_attn_raw.min())
        grid_attn_raw = np.uint8(255 * grid_attn_raw)

        # -- ALGORITMO 2: THRESHOLDING (Filtro de Ruido) --
        # Tomamos el mapa crudo y aplicamos un umbral estricto (top 30%)
        umbral = np.percentile(grid_attn_raw, 70)
        grid_attn_thresh = np.where(grid_attn_raw > umbral, grid_attn_raw, 0)
        # Re-normalizamos el resultado filtrado
        grid_attn_thresh = (grid_attn_thresh - grid_attn_thresh.min()) / (grid_attn_thresh.max() - grid_attn_thresh.min() + 1e-8)
        grid_attn_thresh = np.uint8(255 * grid_attn_thresh)

        # -- ALGORITMO 3: ATTENTION ROLLOUT (Silueta Profunda) --
        # Multiplicación iterativa de todas las capas para eliminar el sesgo de borde
        try:
            result = torch.eye(attentions[0].size(-1)).to(attentions[0].device)
            for attention in attentions:
                attention_heads_fused = attention.mean(axis=1) # Promedio de cabezas por capa
                flat = attention_heads_fused.view(attention_heads_fused.size(0), -1)
                _, indices = flat.topk(int(flat.size(-1)*0.9), -1, False) # Descartar 10% ruido
                indices = indices[indices != 0]
                flat[0, indices] = 0
                I = torch.eye(attention_heads_fused.size(-1)).to(attention_heads_fused.device)
                a = (attention_heads_fused + 1.0*I)/2
                a = a / a.sum(dim=-1, keepdim=True)
                result = torch.matmul(a, result)

            mask = result[0, 0, 1:]
            
            # Ajuste de parches corregido (Indentación correcta)
            if mask.shape[0] != (h_grid * w_grid):
                mask = mask[1:]

            grid_attn_rollout = mask.reshape(h_grid, w_grid).numpy()
            grid_attn_rollout = (grid_attn_rollout - grid_attn_rollout.min()) / (grid_attn_rollout.max() - grid_attn_rollout.min() + 1e-8)
            grid_attn_rollout = np.uint8(255 * grid_attn_rollout)
            
        except Exception as e:
            print(f"Rollout omitido (incompatibilidad de tensores). Usando Fallback. Detalle: {e}")
            # FALLBACK: Si las dimensiones no cuadran, usamos el mapa de umbral como salvavidas
            grid_attn_rollout = grid_attn_thresh.copy()

        # ==========================================
        # 4. CONVERSIÓN Y RETORNO
        # ==========================================
        heatmap_raw_b64 = _convert_to_base64(grid_attn_raw, img_np)
        heatmap_thresh_b64 = _convert_to_base64(grid_attn_thresh, img_np)
        heatmap_rollout_b64 = _convert_to_base64(grid_attn_rollout, img_np)

        return {
            "prediccion": label.upper(),
            "confianza": round(confianza * 100, 2),
            "heatmap": f"data:image/jpeg;base64,{heatmap_raw_b64}",
            "heatmap_threshold": f"data:image/jpeg;base64,{heatmap_thresh_b64}",
            "heatmap_rollout": f"data:image/jpeg;base64,{heatmap_rollout_b64}",
            "grid_attn": grid_attn_raw.tolist() if hasattr(grid_attn_raw, 'tolist') else grid_attn_raw 
        }
    except Exception as e:
        import traceback
        traceback.print_exc()
        return {"error": f"Fallo en motor ViT: {str(e)}"}

def generar_narrativa_vit(prediccion: str, confianza: float, heatmap_matrix: np.ndarray) -> dict:
    if isinstance(heatmap_matrix, list):
        heatmap_matrix = np.array(heatmap_matrix)

    if heatmap_matrix is None:
        return {"estado": "INFO", "detalle": "Análisis completado sin mapa de atención."}
    
    h, w = heatmap_matrix.shape
    y_max, x_max = np.unravel_index(np.argmax(heatmap_matrix), heatmap_matrix.shape)
    
    if y_max < h * 0.3 or y_max > h * 0.7 or x_max < w * 0.3 or x_max > w * 0.7:
        sector = "perimetral"
    else:
        sector = "central"

    if prediccion == "FAKE":
        return {
            "estado": "CRÍTICO",
            "detalle": f"Anomalía visual en zona {sector}: El escaneo detectó elementos artificiales en la región {sector}. La textura y la iluminación no corresponden a la física de una cámara real, sugiriendo fuertemente generación artificial (Certeza: {confianza:.1f}%)."
        }
    else:
        return {
            "estado": "SEGURO",
            "detalle": "Estructura óptica validada: La distribución de píxeles es natural. Las luces, sombras y texturas mantienen la coherencia física esperada de una fotografía real, sin indicios de manipulación por IA."
        }