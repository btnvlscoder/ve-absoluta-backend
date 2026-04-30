import os
import sys
import torch
import torch.nn as nn
import torch.optim as optim
from torchvision import models
import time

# Esto le dice a Python que busque en la carpeta actual del script
sys.path.append(os.path.dirname(__file__))
from data_loader import preparar_datos 

# --- CONFIGURACIÓN ---
PATH_DATASET = "ai_engine/datasets/dfdc_faces"
BATCH_SIZE = 32
EPOCHS = 10
LEARNING_RATE = 0.001
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

def entrenar():
    print(f"--- INICIANDO ENTRENAMIENTO EN {DEVICE} ---")
    
    # Candado maestro para inicialización de pesos y operaciones en GPU/CPU
    torch.manual_seed(42)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(42)

    # 1. Cargar Datos (Usando tu estructura de carpetas)
    train_loader, val_loader, clases = preparar_datos(os.path.join(PATH_DATASET, "train"), BATCH_SIZE)
    print(f"Clases: {clases} | Entrenamiento: {len(train_loader.dataset)} imágenes")

    # 2. Modelo ResNet50 (Transfer Learning)
    modelo = models.resnet50(weights='DEFAULT')
    for param in modelo.parameters():
        param.requires_grad = False # Congelamos lo que ya sabe
    
    # Ajustamos la salida para Real vs Fake (2 clases)
    num_ftrs = modelo.fc.in_features
    modelo.fc = nn.Linear(num_ftrs, len(clases))
    modelo = modelo.to(DEVICE)

    # 3. Optimización
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(modelo.fc.parameters(), lr=LEARNING_RATE)

    # 4. Loop de entrenamiento
    for epoch in range(EPOCHS):
        modelo.train()
        running_loss = 0.0
        corrects = 0

        for inputs, labels in train_loader:
            inputs, labels = inputs.to(DEVICE), labels.to(DEVICE)
            optimizer.zero_grad()
            
            outputs = modelo(inputs)
            _, preds = torch.max(outputs, 1)
            loss = criterion(outputs, labels)

            loss.backward()
            optimizer.step()

            running_loss += loss.item() * inputs.size(0)
            corrects += torch.sum(preds == labels.data)

        acc = corrects.double() / len(train_loader.dataset)
        print(f"Época {epoch+1}/{EPOCHS} - Loss: {running_loss/len(train_loader.dataset):.4f} - Acc: {acc:.4f}")

    # 5. Guardar el "cerebro"
    os.makedirs("ai_engine/models", exist_ok=True)
    torch.save(modelo.state_dict(), "ai_engine/models/ve_absoluta_v1.pth")
    print("\n¡Modelo guardado con éxito!")

if __name__ == "__main__":
    entrenar()