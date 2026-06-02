import cv2
import numpy as np
from PIL import Image, ImageChops
import io

def realizar_analisis_ela(imagen_original: Image.Image, calidad_ela: int = 90) -> dict:
    """
    Realiza Error Level Analysis (ELA) para detectar manipulaciones
    retorna solo metricas crudas
    """
    try:
        buffer = io.BytesIO()
        if imagen_original.mode != 'RGB':
            imagen_original = imagen_original.convert('RGB')
            
        imagen_original.save(buffer, 'JPEG', quality=calidad_ela)
        buffer.seek(0)
        
        imagen_comprimida = Image.open(buffer)
        diferencia = ImageChops.difference(imagen_original, imagen_comprimida)
        
        extremos = diferencia.getextrema()
        max_diferencia = max([ex[1] for ex in extremos])
        
        matriz_dif = np.array(diferencia)
        promedio_ruido = np.mean(matriz_dif)
            
        return {
            "diferencia_maxima": int(max_diferencia),
            "ruido_promedio": float(round(promedio_ruido, 2))
        }
    except Exception as e:
        return {"error": f"Fallo en análisis ELA: {str(e)}"}

def extraer_huella_sensor(imagen_pil: Image.Image) -> float:
    """
    Extrae la huella del sensor usando filtro Laplaciano
    - Varianza alta (>60): Huella fuerte → foto de camara real
    - Varianza baja (<40): Ausencia de huella → probable generación sintética
    """
    img_cv = np.array(imagen_pil.convert('L'))
    ruido_laplaciano = cv2.Laplacian(img_cv, cv2.CV_64F)
    varianza = ruido_laplaciano.var()
    return float(varianza)
