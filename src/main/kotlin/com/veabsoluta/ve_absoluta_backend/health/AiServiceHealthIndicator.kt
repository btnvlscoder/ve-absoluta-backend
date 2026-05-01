package com.veabsoluta.ve_absoluta_backend.health

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * Health indicator para el servicio de análisis de IA.
 * 
 * Verifica que el servicio IA esté disponible haciendo un ping básico.
 * Nota: El health check real depende de si tu servicio IA expone endpoint /health
 */
@Component
class AiServiceHealthIndicator(
    private val webClient: WebClient,
    
    @Value("\${ai.service.url:http://localhost:8000}")
    private val aiServiceBaseUrl: String
) : HealthIndicator {

    override fun health(): Health {
        return try {
            // Intentamos conectar al servicio IA
            // Si el servicio no tiene endpoint /health, usamos head simple
            val response = webClient.head()
                .uri(aiServiceBaseUrl)
                .retrieve()
                .toBodilessEntity()
                .block()

            val statusCode = response?.statusCode?.value() ?: 0
            if (statusCode in 200..399) {
                Health.up()
                    .withDetail("service", "AI Analysis Service")
                    .withDetail("url", aiServiceBaseUrl)
                    .withDetail("statusCode", statusCode)
                    .build()
            } else {
                Health.unknown()
                    .withDetail("service", "AI Analysis Service")
                    .withDetail("statusCode", statusCode)
                    .build()
            }
                
        } catch (e: Exception) {
            Health.down()
                .withDetail("service", "AI Analysis Service")
                .withDetail("error", e.message ?: "Connection failed")
                .build()
        }
    }
}