import torch

print("--- Verificación de Entorno VE ABSOLUTA ---")
print(f"Versión de PyTorch: {torch.__version__}")
print(f"¿CUDA disponible?: {'SÍ (Estamos ready)' if torch.cuda.is_available() else 'NO (Algo falló)'}")

if torch.cuda.is_available():
    print(f"GPU detectada: {torch.cuda.get_device_name(0)}")
    print(f"Memoria total: {torch.cuda.get_device_properties(0).total_memory / 1e9:.2f} GB")