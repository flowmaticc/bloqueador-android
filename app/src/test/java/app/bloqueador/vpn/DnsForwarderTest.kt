package app.bloqueador.vpn

import app.bloqueador.dns.DnsMessage
import app.bloqueador.dns.IpPacket
import app.bloqueador.dns.UdpPacket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * El reenviador contra un servidor DNS falso en la propia máquina: reenvío normal, respuestas
 * suplantadas, respuestas truncadas (TCP) y cambio de servidor cuando el preferido no contesta.
 */
class DnsForwarderTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private lateinit var server: DatagramSocket
    private val replies = LinkedBlockingQueue<ByteArray>()
    private var forwarder: DnsForwarder? = null
    private var tcp: ServerSocket? = null

    @Before
    fun setUp() {
        server = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 3000 }
    }

    @After
    fun tearDown() {
        forwarder?.stop()
        server.close()
        tcp?.close()
    }

    private fun start(upstream: List<InetAddress> = listOf(loopback)) = DnsForwarder(
        protect = { true },
        protectTcp = { true },
        upstream = { upstream },
        reply = { replies.add(it) },
        port = server.localPort,
    ).also { it.start(); forwarder = it }

    /** Consulta A de [name] con identificador [id], tal como llegaría por el túnel. */
    private fun request(name: String, id: Int, clientPort: Int = 40000): UdpPacket {
        val q = dnsQuery(name, id)
        val raw = IpPacket.buildUdp(4, byteArrayOf(10, 111, 222.toByte(), 1), byteArrayOf(10, 111, 222.toByte(), 2), clientPort, 53, q)
        return IpPacket.parseUdp(raw, raw.size)!!
    }

    private fun receiveAtServer(): DatagramPacket =
        DatagramPacket(ByteArray(2048), 2048).also { server.receive(it) }

    private fun answer(query: DatagramPacket, flags: Int = 0x8180, extra: ByteArray = ByteArray(0), from: DatagramSocket = server) {
        val data = query.data.copyOf(query.length) + extra
        DnsMessage.put16(data, 2, flags)
        from.send(DatagramPacket(data, data.size, query.address, query.port))
    }

    private fun payloadOf(replyPacket: ByteArray): ByteArray = IpPacket.parseUdp(replyPacket, replyPacket.size)!!.payload()

    @Test
    fun `reenvia la consulta y devuelve la respuesta con el identificador original`() {
        val f = start()
        f.forward(request("ejemplo.com", id = 0x1234, clientPort = 41000), "ejemplo.com")

        val q = receiveAtServer()
        assertEquals("ejemplo.com", DnsMessage.parseQuery(q.data, 0, q.length)!!.name)
        answer(q)

        val reply = replies.poll(3, TimeUnit.SECONDS)
        assertNotNull("debe llegar la respuesta", reply)
        val udp = IpPacket.parseUdp(reply!!, reply.size)!!
        assertEquals(0x1234, DnsMessage.u16(udp.payload(), 0))
        assertEquals(41000, udp.destinationPort)
        assertEquals(53, udp.sourcePort)
        assertEquals(0, f.inFlight)
    }

    @Test
    fun `los identificadores hacia el servidor no son predecibles ni repiten el del cliente`() {
        val f = start()
        val ids = (1..20).map {
            f.forward(request("a$it.ejemplo.com", id = 7), "a$it.ejemplo.com")
            DnsMessage.u16(receiveAtServer().data, 0)
        }
        assertEquals("todos distintos", 20, ids.toSet().size)
        assertFalse("no son consecutivos", ids.zipWithNext().all { (a, b) -> b == (a + 1) and 0xFFFF })
    }

    @Test
    fun `ignora respuestas que no vienen del servidor o no responden a la pregunta`() {
        val f = start()
        f.forward(request("banco.example", id = 1), "banco.example")
        val q = receiveAtServer()

        // 1) Mismo identificador pero desde otro puerto (un atacante en la red).
        DatagramSocket(InetSocketAddress(loopback, 0)).use { attacker -> answer(q, from = attacker) }
        // 2) Desde el servidor, pero respondiendo a otra pregunta.
        val other = dnsQuery("otro.example", DnsMessage.u16(q.data, 0))
        DnsMessage.put16(other, 2, 0x8180)
        server.send(DatagramPacket(other, other.size, q.address, q.port))
        // 3) Con el identificador correcto pero sin el bit de respuesta.
        answer(q, flags = 0x0100)

        assertNull("ninguna de las falsas se entrega", replies.poll(700, TimeUnit.MILLISECONDS))
        assertEquals(1, f.inFlight)

        answer(q) // la buena
        assertNotNull(replies.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun `una respuesta truncada se pide entera por TCP`() {
        val tcpServer = ServerSocket(server.localPort, 5, loopback).also { tcp = it }
        val big = ByteArray(1500) { 0x42 }
        Thread {
            runCatching {
                tcpServer.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val query = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                    val full = query + big
                    DnsMessage.put16(full, 2, 0x8180)
                    DataOutputStream(s.getOutputStream()).apply { writeShort(full.size); write(full); flush() }
                }
            }
        }.start()

        start().forward(request("grande.example", id = 0x0BAD), "grande.example")
        answer(receiveAtServer(), flags = 0x8380) // TC

        val payload = payloadOf(replies.poll(5, TimeUnit.SECONDS)!!)
        assertFalse("la respuesta entregada ya no está truncada", DnsForwarder.isTruncated(payload))
        assertEquals(0x0BAD, DnsMessage.u16(payload, 0))
        assertArrayEquals(big, payload.copyOfRange(payload.size - big.size, payload.size))
    }

    @Test
    fun `si TCP falla se entrega la respuesta truncada`() {
        start().forward(request("grande.example", id = 5), "grande.example")
        answer(receiveAtServer(), flags = 0x8380) // TC, y no hay servidor TCP escuchando
        val payload = payloadOf(replies.poll(8, TimeUnit.SECONDS)!!)
        assertTrue(DnsForwarder.isTruncated(payload))
        assertEquals(5, DnsMessage.u16(payload, 0))
    }

    @Test
    fun `si el servidor preferido no contesta se pasa al siguiente`() {
        // 127.0.0.2 no tiene a nadie escuchando en ese puerto; el segundo es el servidor falso.
        val f = start(listOf(InetAddress.getByName("127.0.0.2"), loopback))
        repeat(6) { f.forward(request("q$it.example", id = it), "q$it.example") }
        Thread.sleep(2700)
        f.forward(request("tras-el-cambio.example", id = 99), "tras-el-cambio.example")

        val q = receiveAtServer()
        assertEquals("tras-el-cambio.example", DnsMessage.parseQuery(q.data, 0, q.length)!!.name)
        answer(q)
        assertEquals(99, DnsMessage.u16(payloadOf(replies.poll(3, TimeUnit.SECONDS)!!), 0))
    }

    @Test
    fun `nunca acumula mas consultas en vuelo que el limite`() {
        val f = start(listOf(InetAddress.getByName("127.0.0.2"))) // nadie contesta
        repeat(DnsForwarder.MAX_PENDING + 200) { f.forward(request("q$it.example", id = it and 0xFFFF), "q$it.example") }
        assertEquals(DnsForwarder.MAX_PENDING, f.inFlight)
    }

    @Test
    fun `al cambiar de red se vuelve a empezar por el servidor preferido`() {
        var servers = listOf(InetAddress.getByName("127.0.0.2"), loopback)
        val f = DnsForwarder({ true }, { true }, { servers }, { replies.add(it) }, server.localPort)
            .also { it.start(); forwarder = it }
        repeat(6) { f.forward(request("q$it.example", id = it), "q$it.example") }
        Thread.sleep(2700)
        f.forward(request("cambio.example", id = 1), "cambio.example")
        assertEquals("cambio.example", DnsMessage.parseQuery(receiveAtServer().let { it.data.copyOf(it.length) })!!.name)

        // Nueva red cuyo preferido sí contesta: el siguiente envío va a él, no al "segundo".
        servers = listOf(loopback, InetAddress.getByName("127.0.0.3"))
        f.forward(request("nueva-red.example", id = 2), "nueva-red.example")
        assertEquals("nueva-red.example", DnsMessage.parseQuery(receiveAtServer().let { it.data.copyOf(it.length) })!!.name)
    }

    @Test
    fun `acepta errores sin seccion de pregunta y compara la pregunta sin distinguir mayusculas`() {
        val query = dnsQuery("Ejemplo.COM", 1)
        val sameLower = dnsQuery("ejemplo.com", 1).also { DnsMessage.put16(it, 2, 0x8180) }
        assertTrue(DnsForwarder.sameQuestion(query, sameLower))

        val servfailNoQuestion = ByteArray(12).also { DnsMessage.put16(it, 2, 0x8182) }
        assertTrue(DnsForwarder.sameQuestion(query, servfailNoQuestion))
        val okNoQuestion = ByteArray(12).also { DnsMessage.put16(it, 2, 0x8180) }
        assertFalse(DnsForwarder.sameQuestion(query, okNoQuestion))
    }

    companion object {
        /** Consulta DNS mínima: cabecera + una pregunta de tipo A. */
        fun dnsQuery(name: String, id: Int): ByteArray {
            val labels = name.split('.')
            val out = ByteArray(12 + labels.sumOf { it.length + 1 } + 1 + 4)
            DnsMessage.put16(out, 0, id)
            DnsMessage.put16(out, 2, 0x0100)
            DnsMessage.put16(out, 4, 1)
            var p = 12
            for (l in labels) {
                out[p++] = l.length.toByte()
                l.forEach { out[p++] = it.code.toByte() }
            }
            p++ // etiqueta raíz
            DnsMessage.put16(out, p, DnsMessage.TYPE_A)
            DnsMessage.put16(out, p + 2, DnsMessage.CLASS_IN)
            return out
        }
    }
}
