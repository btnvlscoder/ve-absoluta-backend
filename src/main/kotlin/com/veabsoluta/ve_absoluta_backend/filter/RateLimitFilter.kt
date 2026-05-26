package com.veabsoluta.ve_absoluta_backend.filter

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Filtro de Rate Limiting para prevenir ataques DDoS.
 * 
 * Configuración:
 * - 10 requests por IP por minuto
 * - 50 requests por IP por hora
 * 
 * Respuestas cuando se excede:
 * - 429 Too Many Requests con headers de retry
 */
@Component
class RateLimitFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)
    
    // Mapa de buckets por IP (en producción usar Redis/distributed)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 10L
        private const val MAX_REQUESTS_PER_HOUR = 50L
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {

        // Dejar pasar OPTIONS sin consumir tokens
        if ("OPTIONS".equals(request.method, ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }

        val requestUri = request.requestURI
        
        // Rutas exentas de rate limiting
        if (isExemptPath(requestUri)) {
            filterChain.doFilter(request, response)
            return
        }
        
        val clientIp = getClientIp(request)
        val bucket = buckets.computeIfAbsent(clientIp) { createBucket() }
        
        val consumption = bucket.tryConsume(1)
        
        if (consumption) {
            // Agregar headers de rate limit
            response.addHeader("X-RateLimit-Remaining", bucket.availableTokens.toString())
            response.addHeader("X-RateLimit-Limit", MAX_REQUESTS_PER_MINUTE.toString())
            filterChain.doFilter(request, response)
        } else {
            log.warn("Rate limit excedido para IP: {}", clientIp)
            
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"Too Many Requests","message":"Excediste el límite de solicitudes. Intenta de nuevo en un minuto."}""")
            
            // Headers de retry
            response.addHeader("Retry-After", "60")
            response.addHeader("X-RateLimit-Limit", MAX_REQUESTS_PER_MINUTE.toString())
            response.addHeader("X-RateLimit-Remaining", "0")
        }
    }

    private fun createBucket(): Bucket {
        val limitPerMinute = Bandwidth.builder()
            .capacity(MAX_REQUESTS_PER_MINUTE)
            .refillGreedy(MAX_REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
            .build()
            
        val limitPerHour = Bandwidth.builder()
            .capacity(MAX_REQUESTS_PER_HOUR)
            .refillGreedy(MAX_REQUESTS_PER_HOUR, Duration.ofHours(1))
            .build()
            
        return Bucket.builder()
            .addLimit(limitPerMinute)
            .addLimit(limitPerHour)
            .build()
    }

    /**
     * Verifica si la ruta está exenta de rate limiting
     */
    private fun isExemptPath(requestUri: String): Boolean {
        return requestUri.startsWith("/actuator/") ||
               requestUri.startsWith("/swagger-ui/") ||
               requestUri.startsWith("/v3/api-docs") ||
               requestUri == "/swagger-ui.html" ||
               requestUri.contains("/estadisticas")
    }

    private fun getClientIp(request: HttpServletRequest): String {
        // Primero intentar obtener IP real desde header de proxy
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (!xForwardedFor.isNullOrBlank()) {
            return xForwardedFor.split(",").first().trim()
        }
        
        val xRealIp = request.getHeader("X-Real-IP")
        if (!xRealIp.isNullOrBlank()) {
            return xRealIp
        }
        
        return request.remoteAddr ?: "unknown"
    }
}