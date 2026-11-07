package com.slowpixels.tvremote.model

import com.slowpixels.tvremote.net.Companion
import com.slowpixels.tvremote.net.CompanionServices
import com.slowpixels.tvremote.net.Controller
import com.slowpixels.tvremote.net.Crypto
import com.slowpixels.tvremote.net.DebugLog
import com.slowpixels.tvremote.net.DifferentAppleTv
import com.slowpixels.tvremote.net.Finder
import com.slowpixels.tvremote.net.TvFinder
import com.slowpixels.tvremote.net.Identity
import com.slowpixels.tvremote.net.KeyValues
import com.slowpixels.tvremote.net.Logs
import com.slowpixels.tvremote.net.PairingBook
import com.slowpixels.tvremote.net.SavedPairing
import com.slowpixels.tvremote.net.Service
import com.slowpixels.tvremote.net.StaleStore
import com.slowpixels.tvremote.net.StoredPairing
import com.slowpixels.tvremote.net.Tv
import com.slowpixels.tvremote.net.connectFirst
import com.slowpixels.tvremote.net.isAppleTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The tool's state: Apple TVs found on the Wi‑Fi (Bonjour,
 * `_companion-link._tcp`, or an address typed by hand), the pairings made,
 * and the connection to the TV in use (through [Controller], which reconnects
 * when needed). Nothing here touches Android, so it can be tested.
 */
class Remote(
    private val kv: KeyValues,
    private val scope: CoroutineScope,
    private val newFinder: ((Service) -> Unit) -> TvFinder = { found -> Finder(found, log = { DebugLog.add(it) }) },
) {
    val tvs = MutableStateFlow<List<Tv>>(emptyList())
    val status = MutableStateFlow("")
    val current = MutableStateFlow<Tv?>(null)
    /** The TV waiting for its PIN while pairing. */
    val pairing = MutableStateFlow<Tv?>(null)
    private var connection: Companion? = null

    /** This phone's identity as a remote: made once, kept for good. */
    val identity: Identity by lazy {
        val id = kv.get("pairingId") ?: UUID.randomUUID().toString().uppercase().also { kv.put("pairingId", it) }
        val seed = kv.get("seed")?.let { java.util.Base64.getDecoder().decode(it) }
            ?: ByteArray(32).also { Crypto.random.nextBytes(it); kv.put("seed", java.util.Base64.getEncoder().encodeToString(it)) }
        Identity(id, seed)
    }

    /** The pairings, filed by each Apple TV's own identity (see [PairingBook]). */
    private val book = PairingBook(kv)
    /** Apple TVs found to share a paired one's name without being it (in this run of the tool). */
    private val notOurs = mutableSetOf<String>()
    private fun Tv.spot() = "$name@$host"
    /** The pairing used for the current connection. */
    private var inUse: SavedPairing? = null

    fun isPaired(tv: Tv) = tv.spot() !in notOurs && book.candidates(tv).isNotEmpty()

    /** The Apple TVs paired with, by name, whether or not they're on this Wi‑Fi. */
    fun pairedNames(): List<String> = book.all().map { it.name }.distinct().sorted()

    // region Finding Apple TVs

    private var finder: TvFinder? = null
    private var asking: Job? = null
    /** When each Apple TV last answered, by name. */
    private val seen = HashMap<String, Long>()
    /** Addresses typed by hand, asked directly every round (kept across runs). */
    val addresses = MutableStateFlow(kv.get("addresses")?.split(",")?.filter { it.isNotBlank() } ?: emptyList())

    fun startDiscovery() {
        if (finder != null) return
        val f = newFinder { s -> scope.launch { found(s) } }
        addresses.value.forEach { f.addDirect(it) }
        finder = f
        asking = scope.launch {
            // Quick asks first (a TV normally answers the first one), then a slow heartbeat.
            for (wait in listOf(0L, 1000L, 2500L)) { delay(wait); f.ask() }
            while (isActive) {
                delay(8000)
                f.ask()
                val gone = System.currentTimeMillis() - 45_000
                tvs.value = tvs.value.filter { (seen[it.name] ?: 0) > gone }
            }
        }
    }

    fun stopDiscovery() {
        asking?.cancel(); asking = null
        finder?.close(); finder = null
    }

    private fun found(s: Service) {
        // HomePods, Macs and iPhones advertise the same service; only Apple TVs take remote commands.
        if (!isAppleTv(s.model)) return
        val tv = Tv(s.name, s.host, s.port, s.model, s.networkId)
        seen[tv.name] = System.currentTimeMillis()
        if (tvs.value.none { it == tv }) tvs.value = (tvs.value.filter { it.name != tv.name } + tv).sortedBy { it.name }
        autoPick()
    }

    /** An Apple TV's address typed by hand: asked directly from now on; [onAnswer] says whether anything answered within a few seconds. */
    fun addAddress(address: String, onAnswer: (Boolean) -> Unit) {
        val a = address.trim()
        if (a.isEmpty()) return
        if (a !in addresses.value) { addresses.value = addresses.value + a; kv.put("addresses", addresses.value.joinToString(",")) }
        val f = finder
        if (f == null) { startDiscovery() } else { f.addDirect(a); f.ask() }
        scope.launch {
            val before = tvs.value.map { it.host }.toSet()
            for (i in 1..8) {
                delay(500)
                if (i % 3 == 0) finder?.ask()
                if (tvs.value.any { it.host == a && it.host !in before } || tvs.value.any { it.host == a }) { onAnswer(true); return@launch }
            }
            onAnswer(false)
        }
    }

    fun removeAddress(address: String) {
        addresses.value = addresses.value - address
        kv.put("addresses", addresses.value.joinToString(","))
        finder?.removeDirect(address)
    }

    // endregion

    // region Pairing and connecting

    /** Connects by itself when a paired Apple TV turns up, until the user picks from the list by hand. */
    private var autoConnect = true
    private var autoJob: Job? = null

    /**
     * Connects to a paired Apple TV on this Wi‑Fi: the one used last if it's
     * here, otherwise (after a moment, in case the last one is still being
     * found) the first paired one found.
     */
    private fun autoPick() {
        if (!autoConnect || current.value != null || pairing.value != null) return
        val here = tvs.value.filter { isPaired(it) }
        if (here.isEmpty()) return
        here.firstOrNull { tv -> book.candidates(tv).any { book.isLast(it) } }?.let { autoJob?.cancel(); use(it); return }
        if (autoJob?.isActive == true) return
        autoJob = scope.launch {
            delay(1500)
            if (autoConnect && current.value == null && pairing.value == null) tvs.value.firstOrNull { isPaired(it) }?.let { use(it) }
        }
    }

    /** Asks the Apple TV to show a PIN. */
    fun startPairing(tv: Tv) {
        scope.launch {
            status.value = "Asking ${tv.name} for a code…"
            val c = Companion(tv.host, tv.port, identity)
            runCatching { c.startPairing() }
                .onSuccess { connection = c; pairing.value = tv; status.value = "" }
                .onFailure { Logs.i("start pairing failed: $it"); status.value = "Couldn't start pairing: ${it.message ?: it::class.simpleName}" }
        }
    }

    fun finishPairing(pin: String) {
        val tv = pairing.value ?: return
        val c = connection ?: return
        scope.launch {
            status.value = "Pairing…"
            runCatching { c.finishPairing(pin.trim(), "Light Phone") }
                .onSuccess {
                    book.add(tv, it) // filed by this Apple TV's own identity: any other "Living Room" stays paired
                    notOurs -= tv.spot()
                    pairing.value = null
                    use(tv)
                }
                .onFailure {
                    Logs.i("pairing failed: $it")
                    // The Apple TV ends the attempt with the connection, so ask it for a fresh code to type.
                    connection = null
                    val again = Companion(tv.host, tv.port, identity)
                    val asked = runCatching { again.startPairing() }.isSuccess
                    if (asked) connection = again
                    status.value = (it.message ?: "Pairing failed.") + if (asked) " Type the code the TV shows now." else " Go back and choose the Apple TV again."
                }
        }
    }

    fun cancelPairing() {
        connection?.close(); connection = null
        pairing.value = null
        status.value = ""
    }

    /** Connects to a paired Apple TV and makes it the one the buttons control. */
    fun use(tv: Tv) {
        if (!isPaired(tv)) return startPairing(tv)
        autoJob?.cancel()
        if (current.value != tv) controller.stop()
        current.value = tv
        controller.start()
    }

    /**
     * One connection attempt to the current TV, proving it's the one paired
     * with: each pairing that could be it is tried in turn. If none is, it's a
     * different Apple TV with the same name: back to the list, where it can be
     * paired (the others stay paired).
     */
    private suspend fun open(): Companion {
        val tv = current.value ?: throw java.io.IOException("No Apple TV chosen")
        val (saved, c) = try {
            connectFirst(tv, book.candidates(tv)) { s ->
                Companion(tv.host, tv.port, identity, clientVersion = clientVersion.value).also { it.connect(StoredPairing(s.tvId, s.publicKey)) }
            }
        } catch (e: DifferentAppleTv) {
            notOurs += tv.spot()
            scope.launch { disconnect(); status.value = e.message ?: "" }
            throw e
        }
        book.connected(saved, tv, c.verifiedTvId)
        inUse = saved
        return c
    }

    val controller = Controller(scope, ::open, staleStore = object : StaleStore {
        // Kept in order, newest last, one per line.
        override fun load() = kv.get("staleSnapshots")?.split("\n")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        override fun save(signatures: Set<String>) { kv.put("staleSnapshots", signatures.joinToString("\n")) }
    })

    /** Tries again after a failure (the status line is tappable). */
    fun retry() = controller.start()

    /** Back to the list (and no connecting by itself until the tool is next opened). */
    fun disconnect() {
        autoConnect = false
        autoJob?.cancel()
        controller.stop()
        current.value = null
        inUse = null
        status.value = ""
    }

    /** Forgets this Apple TV only; another with the same name stays paired. */
    fun forget(tv: Tv) {
        (inUse?.takeIf { current.value == tv } ?: book.candidates(tv).firstOrNull())?.let { book.remove(it) }
        if (current.value == tv) disconnect()
    }

    /** The tool went to the back: let go of any held button, the connection (and its battery use) and the search. */
    fun pause() {
        controller.foreground.value = false
        controller.stop()
        stopDiscovery()
    }

    /**
     * The tool is in front: look for Apple TVs and reconnect to the one in use.
     * Every screen says so as it shows, so only the first time after the back
     * (not a screen change) starts connecting by itself again: "Choose another
     * Apple TV" must land on the list and stay there.
     */
    fun resume() {
        val wasInBack = !controller.foreground.value
        controller.foreground.value = true
        startDiscovery()
        if (current.value != null) controller.start() else if (wasInBack) { autoConnect = true; autoPick() }
    }

    /** How the touch surface works. */
    val touchpad = MutableStateFlow(Touchpad.from(kv.get("touchpad")))
    fun setTouchpad(t: Touchpad) { touchpad.value = t; kv.put("touchpad", t.key) }

    /** The client version announced to the Apple TV (pyatv's, unless the newer one is chosen in Advanced). */
    val clientVersion = MutableStateFlow(kv.get("clientVersion") ?: Companion.CLIENT_VERSION_PYATV)
    fun setClientVersion(v: String) {
        clientVersion.value = v
        kv.put("clientVersion", v)
        controller.stop()
        controller.start()
    }

    /** Short hints on the touch surface. */
    val hints = MutableStateFlow(kv.get("hints")?.toBooleanStrictOrNull() ?: true)
    fun setHints(on: Boolean) { hints.value = on; kv.put("hints", on.toString()) }

    fun press(button: Companion.Button, holdMs: Long = 0) = controller.press(button, holdMs)
    fun skip(seconds: Double) = controller.skip(seconds)

    // endregion
}

/**
 * The touch surface: Swipe moves one step per stretch; Arrows taps an edge;
 * Both shows the arrows and swipes too.
 */
enum class Touchpad(val key: String, val label: String) {
    Swipe("swipe", "Swipe"), Arrows("arrows", "Arrows"), Both("both", "Both");
    val showsArrows get() = this != Swipe
    val swipes get() = this != Arrows
    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: Swipe
    }
}
