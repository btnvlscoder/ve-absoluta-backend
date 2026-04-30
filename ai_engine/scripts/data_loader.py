import torch 
from torchvision import datasets, transforms
from torch.utils.data import DataLoader
import os

def preparar_datos(path_dataset, batch_size=32):
    """
    Configura la carga de imagenes del dataset.
    Aplica transformaciones para que la red neuronal pueda procesarlas.
    """

    # 1. Definimos las transformaciones
    # Redimensionamos a 224x224 (estandar para ResNet) y normalizamos.
    
    transformaciones = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.RandomHorizontalFlip(p=0.5), # Aumento de datos (Data Augmentation)
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])

    # 2. Cargamos el dataset desde las carpetas
    # Se asume estructura: path/real y path/fake

    if not os.path.exists(path_dataset):
        raise FileNotFoundError(f"No se encontró la carpeta: {path_dataset}")
    
    full_dataset = datasets.ImageFolder(root=path_dataset, transform=transformaciones)

    # 3. Dividimos en Entrenamiento (80%) y Validación (20%)
    train_size = int(0.8 * len(full_dataset))
    val_size = len(full_dataset) - train_size

    # Candado de reproducibilidad para que la división sea siempre igual
    torch.manual_seed(42) 
    train_data, val_data = torch.utils.data.random_split(full_dataset, [train_size, val_size])

    # 4. Creamos los Loaders
    # num_workers=4 ayuda a que la CPU prepare las fotos mientras la GPU entrena
    train_loader = DataLoader(train_data, batch_size=batch_size, shuffle=True, num_workers=2)
    val_loader = DataLoader(val_data, batch_size=batch_size, shuffle=False, num_workers=2)

    return train_loader, val_loader, full_dataset.classes

if __name__ == "__main__":
    # Bloque de prueba para ejecutarlo directamente
    PATH_TEST = "ai_engine/datasets/dfdc_faces" # direccion de imagenes
    
    print("--- Verificando Cargador de Datos ---")
    try:
        t_loader, v_loader, clases = preparar_datos(PATH_TEST)
        print(f"Clases detectadas: {clases}")
        print(f"Imágenes de entrenamiento: {len(t_loader.dataset)}")
        print(f"Imágenes de validación: {len(v_loader.dataset)}")
        
        # Prueba de un mini-batch
        images, labels = next(iter(t_loader))
        print(f"Dimensiones del batch: {images.shape} (BatchSize, Canales, H, W)")
        
    except Exception as e:
        print(f"Error: {e}")
        print("Tip: Asegúrate de que Miguel ya haya creado las carpetas 'real' y 'fake' dentro del dataset.")