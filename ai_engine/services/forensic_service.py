import cv2
import numpy as np
from PIL import Image, ImageChops
import io

def realizar_analisis_ela(imagen_original: Image.Image, calidad_ela: int = 90) -> dict:
    """
    Realiza Error Level Analysis (ELA) para detectar manipulaciones o ruido sintético.
    El análisis se basa en la degradación uniforme de compresión JPEG:
    - Áreas reales muestran degradación homogénea
    - Áreas manipuladas o generadas por IA muestran patrones de ruido anómalos
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
        
        estado = "Normal"
        detalle = "Niveles de compresión consistentes."
        
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

def extraer_huella_sensor(imagen_pil: Image.Image) -> float:
    """
    Extrae la huella del sensor usando filtro Laplaciano (pasa alto).
    - Varianza alta (>60): Huella fuerte → foto de cámara real
    - Varianza baja (<40): Ausencia de huella → probable generación sintética
    """
    img_cv = np.array(imagen_pil.convert('L'))
    ruido_laplaciano = cv2.Laplacian(img_cv, cv2.CV_64F)
    varianza = ruido_laplaciano.var()
    return float(varianza)

def generar_narrativa_ela(diferencia_maxima: int, ruido_promedio: float, varianza_sensor: float) -> dict:
    """
    Traduce las métricas de ELA y huella del sensor a una narrativa técnica justificada.
    Aplica heurísticas combinadas de compresión y ruido de hardware.
    """
    if varianza_sensor > 150.0: 
        return {
            "estado": "SEGURO",
            "detalle": f"Huella de hardware confirmada: Se detectó ruido estático característico de un sensor óptico real (Varianza local: {varianza_sensor:.1f}). Aunque existen alteraciones de compresión superficiales, la estructura base subyacente proviene indudablemente de una captura física."
        }
    elif diferencia_maxima > 60 or ruido_promedio > 3.5:
        return {
            "estado": "ADVERTENCIA",
            "detalle": f"Varianza anómala y ausencia de huella: Discrepancia severa de compresión (Delta: {diferencia_maxima}) sin una firma de sensor óptico fuerte que lo justifique (Varianza: {varianza_sensor:.1f}). Esto sugiere fuertemente una alteración digital profunda o generación sintética."
        }
    else:
        return {
            "estado": "SEGURO",
            "detalle": f"Firma digital uniforme: Degradación homogénea detectada (Delta: {diferencia_maxima}). La matriz es consistente, lo que descarta manipulaciones locales severas o inserciones de fotomontaje."
        }
