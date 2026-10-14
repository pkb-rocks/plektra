/*
 * Vendored from Shizuku (https://github.com/RikkaApps/Shizuku) by RikkaApps.
 * Licensed under the Apache License, Version 2.0. See
 * app/src/main/java/moe/shizuku/manager/adb/NOTICE and third_party/licenses/Apache-2.0.txt.
 */
package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

private const val TAG = "AdbClient"

/** Refused or dropped connects should fail fast; the broker is always on localhost. */
private const val CONNECT_TIMEOUT_MS = 5_000

/** A phone that never authorises the key goes quiet rather than replying. */
private const val READ_TIMEOUT_MS = 10_000

/** Largest payload the ADB protocol allows (AOSP MAX_PAYLOAD, 1 MiB); a larger declared length is rejected. */
private const val MAX_PAYLOAD = 1024 * 1024

/**
 * Most shell output one command may return (adversary review 2026-10-12, finding 1.2). Every
 * broker command prints a few lines at most; a peer streaming without end must not grow the
 * buffer until the keyboard process dies of OOM.
 */
const val MAX_SHELL_OUTPUT_BYTES = 256 * 1024

/**
 * Longest one client may live, connect and commands together. The per-read timeout cannot catch
 * a peer that trickles a byte every few seconds, and the caller holds the broker lock meanwhile.
 */
const val CLIENT_DEADLINE_MS = 30_000L

/** Closes sockets whose deadline passed; one daemon thread for every client. */
private val deadlineTimer = ScheduledThreadPoolExecutor(1) { r ->
    Thread(r, "AdbClient-deadline").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

/**
 * @param sslContextFor builds the TLS context around the trust manager this client pins adbd with
 *   (normally [AdbKey.sslContext]).
 * @param pins adbd's server-certificate pins; see [AdbServerPinStore].
 * @param deadlineMs overall bound on connect plus commands; the socket is closed when it passes.
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val sslContextFor: (X509TrustManager) -> SSLContext,
    private val pins: AdbServerPinStore,
    private val deadlineMs: Long = CLIENT_DEADLINE_MS,
) : Closeable {

    constructor(host: String, port: Int, key: AdbKey, pins: AdbServerPinStore) :
        this(host, port, key::sslContext, pins)

    @Volatile
    private var deadlinePassed = false
    private var deadline: ScheduledFuture<*>? = null

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private var nextLocalId = 1

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() = withinDeadline {
        // Bound both phases. A phone that never accepts our key simply stops answering, and an
        // unbounded read would then block this thread forever while holding the broker lock,
        // deadlocking every later privileged call rather than failing.
        socket = Socket()
        deadline = deadlineTimer.schedule({
            deadlinePassed = true
            close()
        }, deadlineMs, TimeUnit.MILLISECONDS)
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        socket.tcpNoDelay = true
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        // Wireless debugging (the only adbd this client is pointed at) is TLS-only. Never answer
        // the legacy A_AUTH token challenge: signing a peer's token is a signing oracle, because
        // a fake adbd can relay the token from adbd's legacy TCP port and get a shell with our
        // signature (adversary review 2026-10-12, finding 1.1).
        if (message.command != A_STLS) error("adbd did not offer TLS")
        write(A_STLS, A_STLS_VERSION, 0)

        val trustManager = PinnedAdbServerTrustManager(pins)
        tlsSocket = sslContextFor(trustManager).socketFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            tlsSocket.startHandshake()
        } catch (e: IOException) {
            if (trustManager.mismatchSeen) throw AdbServerPinMismatchException(e)
            throw e
        }
        Log.d(TAG, "Handshake succeeded.")

        tlsInputStream = DataInputStream(tlsSocket.inputStream)
        tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
        useTls = true

        message = read()
        if (message.command != A_CNXN) error("not A_CNXN")
        trustManager.commitFirstUse()
    }

    /**
     * Runs [command] and hands its output to [listener]. Fails, closing the connection, once the
     * output passes [maxOutputBytes] or the client's deadline passes.
     */
    fun shellCommand(
        command: String,
        maxOutputBytes: Int = MAX_SHELL_OUTPUT_BYTES,
        listener: ((ByteArray) -> Unit)?,
    ) = withinDeadline {
        // One stream per command, so a broker session can run several commands on one connection.
        val localId = nextLocalId++
        var outputBytes = 0L
        write(A_OPEN, localId, 0, "shell:$command")

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        outputBytes += message.data_length.coerceAtLeast(0)
                        if (outputBytes > maxOutputBytes) {
                            close()
                            error("shell output exceeded $maxOutputBytes bytes")
                        }
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("not A_WRTE or A_CLSE")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("not A_OKAY or A_CLSE")
            }
        }
    }

    /** Reports a socket the deadline closed as that, not as whatever IOException surfaced. */
    private inline fun <T> withinDeadline(block: () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            if (deadlinePassed) throw IOException("adbd did not finish within $deadlineMs ms", e)
            throw e
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data: ByteArray?
        if (dataLength > MAX_PAYLOAD) {
            // The peer controls this field; never allocate an attacker-chosen buffer.
            error("declared payload length $dataLength exceeds the ADB maximum $MAX_PAYLOAD")
        }
        if (dataLength >= 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        deadline?.cancel(false)
        try {
            plainInputStream.close()
        } catch (e: Throwable) {
        }
        try {
            plainOutputStream.close()
        } catch (e: Throwable) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }

        if (useTls) {
            try {
                tlsInputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsSocket.close()
            } catch (e: Exception) {
            }
        }
    }
}