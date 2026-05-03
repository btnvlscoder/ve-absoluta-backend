-- V1__Create_analisis_table.sql
-- Creación de la tabla para almacenar el historial de análisis de deepfakes

CREATE TABLE historial_analisis (
    id BIGSERIAL PRIMARY KEY,
    nombre_archivo VARCHAR(255) NOT NULL,
    ruta_archivo VARCHAR(500) NOT NULL,
    prediccion VARCHAR(50) NOT NULL,
    confianza DOUBLE PRECISION NOT NULL,
    fecha TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Índices para optimizar consultas
CREATE INDEX idx_historial_analisis_fecha ON historial_analisis(fecha);
CREATE INDEX idx_historial_analisis_prediccion ON historial_analisis(prediccion);