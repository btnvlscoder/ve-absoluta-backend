import numpy as np
from PIL import Image, ImageChops
import io

def realizar_analisis_ela(imagen_original: Image.Image, calidad_ela: int = 90) -> dict:
    """
    Realiza Error Level Analysis (ELA) para detectar manipulaciones o 
    ruido sintético de IAs generativas.
    """
    try:
        # 1. Guardamos la imagen temporalmente con una compresión conocida (ej. 90%)
        buffer = io.BytesIO()
        # Aseguramos que sea RGB para JPEG
        if imagen_original.mode != 'RGB':
            imagen_original = imagen_original.convert('RGB')
            
        imagen_original.save(buffer, 'JPEG', quality=calidad_ela)
        buffer.seek(0)
        
        # 2. Abrimos la imagen recomprimida
        imagen_comprimida = Image.open(buffer)
        
        # 3. Calculamos la diferencia absoluta entre la original y la comprimida
        diferencia = ImageChops.difference(imagen_original, imagen_comprimida)
        
        # 4. Extraemos métricas (Extremos (extrema) de la diferencia)
        extremos = diferencia.getextrema()
        max_diferencia = max([ex[1] for ex in extremos])
        
        # 5. Calculamos un "Score de Anomalía" basado en la diferencia promedio
        matriz_dif = np.array(diferencia)
        promedio_ruido = np.mean(matriz_dif)
        
        estado = "Normal"
        detalle = "Niveles de compresión consistentes."
        
        # Umbrales heurísticos (Miguel puede ajustar estos valores matemáticos luego)
        if promedio_ruido > 15.0 or max_diferencia > 100:
            estado = "Alteración Detectada"
            detalle = f"Alta varianza de compresión (Ruido promedio: {promedio_ruido:.2f}). Posible manipulación o generación sintética."
            
        return {
            "estado": estado,
            "detalle": detalle,
            "metricas": {
                "diferencia_maxima": int(max_diferencia),
                "ruido_promedio": float(round(promedio_ruido, 2))
            }
        }
    except Exception as e:
        return {"estado": "Error", "detalle": f"Fallo en análisis ELA: {str(e)}"}