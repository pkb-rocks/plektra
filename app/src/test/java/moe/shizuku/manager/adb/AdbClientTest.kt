package moe.shizuku.manager.adb

import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * AdbClient against a fake adbd on a local ServerSocket (adversary review 2026-10-12, 1.1, 1.2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AdbClientTest {

    private class MemoryPins(
        override var pairingPin: String? = null,
        override var connectPin: String? = null,
    ) : AdbServerPinStore {
        override fun recordPairing(pin: String) {
            pairingPin = pin
            connectPin = null
        }

        override fun storeConnectPin(pin: String) {
            connectPin = pin
        }

        override fun clear() {
            pairingPin = null
            connectPin = null
        }
    }

    private class Identity(val keyPair: KeyPair, val cert: X509Certificate) {
        fun keyManagers() = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null)
                setKeyEntry("k", keyPair.private, PASSWORD, arrayOf(cert))
            }
            init(store, PASSWORD)
        }.keyManagers
    }

    companion object {
        private val PASSWORD = "test".toCharArray()

        private fun identity(cn: String): Identity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val holder = X509v3CertificateBuilder(
                X500Name("CN=$cn"), BigInteger.ONE, Date(0), Date(4_000_000_000_000L), Locale.ROOT,
                X500Name("CN=$cn"), SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
            return Identity(keyPair, cert)
        }

        private val server by lazy { identity("adbd") }
        private val impostor by lazy { identity("impostor") }
        private val client by lazy { identity("client") }
    }

    private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val peerError = AtomicReference<Throwable?>()
    private var peer: Thread? = null

    @Before
    fun generateKeysOutsideTheDeadline() {
        listOf(server, impostor, client).forEach { it.cert }
    }

    @After
    fun tearDown() {
        serverSocket.close()
        peer?.join(5_000)
    }

    // ---- wire helpers ------------------------------------------------------------------------

    private fun readMessage(input: InputStream): AdbMessage {
        val data = DataInputStream(input)
        val header = ByteArray(AdbMessage.HEADER_LENGTH)
        data.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        val crc = b.int
        val magic = b.int
        val payload = ByteArray(length)
        data.readFully(payload)
        return AdbMessage(command, arg0, arg1, length, crc, magic, payload)
    }

    private fun send(output: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        output.write(AdbMessage(command, arg0, arg1, data).toByteArray())
        output.flush()
    }

    /** Accepts one connection on a background thread and runs [script] on it. */
    private fun startPeer(script: (Socket) -> Unit) {
        peer = Thread {
            try {
                serverSocket.accept().use { script(it) }
            } catch (t: Throwable) {
                peerError.set(t)
            }
        }.apply { isDaemon = true; start() }
    }

    /** The real handshake up to the TLS session: CNXN, STLS, then TLS with [identity]'s cert. */
    private fun tlsHandshake(socket: Socket, identity: Identity = server): SSLSocket {
        socket.soTimeout = 10_000
        assertEquals(A_CNXN, readMessage(socket.getInputStream()).command)
        send(socket.getOutputStream(), A_STLS, A_STLS_VERSION, 0)
        assertEquals(A_STLS, readMessage(socket.getInputStream()).command)
        val context = SSLContext.getInstance("TLSv1.3").apply {
            init(identity.keyManagers(), arrayOf(AcceptAnyClient), null)
        }
        val tls = context.socketFactory.createSocket(socket, "127.0.0.1", socket.port, true) as SSLSocket
        tls.useClientMode = false
        tls.wantClientAuth = true
        tls.startHandshake()
        return tls
    }

    /** CNXN over TLS, then accept the OPEN and answer OKAY. */
    private fun openShell(tls: SSLSocket) {
        send(tls.outputStream, A_CNXN, A_VERSION, A_MAXDATA, "device::\u0000".toByteArray())
        assertEquals(A_OPEN, readMessage(tls.inputStream).command)
        send(tls.outputStream, A_OKAY, REMOTE_ID, 1)
    }

    private object AcceptAnyClient : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun tlsClient(pins: AdbServerPinStore, deadlineMs: Long = CLIENT_DEADLINE_MS) =
        AdbClient("127.0.0.1", serverSocket.localPort, { tm ->
            SSLContext.getInstance("TLSv1.3").apply { init(client.keyManagers(), arrayOf(tm), null) }
        }, pins, deadlineMs)

    // ---- 1.1: never answer A_AUTH --------------------------------------------------------------

    @Test
    fun peerAnsweringAuthTokenGetsNoSignatureAndConnectFails() {
        val afterToken = AtomicReference<ByteArray>()
        startPeer { socket ->
            socket.soTimeout = 10_000
            assertEquals(A_CNXN, readMessage(socket.getInputStream()).command)
            // What a fake adbd relaying adbd's legacy-port challenge would send.
            send(socket.getOutputStream(), A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20) { it.toByte() })
            afterToken.set(socket.getInputStream().readBytes())
        }

        val adb = AdbClient("127.0.0.1", serverSocket.localPort, { _ ->
            fail("TLS must not start when adbd did not offer it")
            throw AssertionError()
        }, MemoryPins())
        try {
            adb.connect()
            fail("connect() must fail when the peer answers A_AUTH")
        } catch (e: IllegalStateException) {
            assertEquals("adbd did not offer TLS", e.message)
        } finally {
            adb.close()
        }

        peer!!.join(5_000)
        assertNull(peerError.get())
        assertEquals("the client sent bytes after the AUTH token", 0, afterToken.get().size)
    }

    // ---- 1.2a: pinning ------------------------------------------------------------------------

    @Test
    fun firstConnectWithoutConnectPinPinsTheServerCert() {
        startPeer { socket ->
            val tls = tlsHandshake(socket)
            send(tls.outputStream, A_CNXN, A_VERSION, A_MAXDATA, "device::\u0000".toByteArray())
            tls.inputStream.readBytes()
        }
        val pins = MemoryPins(pairingPin = AdbServerPin.of(server.cert))

        tlsClient(pins).use { it.connect() }

        assertEquals(AdbServerPin.of(server.cert), pins.connectPin)
    }

    @Test
    fun firstUseIsNotPinnedUntilTheAdbHandshakeCompletes() {
        startPeer { socket -> tlsHandshake(socket).close() }
        val pins = MemoryPins()

        tlsClient(pins).use { adb ->
            try {
                adb.connect()
                fail("connect() must fail without CNXN")
            } catch (_: Exception) {
            }
        }

        assertNull(pins.connectPin)
    }

    @Test
    fun serverCertNotMatchingThePinIsRefusedDuringTheHandshake() {
        val sawClientCert = AtomicReference<Boolean>()
        startPeer { socket ->
            try {
                val tls = tlsHandshake(socket, impostor)
                sawClientCert.set(runCatching { tls.session.peerCertificates.isNotEmpty() }.getOrDefault(false))
            } catch (_: IOException) {
                sawClientCert.set(false)
            }
        }
        val pin = AdbServerPin.of(server.cert)
        val pins = MemoryPins(connectPin = pin)

        tlsClient(pins).use { adb ->
            try {
                adb.connect()
                fail("connect() must refuse a server cert that does not match the pin")
            } catch (e: IOException) {
                // A distinct type, so the status can say "adbd's certificate changed".
                assertTrue(e.toString(), e is AdbServerPinMismatchException)
            }
        }

        assertEquals(pin, pins.connectPin)
        peer!!.join(5_000)
        assertFalse("the impostor received our client certificate", sawClientCert.get() ?: false)
    }

    @Test
    fun serverCertMatchingThePinIsAcceptedAndCommandOutputReturned() {
        startPeer { socket ->
            val tls = tlsHandshake(socket)
            openShell(tls)
            send(tls.outputStream, A_WRTE, REMOTE_ID, 1, "pastiera_verify\n".toByteArray())
            send(tls.outputStream, A_CLSE, REMOTE_ID, 1)
            tls.inputStream.readBytes()
        }
        val pins = MemoryPins(connectPin = AdbServerPin.of(server.cert))
        val out = StringBuilder()

        tlsClient(pins).use { adb ->
            adb.connect()
            adb.shellCommand("echo pastiera_verify") { out.append(String(it)) }
        }

        assertEquals("pastiera_verify\n", out.toString())
    }

    // ---- broker sessions: several commands, one connection ---------------------------------

    @Test
    fun twoCommandsRunOnOneConnectionAsSeparateStreams() {
        val opened = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        startPeer { socket ->
            val tls = tlsHandshake(socket)
            send(tls.outputStream, A_CNXN, A_VERSION, A_MAXDATA, "device::\u0000".toByteArray())
            repeat(2) { i ->
                val open = readMessage(tls.inputStream)
                assertEquals(A_OPEN, open.command)
                opened += open.arg0 to String(open.data!!).trimEnd('\u0000')
                send(tls.outputStream, A_OKAY, REMOTE_ID + i, open.arg0)
                send(tls.outputStream, A_WRTE, REMOTE_ID + i, open.arg0, "out$i\n".toByteArray())
                send(tls.outputStream, A_CLSE, REMOTE_ID + i, open.arg0)
                // The client acknowledges the write, then answers the close.
                while (readMessage(tls.inputStream).command != A_CLSE) Unit
            }
            tls.inputStream.readBytes()
        }
        val outputs = mutableListOf<String>()

        tlsClient(MemoryPins()).use { adb ->
            adb.connect()
            listOf("echo a", "echo b").forEach { command ->
                val out = StringBuilder()
                adb.shellCommand(command) { out.append(String(it)) }
                outputs += out.toString()
            }
        }

        peer!!.join(5_000)
        assertNull(peerError.get())
        assertEquals(listOf("out0\n", "out1\n"), outputs)
        assertEquals(listOf("shell:echo a", "shell:echo b"), opened.map { it.second })
        assertEquals("each command needs its own local stream id", 2, opened.map { it.first }.toSet().size)
    }

    // ---- 1.3d: a key handle with no encoding still authenticates ---------------------------

    @Test
    fun keystoreStyleOpaqueKeyCompletesTheTlsHandshakeAsTheClient() {
        val material = SoftwareAdbKeySource.newMaterial()
        assertNull(material.privateKey.encoded)
        val key = AdbKey(material, "plektra")
        val seenClient = AtomicReference<java.security.PublicKey?>()
        startPeer { socket ->
            val tls = tlsHandshake(socket)
            seenClient.set(runCatching { tls.session.peerCertificates.first().publicKey }.getOrNull())
            openShell(tls)
            send(tls.outputStream, A_WRTE, REMOTE_ID, 1, "pastiera_verify\n".toByteArray())
            send(tls.outputStream, A_CLSE, REMOTE_ID, 1)
            tls.inputStream.readBytes()
        }
        val out = StringBuilder()

        AdbClient("127.0.0.1", serverSocket.localPort, key::sslContext, MemoryPins()).use { adb ->
            adb.connect()
            adb.shellCommand("echo pastiera_verify") { out.append(String(it)) }
        }

        assertEquals("pastiera_verify\n", out.toString())
        assertEquals(material.publicKey, seenClient.get())
    }

    // ---- 1.2b: output cap and deadline --------------------------------------------------------

    @Test
    fun endlessOutputIsCutOffAtTheCap() {
        startPeer { socket ->
            val tls = tlsHandshake(socket)
            openShell(tls)
            val chunk = ByteArray(64 * 1024)
            try {
                while (true) send(tls.outputStream, A_WRTE, REMOTE_ID, 1, chunk)
            } catch (_: IOException) {
                // The client hung up, as it should.
            }
        }
        var received = 0L

        tlsClient(MemoryPins()).use { adb ->
            adb.connect()
            try {
                adb.shellCommand("echo hi") { received += it.size }
                fail("shellCommand must fail once the output passes the cap")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!, e.message!!.contains("exceeded"))
            }
        }

        assertTrue("received $received bytes", received <= MAX_SHELL_OUTPUT_BYTES)
    }

    @Test
    fun tricklingPeerIsCutOffAtTheDeadline() {
        startPeer { socket ->
            val tls = tlsHandshake(socket)
            openShell(tls)
            try {
                // Each message arrives well inside the per-read timeout, forever.
                while (true) {
                    send(tls.outputStream, A_WRTE, REMOTE_ID, 1, "x".toByteArray())
                    Thread.sleep(100)
                }
            } catch (_: IOException) {
            }
        }
        val started = System.nanoTime()

        tlsClient(MemoryPins(), deadlineMs = 1_500).use { adb ->
            adb.connect()
            try {
                adb.shellCommand("echo hi") { }
                fail("shellCommand must fail at the deadline")
            } catch (e: IOException) {
                assertTrue(e.message!!, e.message!!.contains("did not finish"))
            }
        }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 8_000)
    }

    @Test
    fun silentPeerIsNotWaitedOnPastTheDeadline() {
        startPeer { socket ->
            socket.soTimeout = 10_000
            readMessage(socket.getInputStream())
            try {
                socket.getInputStream().readBytes()
            } catch (_: SocketTimeoutException) {
            }
        }

        AdbClient("127.0.0.1", serverSocket.localPort, { _ -> throw AssertionError() }, MemoryPins(), 500)
            .use { adb ->
                try {
                    adb.connect()
                    fail("connect() must fail at the deadline")
                } catch (e: IOException) {
                    assertTrue(e.message!!, e.message!!.contains("did not finish"))
                }
            }
    }

    private val REMOTE_ID = 7
}
