package com.veabsoluta.ve_absoluta_backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// Forzamos a Spring a escanear desde la raíz absoluta
@SpringBootApplication(scanBasePackages = [
    "com.veabsoluta", 
    "com.veabsoluta.ve_absoluta_backend"
])
@RestController
class VeAbsolutaBackendApplication {

    @GetMapping("/ping")
    fun ping(): String {
        return "¡El backend está vivo y el radar funciona!"
    }
}

fun main(args: Array<String>) {
    runApplication<VeAbsolutaBackendApplication>(*args)
}