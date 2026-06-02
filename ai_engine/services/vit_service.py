import torch
import numpy as np
import cv2
import base64
import os
from io import BytesIO
from PIL import Image
from transformers import AutoImageProcessor, AutoModelForImageClassification

MODEL_DIR = os.getenv("HF_MODEL_DIR", "btnvlscoder/ve-absoluta-vit-v2")
HUGGING_FACE_TOKEN = os.getenv("HUGGING_FACE_TOKEN")

try:
    processor = AutoImageProcessor.from_pretrained(MODEL_DIR, token=HUGGING_FACE_TOKEN)
    model = AutoModelForImageClassification.from_pretrained(
        MODEL_DIR, 
        token=HUGGING_FACE_TOKEN,
        output_attentions=True,
        return_dict=True
    )
    model.eval()
    print(f"Motor ViT cargado exitosamente desde {MODEL_DIR}")
except Exception as e:
    print(f"Error al cargar el modelo ViT: {e}")
    model = None

def _convert_to_base64(grid_attn: np.ndarray, img_np: np.ndarray) -> str:
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
            outputs = model(**inputs, output_attentions=True, return_dict=True)

        logits = outputs.logits
        probs = torch.nn.functional.softmax(logits, dim=-1)
        pred_idx = logits.argmax(-1).item()
        label = model.config.id2label[pred_idx]
        confianza = probs[0][pred_idx].item()

        img_np = np.array(imagen_pil)
        attentions = getattr(outputs, "attentions", None)

        # BLOQUE DEFENSIVO XAI
        try:
            if not attentions or len(attentions) == 0:
                raise ValueError("Modelo no retornó atenciones")

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
            grid_attn_raw = (grid_attn_raw - grid_attn_raw.min()) / (grid_attn_raw.max() - grid_attn_raw.min() + 1e-8)
            grid_attn_raw = np.uint8(255 * grid_attn_raw)

            umbral = np.percentile(grid_attn_raw, 70)
            grid_attn_thresh = np.where(grid_attn_raw > umbral, grid_attn_raw, 0)
            grid_attn_thresh = (grid_attn_thresh - grid_attn_thresh.min()) / (grid_attn_thresh.max() - grid_attn_thresh.min() + 1e-8)
            grid_attn_thresh = np.uint8(255 * grid_attn_thresh)

        except Exception as e:
            print(f"[XAI WARNING] Fallo al extraer Heatmap: {e}")
            grid_attn_raw = np.zeros((14, 14), dtype=np.uint8)
            grid_attn_thresh = np.zeros((14, 14), dtype=np.uint8)
            h_grid, w_grid = 14, 14

        # BLOQUE DEFENSIVO ROLLOUT
        try:
            if not attentions or len(attentions) == 0:
                raise ValueError("Sin atenciones para Rollout")

            result = torch.eye(attentions[0].size(-1)).to(attentions[0].device)
            for attention in attentions:
                attention_heads_fused = attention.mean(axis=1)
                flat = attention_heads_fused.view(attention_heads_fused.size(0), -1)
                _, indices = flat.topk(int(flat.size(-1)*0.9), -1, False)
                indices = indices[indices != 0]
                flat[0, indices] = 0
                I = torch.eye(attention_heads_fused.size(-1)).to(attention_heads_fused.device)
                a = (attention_heads_fused + 1.0*I)/2
                a = a / a.sum(dim=-1, keepdim=True)
                result = torch.matmul(a, result)

            mask = result[0, 0, 1:]
            if mask.shape[0] != (h_grid * w_grid):
                mask = mask[1:]

            grid_attn_rollout = mask.reshape(h_grid, w_grid).numpy()
            grid_attn_rollout = (grid_attn_rollout - grid_attn_rollout.min()) / (grid_attn_rollout.max() - grid_attn_rollout.min() + 1e-8)
            grid_attn_rollout = np.uint8(255 * grid_attn_rollout)

        except Exception as e:
            print(f"[XAI WARNING] Rollout omitido: {e}")
            grid_attn_rollout = grid_attn_thresh.copy()

        h_grid, w_grid = grid_attn_raw.shape
        y_max, x_max = np.unravel_index(np.argmax(grid_attn_raw), grid_attn_raw.shape)
        
        if y_max < h_grid * 0.3 or y_max > h_grid * 0.7 or x_max < w_grid * 0.3 or x_max > w_grid * 0.7:
            sector_calculado = "perimetral"
        else:
            sector_calculado = "central"

        heatmap_raw_b64 = _convert_to_base64(grid_attn_raw, img_np)
        heatmap_thresh_b64 = _convert_to_base64(grid_attn_thresh, img_np)
        heatmap_rollout_b64 = _convert_to_base64(grid_attn_rollout, img_np)

        return {
            "prediccion": label.upper(),
            "confianza": round(confianza * 100, 2),
            "sector": sector_calculado,
            "heatmap": f"data:image/jpeg;base64,{heatmap_raw_b64}",
            "heatmap_threshold": f"data:image/jpeg;base64,{heatmap_thresh_b64}",
            "heatmap_rollout": f"data:image/jpeg;base64,{heatmap_rollout_b64}",
            "grid_attn": grid_attn_raw.tolist() if hasattr(grid_attn_raw, 'tolist') else grid_attn_raw
        }
    except Exception as e:
        import traceback
        traceback.print_exc()
        return {"error": f"Fallo en motor ViT: {str(e)}"}

