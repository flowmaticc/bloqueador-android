package app.bloqueador.dns

import app.bloqueador.vpn.DnsForwarder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TruncatedTest {
    private fun header(flags: Int) = ByteArray(12).also { it[2] = (flags shr 8).toByte(); it[3] = flags.toByte() }

    @Test
    fun `detecta el bit TC de una respuesta truncada`() {
        assertTrue(DnsForwarder.isTruncated(header(0x8380)))   // respuesta + TC + RD + RA
        assertFalse(DnsForwarder.isTruncated(header(0x8180)))  // respuesta normal
        assertFalse(DnsForwarder.isTruncated(ByteArray(5)))    // demasiado corta
    }
}
