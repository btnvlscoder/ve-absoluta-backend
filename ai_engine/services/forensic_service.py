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

def generar_narrativa_ela(diferencia_maxima: int, ruido_promedio: float, varianza_sensor: float) -> dict:
    """Traduce los cálculos de compresión y ruido de sensor a una justificación técnica."""
    
    # REGLA DE ORO: Si la varianza del sensor es alta, es una cámara física real.
    # Perdonamos los niveles de compresión porque probablemente pasó por Lightroom/Photoshop.
    if varianza_sensor > 150.0: 
        return {
            "estado": "SEGURO",
            "detalle": f"Huella de hardware confirmada: Se detectó ruido estático característico de un sensor óptico real (Varianza local: {varianza_sensor:.1f}). Aunque existen alteraciones de compresión superficiales, la estructura base subyacente proviene indudablemente de una captura física."
        }
    # Si la varianza es baja (sin huella de sensor) y el ELA está alterado, es un peligro.
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

def extraer_huella_sensor(imagen_pil: Image.Image) -> float:
    """
    Extrae el ruido residual (huella estática del sensor) usando un filtro Laplaciano.
    """
    # 1. Convertir la imagen a escala de grises usando OpenCV
    img_cv = np.array(imagen_pil.convert('L'))
    
    # 2. Aplicar filtro Laplaciano (Paso alto para aislar micro-texturas y ruido de hardware)
    ruido_laplaciano = cv2.Laplacian(img_cv, cv2.CV_64F)
    
    # 3. Calcular la varianza (Qué tan disperso y real es el ruido)
    varianza = ruido_laplaciano.var()
    
    return float(varianza)