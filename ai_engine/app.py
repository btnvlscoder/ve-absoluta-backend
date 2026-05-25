from fastapi import FastAPI
from dotenv import load_dotenv
from routers import analisis_router

load_dotenv()

app = FastAPI(title="VE ABSOLUTA - Enterprise API")

app.include_router(analisis_router.router, prefix="/api/v1")

@app.get("/")
def health_check():
    return {"status": "Online", "arquitectura": "Clean"}