package app.bloqueador.vpn

import app.bloqueador.dns.DnsMessage
import app.bloqueador.dns.IpPacket
import app.bloqueador.dns.UdpPacket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * El cliente DoT contra un servidor TLS en la propia máquina, con un certificado de prueba para
 * "dot.test" (src/test/resources/dot-test.p12, solo para tests).
 */
class DotResolverTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val replies = LinkedBlockingQueue<ByteArray>()
    private var server: SSLServerSocket? = null
    private var resolver: DotResolver? = null
    private val connections = AtomicInteger()

    private val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply {
        DotResolverTest::class.java.classLoader!!.getResourceAsStream("dot-test.p12").use { load(it, "prueba".toCharArray()) }
    }

    private fun context(): SSLContext = SSLContext.getInstance("TLS").apply {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "prueba".toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        init(kmf.keyManagers, tmf.trustManagers, null)
    }

    /**
     * Servidor DoT falso: lee [batch] consultas de cada conexión y las responde en orden INVERSO.
     * Si [closeAfter] > 0, cierra la conexión tras responder ese número de consultas.
     */
    private fun startServer(batch: Int = 1, closeAfter: Int = 0): Int {
        val s = context().serverSocketFactory.createServerSocket(0, 10, loopback) as SSLServerSocket
        server = s
        Thread {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                Thread {
                    runCatching {
                        c.use {
                            val input = DataInputStream(c.getInputStream())
                            val out = DataOutputStream(c.getOutputStream())
                            var answered = 0
                            while (true) {
                                val queries = (1..batch).map { ByteArray(input.readUnsignedShort()).also(input::readFully) }
                                for (q in queries.reversed()) {
                                    DnsMessage.put16(q, 2, 0x8180)
                                    out.writeShort(q.size); out.write(q); out.flush()
                                    if (closeAfter > 0 && ++answered >= closeAfter) return@runCatching
                                }
                            }
                        }
                    }
                }.start()
            }
        }.start()
        return s.localPort
    }

    private fun start(port: Int, hostname: String = "dot.test") =
        DotResolver(hostname, listOf(loopback), { true }, { replies.add(it) }, port, context().socketFactory as SSLSocketFactory)
            .also { it.start(); resolver = it }

    private fun request(name: String, id: Int, clientPort: Int = 40000): UdpPacket {
        val q = DnsForwarderTest.dnsQuery(name, id)
        val raw = IpPacket.buildUdp(4, byteArrayOf(10, 111, 222.toByte(), 1), byteArrayOf(10, 111, 222.toByte(), 2), clientPort, 53, q)
        return IpPacket.parseUdp(raw, raw.size)!!
    }

    private fun nextReply(): UdpPacket? = replies.poll(5, TimeUnit.SECONDS)?.let { IpPacket.parseUdp(it, it.size) }

    private var cleanup: () -> Unit = {}

    @After
    fun tearDown() {
        cleanup()
        resolver?.stop()
        server?.close()
    }

    @Test
    fun `resuelve por TLS varias consultas en vuelo aunque lleguen desordenadas`() {
        val r = start(startServer(batch = 2))
        r.forward(request("uno.example", 0x1111, 41001), "uno.example")
        r.forward(request("dos.example", 0x2222, 41002), "dos.example")
        val got = listOfNotNull(nextReply(), nextReply())
        assertEquals(2, got.size)
        // Cada respuesta vuelve a su cliente con su identificador original.
        val byPort = got.associate { it.destinationPort to DnsMessage.u16(it.payload(), 0) }
        assertEquals(mapOf(41001 to 0x1111, 41002 to 0x2222), byPort)
        assertEquals(0, r.inFlight)
        assertEquals("una sola conexión para las dos", 1, connections.get())
    }

    @Test
    fun `un certificado que no es del servicio se rechaza y solo se entrega SERVFAIL`() {
        val r = start(startServer(), hostname = "otro.test")
        r.forward(request("banco.example", 7), "banco.example")
        val reply = nextReply()!!.payload()
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.u16(reply, 2) and 0xF)
        assertEquals("ninguna respuesta del servidor falso", 0, DnsMessage.u16(reply, 6))
    }

    @Test
    fun `si el servidor cierra la conexion, la siguiente consulta abre otra`() {
        val r = start(startServer(closeAfter = 1))
        r.forward(request("a.example", 1), "a.example")
        assertNotNull(nextReply())
        Thread.sleep(300)
        r.forward(request("b.example", 2), "b.example")
        assertEquals(2, DnsMessage.u16(nextReply()!!.payload(), 0))
        assertTrue(connections.get() >= 2)
    }

    @Test
    fun `una app que inunda recibe SERVFAIL sin tocar la conexion`() {
        // Servidor que acepta la conexión y nunca contesta: las consultas se quedan en vuelo.
        val silent = java.net.ServerSocket(0, 10, loopback)
        val held = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        Thread { while (!silent.isClosed) runCatching { held.add(silent.accept()) }.onFailure { return@Thread } }.start()
        cleanup = { silent.close(); held.forEach { runCatching { it.close() } } }
        val r = DotResolver("dot.test", listOf(loopback), { true }, { replies.add(it) }, silent.localPort)
            .also { it.start(); resolver = it }
        repeat(DotResolver.MAX_PENDING_PER_APP) { r.forward(request("f$it.example", it), "f$it.example", "ruidosa") }
        r.forward(request("mas.example", 0x7777), "mas.example", "ruidosa")
        val servfail = nextReply()!!.payload()
        assertEquals(0x7777, DnsMessage.u16(servfail, 0))
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.u16(servfail, 2) and 0xF)
    }

    @Test
    fun `el router usa la red sin cifrar si esta desactivado o hay portal cautivo`() {
        val udp = Fake()
        var provider: DotProvider? = null
        var validated = true
        val created = mutableListOf<Fake>()
        val router = ResolverRouter(udp, { provider }, { validated }, { Fake().also(created::add) })

        assertSame(udp, router.current())
        provider = DotProvider.QUAD9
        validated = false
        assertSame("portal cautivo: DNS de la red", udp, router.current())
        validated = true
        val dot = router.current()
        assertSame(created.single(), dot)
        assertSame("se reutiliza", dot, router.current())
        provider = DotProvider.MULLVAD
        val other = router.current()
        assertTrue("servicio nuevo", other !== dot)
        assertTrue("el anterior se cierra", created.first().stopped)
    }

    private class Fake : DnsResolver {
        var stopped = false
        override fun start() {}
        override fun stop() { stopped = true }
        override fun forward(request: UdpPacket, domain: String, app: String?) {}
        override val inFlight = 0
        override fun isUnresponsive(now: Long) = false
    }
}
