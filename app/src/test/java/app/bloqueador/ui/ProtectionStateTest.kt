package app.bloqueador.ui

import app.bloqueador.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStateTest {

    private val running = VpnState.Running(loadingList = false)

    @Test
    fun `solo dice Protegido si hay listas, el DNS contesta y no hay DNS privado fijo`() {
        val ok = protectionOf(running, rules = 1000, dnsSilent = false, privateDnsFixed = false)
        assertEquals("Protegido", ok.title)
        assertFalse(ok.warning)
    }

    @Test
    fun `cada problema da un aviso distinto`() {
        val noLists = protectionOf(running, rules = 0, dnsSilent = false, privateDnsFixed = false)
        val silent = protectionOf(running, rules = 1000, dnsSilent = true, privateDnsFixed = false)
        val privateDns = protectionOf(running, rules = 1000, dnsSilent = false, privateDnsFixed = true)
        listOf(noLists, silent, privateDns).forEach { assertTrue(it.title, it.warning) }
        assertEquals(3, listOf(noLists, silent, privateDns).map { it.title }.toSet().size)
    }

    @Test
    fun `mientras carga o arranca no se avisa de nada`() {
        assertFalse(protectionOf(VpnState.Running(loadingList = true), 0, dnsSilent = true, privateDnsFixed = false).warning)
        assertFalse(protectionOf(VpnState.Starting, 0, dnsSilent = false, privateDnsFixed = false).warning)
        assertFalse(protectionOf(VpnState.Stopped, 0, dnsSilent = false, privateDnsFixed = false).warning)
        assertTrue(protectionOf(VpnState.Error("x"), 0, dnsSilent = false, privateDnsFixed = false).warning)
    }
}
