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
        val traceId = httpRequest.getHeader("X-Trace-Id") ?: UUID.randomUUID().toString()
        MDC.put("traceId", traceId)
        try {
            chain.doFilter(request, response)
        } finally {
            // Limpiar MDC después de la request
            MDC.clear()
        }
    }
}