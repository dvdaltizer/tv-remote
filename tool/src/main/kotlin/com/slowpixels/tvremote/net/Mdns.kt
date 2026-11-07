package com.slowpixels.tvremote.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Just enough of DNS to find Apple TVs with Bonjour (RFC 6762), in plain
 * Kotlin: Light's kit doesn't allow Android's own service discovery, and a
 * tool can't hold the Wi‑Fi multicast lock either.
 *
 * So the finder never needs to hear multicast: it sends its questions from an
 * ordinary port, and a Bonjour responder answers one of those ("legacy
 * unicast queries", RFC 6762 §6.7) with a plain packet straight back to the
 * sender. The questions go to the Bonjour address, and also directly to an
 * address the user typed.
 */
object Dns {
    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33
    const val TYPE_ANY = 255
    const val CLASS_IN = 1

    /** A resource record as it was answered; [data] is the record's bytes, and [rdataAt] where they sit in [packet] (names inside may point back). */
    class Record(val name: String, val type: Int, val ttl: Long, val data: ByteArray, private val packet: ByteArray, private val rdataAt: Int) {
        /** PTR: the name pointed to. */
        val target: String? get() = if (type == TYPE_PTR) runCatching { readName(packet, rdataAt).first }.getOrNull() else null
        /** SRV: the port. */
        val port: Int? get() = if (type == TYPE_SRV && data.size >= 6) ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF) else null
        /** SRV: the host the service runs on. */
        val host: String? get() = if (type == TYPE_SRV && data.size > 6) runCatching { readName(packet, rdataAt + 6).first }.getOrNull() else null
        /** A: the IPv4 address, dotted. */
        val address: String? get() = if (type == TYPE_A && data.size == 4) data.joinToString(".") { (it.toInt() and 0xFF).toString() } else null
        /** TXT: its "key=value" strings, as a map (a key without "=" has an empty value). */
        val txt: Map<String, String> get() {
            if (type != TYPE_TXT) return emptyMap()
            val out = LinkedHashMap<String, String>()
            var i = 0
            while (i < data.size) {
                val n = data[i].toInt() and 0xFF
                if (i + 1 + n > data.size) break
                val s = String(data, i + 1, n, Charsets.UTF_8)
                if (s.isNotEmpty()) { val eq = s.indexOf('='); if (eq < 0) out[s] = "" else out[s.substring(0, eq)] = s.substring(eq + 1) }
                i += 1 + n
            }
            return out
        }
    }

    /**
     * One question for [name] and [type], as a legacy unicast query (ID 0,
     * the unicast-response bit set, no recursion), which is what a Bonjour
     * responder answers straight back to the sender.
     */
    fun query(name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // ID 0, flags 0 (a query), one question
        writeName(out, name)
        out.write(type shr 8); out.write(type)
        out.write(0x80); out.write(CLASS_IN) // class IN with the unicast-response bit
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            require(b.size in 1..63) { "DNS label too long" }
            out.write(b.size); out.write(b)
        }
        out.write(0)
    }

    /**
     * The answers and additional records of a response (questions are
     * skipped). Malformed input gives what could be read, never an exception
     * and never an endless loop over compression pointers.
     */
    fun records(packet: ByteArray): List<Record> = runCatching {
        if (packet.size < 12) return emptyList()
        if (packet[2].toInt() and 0x80 == 0) return emptyList() // a question, not a response
        val questions = u16(packet, 4)
        val count = u16(packet, 6) + u16(packet, 8) + u16(packet, 10)
        var at = 12
        repeat(questions) { at = readName(packet, at).second + 4 }
        val out = ArrayList<Record>()
        repeat(count) {
            val (name, after) = readName(packet, at)
            at = after
            if (at + 10 > packet.size) return out
            val type = u16(packet, at)
            val ttl = (u16(packet, at + 4).toLong() shl 16) or u16(packet, at + 6).toLong()
            val n = u16(packet, at + 8)
            at += 10
            if (at + n > packet.size) return out
            out += Record(name, type, ttl, packet.copyOfRange(at, at + n), packet, at)
            at += n
        }
        out
    }.getOrDefault(emptyList())

    private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    /** A name at [start] (following compression pointers, at most 32 hops), and where the name's own bytes end. */
    internal fun readName(d: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var at = start
        var end = -1
        var hops = 0
        while (true) {
            require(at in d.indices) { "DNS name runs past the end" }
            val n = d[at].toInt() and 0xFF
            when {
                n == 0 -> { if (end < 0) end = at + 1; break }
                n and 0xC0 == 0xC0 -> {
                    require(++hops <= 32) { "DNS pointers loop" }
                    if (end < 0) end = at + 2
                    at = ((n and 0x3F) shl 8) or (d[at + 1].toInt() and 0xFF)
                }
                n and 0xC0 != 0 -> throw IllegalArgumentException("DNS label kind not supported")
                else -> {
                    require(at + 1 + n <= d.size) { "DNS label runs past the end" }
                    labels += String(d, at + 1, n, Charsets.UTF_8)
                    at += 1 + n
                }
            }
        }
        return labels.joinToString(".") + "." to end
    }
}

/** A Companion service as one response described it; [networkId] is its permanent "rpMRtID" (tvOS 16+), when announced. */
data class Service(val name: String, val host: String, val port: Int, val model: String, val networkId: String?)

/** Reads the Apple TVs out of one response's records (the responder's own address stands in when no A record came). */
object CompanionServices {
    const val TYPE_NAME = "_companion-link._tcp.local."

    fun from(records: List<Dns.Record>, from: InetAddress?): List<Service> {
        val instances = records.filter { it.type == Dns.TYPE_PTR && it.name.equals(TYPE_NAME, true) }.mapNotNull { it.target } +
            records.filter { it.type == Dns.TYPE_SRV && it.name.endsWith("._companion-link._tcp.local.", true) }.map { it.name }
        return instances.distinct().mapNotNull { instance ->
            val srv = records.firstOrNull { it.type == Dns.TYPE_SRV && it.name.equals(instance, true) } ?: return@mapNotNull null
            val port = srv.port ?: return@mapNotNull null
            val txt = records.firstOrNull { it.type == Dns.TYPE_TXT && it.name.equals(instance, true) }?.txt ?: emptyMap()
            // An answer naming a loopback address can't be reached from here (a Mac answers its own loopback that way), so the
            // responder's own address stands in, as it does when no A record came at all.
            val a = srv.host?.let { h -> records.firstOrNull { it.type == Dns.TYPE_A && it.name.equals(h, true) } }?.address?.takeIf { !it.startsWith("127.") }
            val host = a ?: (from as? Inet4Address)?.hostAddress ?: return@mapNotNull null
            val name = instance.removeSuffix(".").removeSuffix("._companion-link._tcp.local").removeSuffix("._companion-link._tcp.local".uppercase())
            Service(name, host, port, txt["rpMd"] ?: "", (txt["rpMRtID"] ?: txt["rpmrtid"])?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * Asks the Wi‑Fi for Companion services and hands each answer to [found], on
 * the finder's own thread. [ask] sends one round of questions: to the Bonjour
 * group on every live interface, and to [direct] addresses (typed by the
 * user) one by one. [close] ends it.
 */
interface TvFinder {
    fun ask()
    fun addDirect(address: String)
    fun removeDirect(address: String)
    fun close()
}

class Finder(private val found: (Service) -> Unit, private val log: (String) -> Unit = {}) : TvFinder {
    private val socket = DatagramSocket(0).apply { soTimeout = 400; broadcast = true }
    private val group = InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353)
    private val query = Dns.query(CompanionServices.TYPE_NAME, Dns.TYPE_PTR)
    @Volatile private var closed = false
    private val direct = java.util.concurrent.CopyOnWriteArraySet<String>()
    /** Questions go out from here, never from the thread that asked (Android refuses network work on the main one). */
    private val asker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "tv-asker").apply { isDaemon = true } }

    private val reader = Thread({
        val buf = ByteArray(9000)
        while (!closed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                val records = Dns.records(buf.copyOf(p.length))
                val services = CompanionServices.from(records, p.address)
                if (services.isEmpty() && records.isNotEmpty()) log("answer from ${p.address.hostAddress} had no Companion service (${records.size} records)")
                services.forEach { log("found ${it.name} at ${it.host}:${it.port} model=${it.model}"); found(it) }
            } catch (e: SocketTimeoutException) {
            } catch (e: Throwable) {
                if (!closed) log("finder: ${e::class.simpleName} ${e.message ?: ""}")
            }
        }
    }, "tv-finder").apply { isDaemon = true; start() }

    /** An address to ask directly as well (the Apple TV's, typed by hand). */
    override fun addDirect(address: String) { direct += address }
    override fun removeDirect(address: String) { direct -= address }

    /** Sends one round of questions, from the finder's own thread. Failures (no Wi‑Fi, say) are logged, never thrown. */
    override fun ask() {
        if (closed) return
        runCatching { asker.execute { askNow() } }
    }

    private fun askNow() {
        if (closed) return
        val nics = interfaces()
        if (nics.isEmpty()) send(group, "the Bonjour group")
        for (nic in nics) { setInterface(nic); send(group, "the Bonjour group on ${nic.name}") }
        for (a in direct) {
            val to = runCatching { InetSocketAddress(InetAddress.getByName(a), 5353) }.getOrNull() ?: continue
            send(to, a)
        }
    }

    private fun send(to: InetSocketAddress, what: String) {
        try {
            socket.send(DatagramPacket(query, query.size, to))
        } catch (e: Throwable) { log("ask $what: ${e::class.simpleName} ${e.message ?: ""}") }
    }

    private fun setInterface(nic: NetworkInterface) {
        // A plain DatagramSocket sends multicast on the route's interface; to choose, set IP_MULTICAST_IF through the option.
        runCatching { socket.setOption(java.net.StandardSocketOptions.IP_MULTICAST_IF, nic) }
    }

    /** The live, non-loopback interfaces with an IPv4 address (the Wi‑Fi, normally). */
    private fun interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { nic ->
            nic.isUp && !nic.isLoopback && nic.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
        }
    }.getOrDefault(emptyList())

    override fun close() { closed = true; asker.shutdownNow(); runCatching { socket.close() } }
}
