package com.veabsoluta.ve_absoluta_backend.service

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.repository.AnalisisRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argThat
import org.mockito.kotlin.whenever
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests unitarios para AnalisisService
 * 
 * Verifica:
 * - Manejo de respuestas exitosas del servicio IA
 * - Manejo de errores de comunicación
 * - Persistencia en base de datos
 */
@ExtendWith(MockitoExtension::class)
class AnalisisServiceTest {

    @Mock
    private lateinit var analisisRepository: AnalisisRepository
    
    @Mock
    private lateinit var webClientBuilder: WebClient.Builder
    
    @Mock
    private lateinit var webClient: WebClient
    
    @Mock
    private lateinit var requestBodyUriSpec: WebClient.RequestBodyUriSpec
    
    @Mock
    private lateinit var responseSpec: WebClient.ResponseSpec

    private lateinit var service: AnalisisService

    @BeforeEach
    fun setUp() {
        service = AnalisisService(analisisRepository, webClientBuilder)
    }

    @Test
    fun `ejecutarDeteccion guarda resultado en base de datos`() {
        // Given
        val urlImagen = "https://cloudinary.com/test.jpg"
        val nombreArchivo = "test.jpg"
        val expectedResponse = PythonResponse(prediction = "FAKE", confidence = 0.95)
        val expectedAnalisis = Analisis(
            id = 1L,
            nombreArchivo = nombreArchivo,
            rutaArchivo = urlImagen,
            prediccion = "FAKE",
            confianza = 0.95
        )

        // Mock WebClient builder chain
        whenever(webClientBuilder.baseUrl(any())).thenReturn(webClientBuilder)
        whenever(webClientBuilder.codecs(any())).thenReturn(webClientBuilder)
        whenever(webClientBuilder.build()).thenReturn(webClient)
        whenever(webClient.post()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.contentType(any())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.bodyValue(any())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.bodyToMono(PythonResponse::class.java)).thenReturn(Mono.just(expectedResponse))
        whenever(analisisRepository.save(any())).thenReturn(expectedAnalisis)

        // When
        val result = service.ejecutarDeteccion(urlImagen, nombreArchivo)

        // Then
        assertEquals("FAKE", result.prediccion)
        assertEquals(0.95, result.confianza)
    }

    @Test
    fun `ejecutarDeteccion lanza excepción cuando servicio IA no responde`() {
        // Given
        val urlImagen = "https://cloudinary.com/test.jpg"
        val nombreArchivo = "test.jpg"

        whenever(webClientBuilder.baseUrl(any())).thenReturn(webClientBuilder)
        whenever(webClientBuilder.codecs(any())).thenReturn(webClientBuilder)
        whenever(webClientBuilder.build()).thenReturn(webClient)
        whenever(webClient.post()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.contentType(any())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.bodyValue(any())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.bodyToMono(PythonResponse::class.java))
            .thenReturn(Mono.error(RuntimeException("Connection refused")))

        // When/Then
        val exception = assertFailsWith<AnalisisServiceException> {
            service.ejecutarDeteccion(urlImagen, nombreArchivo)
        }
        
        assertEquals(ErrorCode.IA_SERVICE_UNAVAILABLE, exception.codigo)
    }

    @Test
    fun `ejecutarDeteccion lanza excepción cuando respuesta es nula`() {
        // Given
        val urlImagen = "https://cloudinary.com/test.jpg"
        val nombreArchivo = "test.jpg"

        whenever(webClientBuilder.baseUrl(any())).thenReturn(webClientBuilder)
        whenever(webClientBuilder.codecs(any())).thenReturn(webClientBuilder)
        whenever(webClientBuilder.build()).thenReturn(webClient)
        whenever(webClient.post()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.contentType(any())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.bodyValue(any())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.bodyToMono(PythonResponse::class.java))
            .thenReturn(Mono.empty())

        // When/Then
        val exception = assertFailsWith<AnalisisServiceException> {
            service.ejecutarDeteccion(urlImagen, nombreArchivo)
        }
        
        assertEquals(ErrorCode.IA_SERVICE_ERROR, exception.codigo)
        assertTrue(exception.message?.contains("nula") == true)
    }

    @Test
    fun `ejecutarDeteccion lanza excepción cuando prediccion IA es desconocida`() {
        // Given
        val urlImagen = "https://cloudinary.com/test.jpg"
        val nombreArchivo = "test.jpg"

        whenever(webClientBuilder.baseUrl(any())).thenReturn(webClientBuilder)
        whenever(webClientBuilder.codecs(any())).thenReturn(webClientBuilder)
        whenever(webClientBuilder.build()).thenReturn(webClient)
        whenever(webClient.post()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.contentType(any())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.bodyValue(any())).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.bodyToMono(PythonResponse::class.java))
            .thenReturn(Mono.just(PythonResponse(prediction = "suspicious", confidence = 0.95)))

        // When/Then
        val exception = assertFailsWith<AnalisisServiceException> {
            service.ejecutarDeteccion(urlImagen, nombreArchivo)
        }

        assertEquals(ErrorCode.IA_SERVICE_ERROR, exception.codigo)
        assertTrue(exception.message?.contains("no reconocida") == true)
    }
}

// Helper functions para Mockito
private fun <T> any(): T = org.mockito.kotlin.any()