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
        assertEquals(app.bloqueador.UiText.res(app.bloqueador.R.string.state_protected), ok.title)
        assertFalse(ok.warning)
    }

    @Test
    fun `cada problema da un aviso distinto`() {
        val noLists = protectionOf(running, rules = 0, dnsSilent = false, privateDnsFixed = false)
        val silent = protectionOf(running, rules = 1000, dnsSilent = true, privateDnsFixed = false)
        val privateDns = protectionOf(running, rules = 1000, dnsSilent = false, privateDnsFixed = true)
        listOf(noLists, silent, privateDns).forEach { assertTrue(it.title.toString(), it.warning) }
        assertEquals(3, listOf(noLists, silent, privateDns).map { it.title }.toSet().size)
    }

    @Test
    fun `mientras carga o arranca no se avisa de nada`() {
        assertFalse(protectionOf(VpnState.Running(loadingList = true), 0, dnsSilent = true, privateDnsFixed = false).warning)
        assertFalse(protectionOf(VpnState.Starting, 0, dnsSilent = false, privateDnsFixed = false).warning)
        assertFalse(protectionOf(VpnState.Stopped, 0, dnsSilent = false, privateDnsFixed = false).warning)
        assertTrue(protectionOf(VpnState.Error(app.bloqueador.UiText.raw("x")), 0, dnsSilent = false, privateDnsFixed = false).warning)
    }

    @Test
    fun `con "Bloquear conexiones sin VPN" no dice Protegido`() {
        val p = protectionOf(VpnState.Running(loadingList = false), 1000, dnsSilent = false, privateDnsFixed = false, lockdown = true)
        assertTrue(p.warning)
        org.junit.Assert.assertEquals(app.bloqueador.UiText.res(app.bloqueador.R.string.state_no_internet), p.title)
    }

    @Test
    fun `DNS privado fijo y bloqueado por las listas avisa de que no hay internet, no de que se salta el filtro`() {
        val bypass = protectionOf(running, rules = 1000, dnsSilent = false, privateDnsFixed = true)
        val blocked = protectionOf(running, rules = 1000, dnsSilent = false, privateDnsFixed = true, privateDnsBlocked = true)
        assertEquals(app.bloqueador.UiText.res(app.bloqueador.R.string.state_no_effect_text), bypass.detail)
        assertEquals(app.bloqueador.UiText.res(app.bloqueador.R.string.state_private_dns_blocked_text), blocked.detail)
        assertEquals(app.bloqueador.UiText.res(app.bloqueador.R.string.state_no_internet), blocked.title)
        assertTrue(blocked.warning)
    }
}
