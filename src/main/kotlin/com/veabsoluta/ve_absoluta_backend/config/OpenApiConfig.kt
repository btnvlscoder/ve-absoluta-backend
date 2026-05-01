package com.veabsoluta.ve_absoluta_backend.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuración de Swagger/OpenAPI para documentación de la API.
 * 
 * Accede a la documentación en: /swagger-ui.html
 * Documentación OpenAPI en: /v3/api-docs
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("VE Absoluta - API de Detección de Deepfakes")
                    .description("""
                        API REST para detección de deepfakes en imágenes.
                        
                        ## Flujo de uso:
                        1. Subir una imagen mediante POST /api/v1/deteccion/upload
                        2. La imagen se analiza contra modelos de IA
                        3. Se retorna la predicción (FAKE/REAL) y confianza
                        
                        ## Notas:
                        - Formatos aceptados: JPEG, PNG, WebP
                        - Tamaño máximo: 10MB
                        - Rate limit: 10 requests/minuto por IP
                    """.trimIndent())
                    .version("1.0.0")
                    .contact(
                        Contact()
                            .name("Equipo VE Absoluta")
                            .email("dev@veabsoluta.com")
                    )
                    .license(
                        License()
                            .name("Proprietario")
                            .url("https://veabsoluta.com")
                    )
            )
            .addSecurityItem(
                SecurityRequirement().addList("Bearer Authentication")
            )
            .components(
                Components()
                    .addSecuritySchemes(
                        "Bearer Authentication",
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description("Token de autenticación (si se requiere)")
                    )
            )
    }
}