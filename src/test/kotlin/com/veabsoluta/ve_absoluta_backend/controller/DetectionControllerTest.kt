package com.veabsoluta.ve_absoluta_backend.controller

import com.veabsoluta.ve_absoluta_backend.model.Analisis
import com.veabsoluta.ve_absoluta_backend.service.AnalisisService
import com.veabsoluta.ve_absoluta_backend.service.CloudinaryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.multipart.MultipartFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests unitarios para DetectionController
 * 
 * Nota: Estos son tests de lógica pura. Los tests de integración
 * con MockMvc requerirían más configuración de Spring context.
 */
@ExtendWith(MockitoExtension::class)
class DetectionControllerTest {

    @Mock
    private lateinit var analisisService: AnalisisService
    
    @Mock
    private lateinit var cloudinaryService: CloudinaryService
    
    private lateinit var controller: DetectionController

    @BeforeEach
    fun setUp() {
        controller = DetectionController(analisisService, cloudinaryService)
    }

    @Test
    fun `validarArchivo acepta archivo JPEG válido`() {
        val file = MockMultipartFile(
            "file",
            "test.jpg",
            "image/jpeg",
            "fake image content".toByteArray()
        )
        
        // No debe lanzar excepción
        controller.validarArchivo(file)
    }

    @Test
    fun `validarArchivo acepta archivo PNG válido`() {
        val file = MockMultipartFile(
            "file",
            "test.png",
            "image/png",
            "fake image content".toByteArray()
        )
        
        controller.validarArchivo(file)
    }

    @Test
    fun `validarArchivo acepta archivo WebP válido`() {
        val file = MockMultipartFile(
            "file",
            "test.webp",
            "image/webp",
            "fake image content".toByteArray()
        )
        
        controller.validarArchivo(file)
    }

    @Test
    fun `validarArchivo rechaza archivo vacío`() {
        val file = MockMultipartFile(
            "file",
            "empty.jpg",
            "image/jpeg",
            ByteArray(0)
        )
        
        val exception = assertFailsWith<Exception> {
            controller.validarArchivo(file)
        }
        
        assertTrue(exception.message?.contains("vacío") == true)
    }

    @Test
    fun `validarArchivo rechaza tipo MIME inválido`() {
        val file = MockMultipartFile(
            "file",
            "test.exe",
            "application/exe",
            "fake content".toByteArray()
        )
        
        val exception = assertFailsWith<Exception> {
            controller.validarArchivo(file)
        }
        
        assertTrue(exception.message?.contains("no válido") == true)
    }

    @Test
    fun `validarArchivo rechaza archivo mayor a 10MB`() {
        // Crear archivo de más de 10MB (11MB)
        val largeContent = ByteArray(11 * 1024 * 1024)
        val file = MockMultipartFile(
            "file",
            "large.jpg",
            "image/jpeg",
            largeContent
        )
        
        val exception = assertFailsWith<Exception> {
            controller.validarArchivo(file)
        }
        
        assertTrue(exception.message?.contains("10MB") == true)
    }

    @Test
    fun `validarArchivo acepta archivo de exactamente 10MB`() {
        // Crear archivo de exactamente 10MB
        val tenMbContent = ByteArray(10 * 1024 * 1024)
        val file = MockMultipartFile(
            "file",
            "tenmb.jpg",
            "image/jpeg",
            tenMbContent
        )
        
        // No debe lanzar excepción
        controller.validarArchivo(file)
    }
}