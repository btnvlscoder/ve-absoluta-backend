package com.veabsoluta.ve_absoluta_backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
@RestController // Convertimos la clase principal en un controlador de prueba
class VeAbsolutaBackendApplication {

    @GetMapping("/ping")
    fun ping(): String {
        return "¡El backend está vivo y Render sí actualiza, Bastián!"
    }
}

fun main(args: Array<String>) {
    runApplication<VeAbsolutaBackendApplication>(*args)
}