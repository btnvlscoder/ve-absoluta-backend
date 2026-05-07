package com.veabsoluta.ve_absoluta_backend.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class RootController {

    @GetMapping("/")
    fun index(): ResponseEntity<Map<String, String>> {
        val status = mapOf(
            "servicio" to "VE Absoluta API",
            "estado" to "Online",
            "motor_ia" to "Conectado"
        )
        return ResponseEntity.ok(status)
    }
}