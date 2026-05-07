package com.veabsoluta.ve_absoluta_backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.servlet.resource.NoResourceFoundException
@Configuration
class WebClientConfig {

    @Bean
    fun webClientBuilder(): WebClient.Builder {
        return WebClient.builder()
    }
}