package app.bloqueador.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class ErrorReportsTest {

    private fun fallo(): Throwable = try {
        throw IOException("no se pudo abrir https://banco-secreto.example/login?usuario=ana", UnknownHostException("banco-secreto.example"))
    } catch (e: IOException) {
        e
    }

    @Test
    fun `la traza nunca lleva el texto del mensaje, solo clases y lineas de Garbillo`() {
        val traza = ErrorReports.traza(fallo())
        val todo = traza.joinToString("\n")
        assertFalse("sin dominios ni datos", todo.contains("banco-secreto") || todo.contains("usuario") || todo.contains("ana"))
        assertTrue(traza.contains("clase java.io.IOException"))
        assertTrue(traza.contains("clase java.net.UnknownHostException"))
        assertTrue("dónde ocurrió, sin el prefijo del paquete", traza.any { it.startsWith("diag.ErrorReportsTest.fallo:") })
        assertTrue(traza.size <= ErrorReports.MAX_LINEAS + 4)
    }

    @Test
    fun `clean deja solo caracteres seguros y corta`() {
        assertEquals("HTTP 404", ErrorReports.clean("HTTP 404"))
        assertEquals("abc", ErrorReports.clean("a\"b\nc"))
        assertEquals(120, ErrorReports.clean("x".repeat(500)).length)
        assertEquals("vpn.DnsVpnService\$lambda.invoke:12", ErrorReports.clean("vpn.DnsVpnService\$lambda.invoke:12"))
    }

    @Test
    fun `los errores iguales se agrupan y hay un maximo de tipos`() {
        val a = ErrorEntry("cierre", "", listOf("clase java.lang.X", "vpn.A.b:1"), 1, "2026-11-15", "2026-11-15")
        var list = ErrorReports.merge(emptyList(), a)
        list = ErrorReports.merge(list, a.copy(ultimoDia = "2026-11-16"))
        assertEquals(1, list.size)
        assertEquals(2, list[0].veces)
        assertEquals("2026-11-15", list[0].primerDia)
        assertEquals("2026-11-16", list[0].ultimoDia)

        repeat(50) { list = ErrorReports.merge(list, a.copy(detalle = "d$it")) }
        assertEquals(ErrorReports.MAX_TIPOS, list.size)
    }

    @Test
    fun `detalle de descarga: solo el codigo HTTP o sin conexion`() {
        assertEquals("HTTP 404", ErrorReports.downloadDetail("Hagezi Pro: HTTP 404 en https://x/y"))
        assertEquals("sin_conexion", ErrorReports.downloadDetail("Unable to resolve host \"raw.githubusercontent.com\""))
        assertEquals("sin_conexion", ErrorReports.downloadDetail(null))
    }
}
