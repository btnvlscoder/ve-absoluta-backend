package com.veabsoluta.ve_absoluta_backend
//imports zona horaria
import jakarta.annotation.PostConstruct
import java.util.TimeZone
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
