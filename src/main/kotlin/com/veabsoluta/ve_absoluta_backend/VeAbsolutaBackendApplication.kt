package com.veabsoluta.ve_absoluta_backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class VeAbsolutaBackendApplication{
	@PostConstruct
    fun init() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"))
    }
}

fun main(args: Array<String>) {
	runApplication<VeAbsolutaBackendApplication>(*args)
}
