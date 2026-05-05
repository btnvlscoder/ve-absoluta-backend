package com.veabsoluta.ve_absoluta_backend.filter

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Filtro para generar y propagar traceId en todas las requests.
 * Esto permite correlacionar logs entre servicios.
 */
@Component
class TraceFilter : Filter {

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        // Inyectar cabeceras CORS manualmente por seguridad
        httpResponse.setHeader("Access-Control-Allow-Origin", "https://ve-absoluta-frontend.onrender.com")
        httpResponse.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        httpResponse.setHeader("Access-Control-Allow-Headers", "*")
        httpResponse.setHeader("Access-Control-Allow-Credentials", "true")

        if ("OPTIONS".equals(httpRequest.method, ignoreCase = true)) {
            httpResponse.status = HttpServletResponse.SC_OK
            return
        }
        // Intentar obtener traceId del header, si no existe generar uno nuevo
        val traceId = httpRequest.getHeader("X-Trace-Id") ?: UUID.randomUUID().toString()
        
        // Poner en MDC para que esté disponible en todos los logs
        MDC.put("traceId", traceId)
        
        try {
            chain.doFilter(request, response)
        } finally {
            // Limpiar MDC después de la request
            MDC.clear()
        }
    }
}