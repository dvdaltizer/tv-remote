package com.slowpixels.tvremote.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** This phone's identity as a remote: a pairing ID and an Ed25519 key, made once and kept. */
class Identity(val pairingId: String, val seed: ByteArray) {
    val publicKey: ByteArray get() = Crypto.publicKey(seed)
}

/** What pairing with one Apple TV leaves: its identifier and public key. */
class Pairing(val id: String, val publicKey: ByteArray)

/**
 * A stored pairing used to verify the Apple TV on every connection. Records made
 * by 0.1.0 kept only the key ([tvId] is null): the signature is still checked
 * against that key, and the identifier it proves is then recorded.
 */
class StoredPairing(val tvId: String?, val tvKey: ByteArray)

/** A message the Apple TV pushed (not a reply): "_tiStarted", "SystemStatus"… */
class TvEvent(val name: String, val content: Map<*, *>)

/** Why a command failed, and whether the Apple TV may still have acted on it. */
sealed class CommandFailure(message: String, val maybeDelivered: Boolean) : IOException(message)
/** Not connected: nothing was sent, so it's safe to reconnect and send it. */
class NotConnected : CommandFailure("Not connected", maybeDelivered = false)
/** Sent, but no reply in time or the connection dropped while waiting: it may or may not have happened. */
class NoReply(name: String) : CommandFailure("No reply to $name", maybeDelivered = true)
/** The Apple TV answered with an error. */
class Rejected(name: String, val reason: String) : CommandFailure("$name refused: $reason", maybeDelivered = false)
/** The device that answered isn't the Apple TV we paired with (or its proof is wrong). */
open class PeerRejected(reason: String) : IOException(reason)

/** Logging that's quiet off-device (in tests, android.util.Log isn't there). */
object Logs {
    var sink: (String) -> Unit = { msg -> runCatching { android.util.Log.i("TVRemote", msg) } }
    fun i(msg: String) = sink(msg)
}

/**
 * One connection to an Apple TV over Apple's Companion protocol (as pyatv
 * describes it): pair once with the PIN on the TV, then on every connection
 * prove both sides' identities (pair-verify) and talk over an encrypted channel.
 *
 * Once connected, one reader thread owns the socket's input: replies are
 * handed to the request waiting for that message ID, and everything the Apple
 * TV pushes goes to [events]. Writes are serialized. When the connection drops,
 * every waiting request fails and [closed] turns true; a Companion is never
 * reused after that (make a new one).
 */
class Companion(
    private val host: String,
    private val port: Int,
    private val me: Identity,
    private val replyTimeoutMs: Long = 5000,
    /** The client version this remote announces ("_sv"): pyatv's 170.18, or 600.41.1 as iOS 18 sends. */
    private val clientVersion: String = CLIENT_VERSION_PYATV,
) {
    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private val writeLock = Any()
    @Volatile private var sendKey: ByteArray? = null
    @Volatile private var receiveKey: ByteArray? = null
    private var sent = 0L
    private var received = 0L
    private var xid = (Crypto.random.nextInt(0xFFFF)).toLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Map<*, *>>>()
    // Buffered until read: an event that comes in before anyone listens (right after connecting) isn't lost.
    private val _events = kotlinx.coroutines.channels.Channel<TvEvent>(256, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val _closed = MutableStateFlow(false)

    /** What the Apple TV pushes, in order, for one reader. */
    val events: kotlinx.coroutines.flow.Flow<TvEvent> = _events.receiveAsFlow()
    val closed: StateFlow<Boolean> = _closed
    /** The Apple TV identifier proven by pair-verify. */
    var verifiedTvId: String? = null
        private set
    /** The reply to the opening text-input request (which field is active, if any). */
    var textStart: Map<*, *>? = null
        private set

    private fun open() {
        // The address is built outside `apply`: inside it, `port` would mean the new socket's own port (0).
        val address = InetSocketAddress(host, port)
        socket = Socket().apply { tcpNoDelay = true; connect(address, 5000); soTimeout = 0 }
        input = DataInputStream(socket!!.getInputStream())
        output = socket!!.getOutputStream()
    }

    /** Ends the connection; anything waiting for a reply fails (as possibly delivered). */
    fun close() {
        _closed.value = true
        runCatching { socket?.close() }
        val waiting = snapshotOf(pending.values) // the reader may be completing one of these right now
        pending.clear()
        waiting.forEach { it.completeExceptionally(IOException("Connection closed")) }
    }

    // region Frames

    private fun writeFrame(type: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val key = sendKey
            val body = if (key != null) {
                val header = byteArrayOf(type.toByte()) + length3(payload.size + 16)
                Crypto.seal(key, counterNonce(sent++), payload, header)
            } else payload
            output.write(byteArrayOf(type.toByte()) + length3(body.size) + body)
            output.flush()
        }
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val header = ByteArray(4).also { input.readFully(it) }
        val n = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val body = ByteArray(n).also { input.readFully(it) }
        val key = receiveKey
        val payload = if (key != null && header[0].toInt() == E_OPACK) Crypto.open(key, counterNonce(received++), body, header) else body
        return (header[0].toInt() and 0xFF) to payload
    }

    private fun length3(n: Int) = byteArrayOf((n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun counterNonce(n: Long) = ByteArray(12) { i -> if (i < 8) (n ushr (8 * i)).toByte() else 0 }

    /** Sends a pairing step and returns the TLV the Apple TV answers with (before encryption starts). */
    private fun authStep(type: Int, tlv: ByteArray, extra: Pair<String, Any>?): Map<Int, ByteArray> {
        socket?.soTimeout = 15000
        writeFrame(type, Opack.pack(if (extra != null) mapOf("_pd" to tlv, extra) else mapOf("_pd" to tlv)))
        while (true) {
            val (_, payload) = readFrame()
            val msg = Opack.unpack(payload) as? Map<*, *> ?: continue
            val pd = msg["_pd"] as? ByteArray ?: continue
            val out = Tlv8.read(pd)
            out[Tlv8.ERROR]?.let { e -> throw IOException(pairingError(e.firstOrNull()?.toInt() ?: 0)) }
            return out
        }
    }

    private fun pairingError(code: Int) = when (code) {
        2 -> "Wrong code. Try again."
        3 -> "The Apple TV is busy pairing. Wait a few seconds and try again."
        4 -> "The Apple TV has too many paired devices."
        5 -> "Too many tries. Wait a minute, then try again."
        6 -> "The Apple TV isn't ready to pair."
        else -> "Pairing failed (error $code)."
    }

    // endregion

    // region Pairing (once, with the PIN)

    private var pairingSalt = ByteArray(0)
    private var pairingB = ByteArray(0)

    /** Asks the Apple TV to show a PIN. */
    suspend fun startPairing() = withContext(Dispatchers.IO) {
        try {
            open()
            val m2 = authStep(PS_START, Tlv8.write(listOf(Tlv8.METHOD to byteArrayOf(0), Tlv8.STATE to byteArrayOf(1))), "_pwTy" to 1)
            pairingSalt = m2[Tlv8.SALT] ?: throw IOException("The Apple TV didn't start pairing.")
            pairingB = m2[Tlv8.PUBLIC_KEY] ?: throw IOException("The Apple TV didn't start pairing.")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** Finishes pairing with the PIN the Apple TV shows; returns what's needed to connect from now on. */
    suspend fun finishPairing(pin: String, name: String): Pairing = withContext(Dispatchers.IO) {
        try {
            val c = SrpClient(pin)
            val proof = c.proof(pairingSalt, pairingB)
            authStep(PS_NEXT, Tlv8.write(listOf(
                Tlv8.STATE to byteArrayOf(3),
                Tlv8.PUBLIC_KEY to org.bouncycastle.util.BigIntegers.asUnsignedByteArray(c.publicA),
                Tlv8.PROOF to proof,
            )), "_pwTy" to 1)

            val key = Crypto.hkdf(c.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
            val deviceX = Crypto.hkdf(c.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
            val id = me.pairingId.toByteArray()
            val signature = Crypto.sign(me.seed, deviceX + id + me.publicKey)
            val sub = Tlv8.write(listOf(
                Tlv8.IDENTIFIER to id,
                Tlv8.PUBLIC_KEY to me.publicKey,
                Tlv8.SIGNATURE to signature,
                Tlv8.NAME to Opack.pack(mapOf("name" to name)),
            ))
            val m6 = authStep(PS_NEXT, Tlv8.write(listOf(
                Tlv8.STATE to byteArrayOf(5),
                Tlv8.ENCRYPTED to Crypto.seal(key, Crypto.nonce("PS-Msg05"), sub),
            )), "_pwTy" to 1)
            val tv = Tlv8.read(Crypto.open(key, Crypto.nonce("PS-Msg06"), m6[Tlv8.ENCRYPTED] ?: throw IOException("Pairing didn't finish.")))
            val tvId = tv[Tlv8.IDENTIFIER] ?: throw IOException("Pairing didn't finish.")
            val tvKey = tv[Tlv8.PUBLIC_KEY]?.takeIf { it.size == 32 } ?: throw IOException("Pairing didn't finish.")
            Pairing(String(tvId), tvKey)
        } finally {
            close()
        }
    }

    // endregion

    // region Connecting (every time)

    /**
     * Connects, proves both identities (pair-verify), starts encryption and the
     * remote session. The Apple TV must sign with the key from pairing and give
     * the identifier we stored; anything else is [PeerRejected], and nothing more
     * is sent to it.
     */
    suspend fun connect(pairing: StoredPairing) = withContext(Dispatchers.IO) {
        try {
            DebugLog.add("connect $host:$port (client $clientVersion)")
            open()
            val eph = Crypto.Ephemeral()
            val m2 = authStep(PV_START, Tlv8.write(listOf(Tlv8.STATE to byteArrayOf(1), Tlv8.PUBLIC_KEY to eph.public)), "_auTy" to 4)
            val theirs = m2[Tlv8.PUBLIC_KEY]?.takeIf { it.size == 32 } ?: throw IOException("The Apple TV didn't answer.")
            val shared = eph.shared(theirs)
            val key = Crypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
            val sealed = m2[Tlv8.ENCRYPTED] ?: throw PeerRejected("The Apple TV didn't prove who it is.")
            val proof = runCatching { Tlv8.read(Crypto.open(key, Crypto.nonce("PV-Msg02"), sealed)) }
                .getOrElse { throw PeerRejected("The Apple TV's proof couldn't be read.") }
            val tvId = proof[Tlv8.IDENTIFIER] ?: throw PeerRejected("The Apple TV didn't say who it is.")
            val tvSignature = proof[Tlv8.SIGNATURE] ?: throw PeerRejected("The Apple TV didn't sign its proof.")
            if (pairing.tvId != null && String(tvId) != pairing.tvId) throw PeerRejected("This isn't the Apple TV you paired with.")
            if (!Crypto.verify(pairing.tvKey, theirs + tvId + eph.public, tvSignature)) {
                throw PeerRejected("This Apple TV's signature doesn't match the pairing.")
            }
            verifiedTvId = String(tvId)

            val id = me.pairingId.toByteArray()
            val signature = Crypto.sign(me.seed, eph.public + id + theirs)
            val sub = Tlv8.write(listOf(Tlv8.IDENTIFIER to id, Tlv8.SIGNATURE to signature))
            authStep(PV_NEXT, Tlv8.write(listOf(
                Tlv8.STATE to byteArrayOf(3),
                Tlv8.ENCRYPTED to Crypto.seal(key, Crypto.nonce("PV-Msg03"), sub),
            )), null)
            sendKey = Crypto.hkdf(shared, "", "ClientEncrypt-main")
            receiveKey = Crypto.hkdf(shared, "", "ServerEncrypt-main")
            socket?.soTimeout = 0
            startReader()

            request("_systemInfo", mapOf(
                "_bf" to 0, "_cf" to 512, "_clFl" to 128, "_i" to me.pairingId.take(12), "_idsID" to me.pairingId,
                "_pubID" to "aa:bb:cc:dd:ee:ff", "_sf" to 256, "_sv" to clientVersion, "model" to "iPhone14,2", "name" to "Light Phone",
            ))
            request("_sessionStart", mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to Crypto.random.nextInt(Int.MAX_VALUE).toLong()))
            // tvOS answers FetchAttentionState only once a TV Remote session is open (pyatv); older tvOS may not know it.
            runCatching { request("TVRCSessionStart", mapOf("ProtocolVersionKey" to "1.2")) }
            textStart = runCatching { request("_tiStart", emptyMap()) }.getOrNull()
            // Pushed power updates (pyatv subscribes to both names), which media controls work now ("_iMC"),
            // what's playing, and SupportedActions (sent on big screen changes; used only as a cue to ask again).
            event("_interest", mapOf("_regEvents" to listOf("SystemStatus", "TVSystemStatus", "_iMC", "NowPlayingInfo", "SupportedActions")))
            DebugLog.add("connected, verified ${verifiedTvId ?: "?"}")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun startReader() {
        val t = Thread({
            try {
                while (!_closed.value) {
                    val (type, payload) = readFrame()
                    if (type != E_OPACK) continue
                    val msg = runCatching { Opack.unpack(payload) as? Map<*, *> }.getOrNull() ?: continue
                    dispatch(msg)
                }
            } catch (e: Throwable) {
                DebugLog.add("connection ended: ${e::class.simpleName} ${e.message ?: ""}")
            } finally {
                close()
            }
        }, "companion-reader")
        t.isDaemon = true
        t.start()
    }

    /** A reply goes to whoever is waiting for its message ID (stale or unknown ones are dropped); an event to [events]. */
    internal fun dispatch(msg: Map<*, *>) {
        when ((msg["_t"] as? Number)?.toInt()) {
            T_EVENT -> {
                val name = msg["_i"] as? String ?: return
                DebugLog.add("⇐ $name ${DebugLog.summary(msg["_c"])}")
                _events.trySend(TvEvent(name, msg["_c"] as? Map<*, *> ?: emptyMap<Any, Any>()))
            }
            T_RESPONSE -> {
                val x = (msg["_x"] as? Number)?.toLong() ?: return
                pending.remove(x)?.complete(msg) ?: Logs.i("reply to unknown or late message $x dropped")
            }
        }
    }

    /**
     * Sends a request and waits for its reply. Throws [NotConnected] if nothing
     * was sent, [NoReply] if it was sent but no reply came (it may have happened),
     * and [Rejected] when the Apple TV answers with an error.
     */
    suspend fun request(name: String, content: Map<String, Any?>, timeoutMs: Long = replyTimeoutMs): Map<*, *> {
        if (_closed.value || sendKey == null) { DebugLog.add("→ $name not sent: not connected"); throw NotConnected() }
        val waiter = CompletableDeferred<Map<*, *>>()
        val x: Long
        val started = System.currentTimeMillis()
        DebugLog.add("→ $name ${DebugLog.summary(content)}")
        try {
            x = withContext(Dispatchers.IO) {
                synchronized(writeLock) {
                    val id = ++xid
                    pending[id] = waiter
                    writeFrame(E_OPACK, Opack.pack(mapOf("_i" to name, "_x" to id, "_t" to T_REQUEST, "_c" to content)))
                    id
                }
            }
        } catch (e: IOException) {
            DebugLog.add("← $name write failed: ${e.message}")
            close()
            throw NoReply(name)
        }
        val reply = try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } catch (e: IOException) {
            null
        } finally {
            pending.remove(x)
        } ?: run {
            DebugLog.add("← $name no reply after ${System.currentTimeMillis() - started} ms")
            throw NoReply(name)
        }
        val ms = System.currentTimeMillis() - started
        (reply["_em"] as? String)?.let { DebugLog.add("← $name refused in $ms ms: $it"); throw Rejected(name, it) }
        DebugLog.add("← $name ok in $ms ms ${DebugLog.summary(reply["_c"])}")
        return reply
    }

    /** Sends an event (no reply expected), as pyatv does for text input and subscriptions. */
    suspend fun event(name: String, content: Map<String, Any?>) {
        if (_closed.value || sendKey == null) { DebugLog.add("→ event $name not sent: not connected"); throw NotConnected() }
        DebugLog.add("→ event $name ${DebugLog.summary(content)}")
        try {
            withContext(Dispatchers.IO) {
                synchronized(writeLock) { writeFrame(E_OPACK, Opack.pack(mapOf("_i" to name, "_x" to ++xid, "_t" to T_EVENT, "_c" to content))) }
            }
        } catch (e: IOException) {
            close()
            throw NoReply(name)
        }
    }

    // endregion

    enum class Button(val code: Int) {
        Up(1), Down(2), Left(3), Right(4), Menu(5), Select(6), Home(7), VolumeUp(8), VolumeDown(9),
        Siri(10), Screensaver(11), Sleep(12), Wake(13), PlayPause(14), ChannelUp(15), ChannelDown(16), Guide(17),
    }

    companion object {
        const val PS_START = 3
        const val PS_NEXT = 4
        const val PV_START = 5
        const val PV_NEXT = 6
        const val E_OPACK = 8
        const val T_EVENT = 1
        const val T_REQUEST = 2
        const val T_RESPONSE = 3
        /** Media control commands (pyatv's MediaControlCommand). */
        const val SKIP_BY = 7
        const val GET_VOLUME = 5
        const val SET_VOLUME = 6
        const val GET_CAPTION_SETTINGS = 12
        const val SET_CAPTION_SETTINGS = 13
        const val CLIENT_VERSION_PYATV = "170.18"
        const val CLIENT_VERSION_IOS18 = "600.41.1"
    }
}
