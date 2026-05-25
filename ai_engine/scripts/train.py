import os
import torch
from torchvision import datasets
from torch.utils.data import Dataset
from transformers import (
    AutoImageProcessor,
    AutoModelForImageClassification,
    TrainingArguments,
    Trainer
)

PATH_DATASET = "datasets/test"
MODELO_BASE = "umm-maybe/AI-image-detector"
OUTPUT_DIR = "models/ve_absoluta_vit"

class TraductorViT(Dataset):
    """
    Wrapper para adaptar un dataset de imágenes a la entrada esperada por el ViT.
    Aplica el pre-procesamiento del modelo y convierte las etiquetas a tensores.
    """
    def __init__(self, dataset_pytorch, procesador):
        self.dataset = dataset_pytorch
        self.procesador = procesador

    def __len__(self):
        return len(self.dataset)

    def __getitem__(self, idx):
        img, label = self.dataset[idx]
        img = img.convert("RGB")

        inputs = self.procesador(images=img, return_tensors="pt")

        return {
            "pixel_values": inputs["pixel_values"].squeeze(0),
            "labels": torch.tensor(label)
        }

def preparar_datos():
    """
    Carga y preprocesa el dataset:
    - Carga imágenes desde la carpeta con estructura ImageFolder
    - Divide en train/validation (80/20) con semilla 42 para reproducibilidad
    - Aplica el processor del ViT
    """
    print("1. Cargando el 'Picador' de imágenes del Transformer...")
    procesador = AutoImageProcessor.from_pretrained(MODELO_BASE)

    dataset_base = datasets.ImageFolder(PATH_DATASET)
    clases = dataset_base.classes
    print(f"   -> Clases detectadas: {clases}")

    train_size = int(0.8 * len(dataset_base))
    val_size = len(dataset_base) - train_size
    torch.manual_seed(42)
    train_base, val_base = torch.utils.data.random_split(dataset_base, [train_size, val_size])

    train_dataset = TraductorViT(train_base, procesador)
    val_dataset = TraductorViT(val_base, procesador)

    return train_dataset, val_dataset, procesador, clases

def cargar_modelo(clases):
    """
    Carga el modelo base y configura la cabeza de clasificación personalizada.
    El modelo base es un detector de imágenes IA pre-entrenado.
    """
    print(f"2. Descargando el cerebro base: {MODELO_BASE}...")
    id2label = {str(i): c for i, c in enumerate(clases)}
    label2id = {c: str(i) for i, c in enumerate(clases)}

    modelo = AutoModelForImageClassification.from_pretrained(
        MODELO_BASE,
        num_labels=len(clases),
        id2label=id2label,
        label2id=label2id,
        ignore_mismatched_sizes=True
    )
    return modelo

def entrenar_vit():
    """
    Pipeline completo de fine-tuning del ViT:
    - Carga y prepara datos
    - Carga el modelo base
    - Configura hiperparámetros (LR, batch size, epochs)
    - Entrena y evalúa por época
    - Guarda el mejor modelo según loss de validación
    """
    train_dataset, val_dataset, procesador, clases = preparar_datos()
    modelo = cargar_modelo(clases)

    print("3. Configurando el Piloto Automático (Trainer)...")
    argumentos_entrenamiento = TrainingArguments(
        output_dir=OUTPUT_DIR,
        remove_unused_columns=False,
        eval_strategy="epoch",
        save_strategy="epoch",
        learning_rate=2e-5,
        per_device_train_batch_size=16,
        gradient_accumulation_steps=4,
        per_device_eval_batch_size=16,
        num_train_epochs=5,
        warmup_ratio=0.1,
        logging_steps=10,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss"
    )

    entrenador = Trainer(
        model=modelo,
        args=argumentos_entrenamiento,
        train_dataset=train_dataset,
        eval_dataset=val_dataset,
        processing_class=procesador,
    )

    print("4. ¡Iniciando Aprendizaje Continuo (Fine-Tuning)!")
    entrenador.train()

    print(f"5. Guardando el modelo definitivo VE ABSOLUTA v2 en: {OUTPUT_DIR}")
    entrenador.save_model(OUTPUT_DIR)

if __name__ == "__main__":
    entrenar_vit()