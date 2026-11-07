package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.*

import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in Apple TV on localhost: answers pair-verify as the real one does
 * (as pyatv describes it), then speaks encrypted Companion messages. Each
 * request goes to [reply]: return the `_c` content, [NO_REPLY] to stay silent,
 * [DROP] to hang up, or a map with "_em" to answer with an error.
 */
class FakeAppleTv(
    val tvId: String = "AA:BB:CC:DD:EE:FF",
    val seed: ByteArray = ByteArray(32) { 7 },
    /** Sign with another key (an impostor). */
    val signingSeed: ByteArray = seed,
    /** Say a different identifier (an impostor). */
    val claimedId: String = tvId,
    /** The code it "shows" while pairing. */
    val pin: String = "1234",
    /** A fixed port (for the stand); 0 picks a free one. */
    fixedPort: Int = 0,
) {
    /** The remotes that paired: their identifier and public key. */
    val paired = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    /** The names the remotes gave while pairing. */
    val pairedNames = CopyOnWriteArrayList<String>()
    val publicKey: ByteArray get() = Crypto.publicKey(seed)
    private val server = ServerSocket(fixedPort)
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<Pair<String, Map<*, *>>>()
    val events = CopyOnWriteArrayList<Pair<String, Map<*, *>>>()
    val connections = AtomicInteger()
    @Volatile var reply: (String, Map<*, *>) -> Any? = { _, _ -> emptyMap<String, Any>() }
    @Volatile var refuseConnections = 0
    private val clients = CopyOnWriteArrayList<Client>()

    init {
        Thread({
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                if (refuseConnections > 0) { refuseConnections--; s.close(); continue }
                val c = Client(s)
                clients += c
                Thread({ runCatching { c.run() }; s.close() }, "fake-tv").apply { isDaemon = true }.start()
            }
        }, "fake-tv-accept").apply { isDaemon = true }.start()
    }

    fun names() = requests.map { it.first }

    /** Pushes an event to every connected remote. */
    fun push(name: String, content: Map<String, Any?>) = clients.forEach { runCatching { it.send(mapOf("_i" to name, "_t" to 1, "_c" to content)) } }

    /** Sends an already-encoded OPACK message (a fixture) to every connected remote. */
    fun pushRaw(opack: ByteArray) = clients.forEach { runCatching { it.frame(8, opack) } }

    fun dropAll() = clients.forEach { runCatching { it.socket.close() } }

    fun close() { server.close(); dropAll() }

    private inner class Client(val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private var sendKey: ByteArray? = null
        private var receiveKey: ByteArray? = null
        private var sent = 0L
        private var received = 0L

        private fun nonce(n: Long) = ByteArray(12) { i -> if (i < 8) (n ushr (8 * i)).toByte() else 0 }
        private fun len3(n: Int) = byteArrayOf((n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

        @Synchronized fun frame(type: Int, payload: ByteArray) {
            val key = sendKey
            val body = if (key != null) Crypto.seal(key, nonce(sent++), payload, byteArrayOf(type.toByte()) + len3(payload.size + 16)) else payload
            output.write(byteArrayOf(type.toByte()) + len3(body.size) + body)
            output.flush()
        }

        fun send(msg: Map<String, Any?>) = frame(8, Opack.pack(msg))

        private fun read(): Pair<Int, ByteArray> {
            val h = ByteArray(4).also { input.readFully(it) }
            val n = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            val body = ByteArray(n).also { input.readFully(it) }
            val key = receiveKey
            return h[0].toInt() to (if (key != null) Crypto.open(key, nonce(received++), body, h) else body)
        }

        fun run() {
            val (type, first) = read()
            val m1 = Tlv8.read((Opack.unpack(first) as Map<*, *>)["_pd"] as ByteArray)
            if (type == com.slowpixels.tvremote.net.Companion.PS_START) return pairSetup(m1)
            // Pair-verify M1 → M2.
            val theirs = m1[Tlv8.PUBLIC_KEY]!!
            val eph = Crypto.Ephemeral()
            val shared = eph.shared(theirs)
            val key = Crypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
            val id = claimedId.toByteArray()
            val sub = Tlv8.write(listOf(Tlv8.IDENTIFIER to id, Tlv8.SIGNATURE to Crypto.sign(signingSeed, eph.public + id + theirs)))
            frame(6, Opack.pack(mapOf("_pd" to Tlv8.write(listOf(
                Tlv8.STATE to byteArrayOf(2), Tlv8.PUBLIC_KEY to eph.public,
                Tlv8.ENCRYPTED to Crypto.seal(key, Crypto.nonce("PV-Msg02"), sub),
            )))))
            // M3 → M4, then encryption.
            read()
            frame(6, Opack.pack(mapOf("_pd" to Tlv8.write(listOf(Tlv8.STATE to byteArrayOf(4))))))
            sendKey = Crypto.hkdf(shared, "", "ServerEncrypt-main")
            receiveKey = Crypto.hkdf(shared, "", "ClientEncrypt-main")
            while (true) {
                val msg = Opack.unpack(read().second) as Map<*, *>
                val name = msg["_i"] as String
                val content = msg["_c"] as? Map<*, *> ?: emptyMap<Any, Any>()
                if ((msg["_t"] as Number).toInt() == 1) { events += name to content; continue }
                requests += name to content
                when (val r = reply(name, content)) {
                    NO_REPLY -> {}
                    DROP -> { socket.close(); return }
                    is Map<*, *> -> if (r.containsKey("_em")) send(mapOf("_x" to msg["_x"], "_t" to 3, "_em" to r["_em"]))
                        else send(mapOf("_x" to msg["_x"], "_t" to 3, "_c" to r))
                }
            }
        }

        private fun pd(items: List<Pair<Int, ByteArray>>) = Opack.pack(mapOf("_pd" to Tlv8.write(items)))
        private fun sha512(vararg parts: ByteArray) = java.security.MessageDigest.getInstance("SHA-512").also { md -> parts.forEach(md::update) }.digest()
        private fun bytes(x: java.math.BigInteger) = org.bouncycastle.util.BigIntegers.asUnsignedByteArray(x)

        /**
         * Pair-setup, as the Apple TV does it (HomeKit's SRP-6a with SHA-512): M2 with the salt and B; M4 checks the
         * remote's proof of the PIN (a wrong one is error 2); M6 gives this TV's identifier and key, encrypted.
         */
        private fun pairSetup(m1: Map<Int, ByteArray>) {
            check(m1[Tlv8.STATE]?.firstOrNull()?.toInt() == 1)
            val group = org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups.rfc5054_3072
            val digest = org.bouncycastle.crypto.digests.SHA512Digest()
            val salt = ByteArray(16) { 0x5A }
            val identity = "Pair-Setup".toByteArray()
            val x = org.bouncycastle.crypto.agreement.srp.SRP6Util.calculateX(digest, group.n, salt, identity, pin.toByteArray())
            val server = org.bouncycastle.crypto.agreement.srp.SRP6Server().also { it.init(group, group.g.modPow(x, group.n), digest, Crypto.random) }
            val b = server.generateServerCredentials()
            frame(com.slowpixels.tvremote.net.Companion.PS_NEXT, pd(listOf(Tlv8.STATE to byteArrayOf(2), Tlv8.SALT to salt, Tlv8.PUBLIC_KEY to bytes(b))))
            val m3 = Tlv8.read((Opack.unpack(read().second) as Map<*, *>)["_pd"] as ByteArray)
            val a = java.math.BigInteger(1, m3[Tlv8.PUBLIC_KEY]!!)
            val key = sha512(bytes(server.calculateSecret(a)))
            val hn = sha512(bytes(group.n)); val hg = sha512(bytes(group.g))
            val expected = sha512(ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }, sha512(identity), salt, bytes(a), bytes(b), key)
            if (!expected.contentEquals(m3[Tlv8.PROOF])) {
                frame(com.slowpixels.tvremote.net.Companion.PS_NEXT, pd(listOf(Tlv8.STATE to byteArrayOf(4), Tlv8.ERROR to byteArrayOf(2))))
                socket.close(); return
            }
            frame(com.slowpixels.tvremote.net.Companion.PS_NEXT, pd(listOf(Tlv8.STATE to byteArrayOf(4), Tlv8.PROOF to sha512(bytes(a), expected, key))))
            val m5 = Tlv8.read((Opack.unpack(read().second) as Map<*, *>)["_pd"] as ByteArray)
            val sessionKey = Crypto.hkdf(key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
            val sub = Tlv8.read(Crypto.open(sessionKey, Crypto.nonce("PS-Msg05"), m5[Tlv8.ENCRYPTED]!!))
            val theirId = sub[Tlv8.IDENTIFIER]!!; val theirKey = sub[Tlv8.PUBLIC_KEY]!!
            val deviceX = Crypto.hkdf(key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
            check(Crypto.verify(theirKey, deviceX + theirId + theirKey, sub[Tlv8.SIGNATURE]!!)) { "the remote's signature is wrong" }
            paired += String(theirId) to theirKey
            sub[Tlv8.NAME]?.let { ((Opack.unpack(it) as? Map<*, *>)?.get("name") as? String)?.let(pairedNames::add) }
            val tvX = Crypto.hkdf(key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
            val id = claimedId.toByteArray()
            val mine = Tlv8.write(listOf(Tlv8.IDENTIFIER to id, Tlv8.PUBLIC_KEY to publicKey, Tlv8.SIGNATURE to Crypto.sign(signingSeed, tvX + id + publicKey)))
            frame(com.slowpixels.tvremote.net.Companion.PS_NEXT, pd(listOf(Tlv8.STATE to byteArrayOf(6), Tlv8.ENCRYPTED to Crypto.seal(sessionKey, Crypto.nonce("PS-Msg06"), mine))))
        }

    }
    companion object {
        val NO_REPLY = Any()
        val DROP = Any()

        /** A `_tiD` archive like the Apple TV's, for a field with [session] and [text]. */
        fun textArchive(session: ByteArray, text: String): ByteArray = BinaryPlist.write(mapOf(
            "\$version" to 100000,
            "\$archiver" to "RTIKeyedArchiver",
            "\$top" to mapOf("sessionUUID" to Uid(1), "documentState" to Uid(2)),
            "\$objects" to listOf("\$null", session, mapOf("docSt" to Uid(3)), mapOf("contextBeforeInput" to Uid(4)), text),
        ))
    }
}
