package com.veabsoluta.ve_absoluta_backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

@Configuration
class CorsConfig {

    @Bean
    fun corsFilter(): CorsFilter {
        val config = CorsConfiguration()
        
        // Permitir todos los orígenes
        config.allowedOrigins = listOf("*")
        // Permitir todas las cabeceras
        config.allowedHeaders = listOf("*")
        // Permitir todos los métodos HTTP, especialmente OPTIONS que es el que usa Chrome para verificar
        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
        
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        
        // Al registrarlo como un Bean CorsFilter, Spring Boot lo pone al principio de la cadena
        return CorsFilter(source)
    }
}