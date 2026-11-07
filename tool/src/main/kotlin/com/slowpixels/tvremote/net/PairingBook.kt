package com.slowpixels.tvremote.net

/** The little key-value store pairings live in (the app's preferences; a map in tests). */
interface KeyValues {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/**
 * One Apple TV paired with. [record] is how it's filed: the Apple TV's own
 * identifier (proven on every connection). [networkId] is the permanent ID
 * tvOS 16+ announces on the Wi‑Fi ("rpMRtID"), when seen.
 */
class SavedPairing(val record: String, val tvId: String?, val publicKey: ByteArray, val name: String, val networkId: String?)

/**
 * The Apple TVs paired with, filed by each Apple TV's own identity rather than
 * its name, so two Apple TVs called "Living Room" (at home and somewhere else)
 * can both stay paired, and a renamed Apple TV keeps its pairing.
 *
 * On the same Wi‑Fi two Apple TVs can't share a name (Bonjour renames the
 * second "Living Room (2)"), so names only collide across networks; the
 * network ID, or failing that the secure check on connecting, tells them apart.
 */
class PairingBook(private val kv: KeyValues) {

    fun all(): List<SavedPairing> = kv.keys().filter { it.startsWith(P) && it.endsWith(".key") }
        .map { it.removePrefix(P).removeSuffix(".key") }
        .mapNotNull { load(it) }
        .sortedBy { it.name }

    private fun load(record: String): SavedPairing? {
        val key = kv.get("$P$record.key")?.let { java.util.Base64.getDecoder().decode(it) } ?: return null
        return SavedPairing(record, kv.get("$P$record.id"), key, kv.get("$P$record.name") ?: "", kv.get("$P$record.mrtid"))
    }

    /**
     * The pairings that could be [tv], most likely first: the same network ID
     * (the same Apple TV, whatever it's called now), then the same name, unless
     * both network IDs are known and differ (then it's another Apple TV).
     */
    fun candidates(tv: Tv): List<SavedPairing> {
        val last = kv.get(LAST)
        val byNetwork = if (tv.networkId != null) all().filter { it.networkId == tv.networkId } else emptyList()
        val byName = all().filter { it.name == tv.name && (it.networkId == null || tv.networkId == null) && it !in byNetwork }
        return byNetwork + byName.sortedByDescending { it.record == last }
    }

    /** Files a new pairing (or replaces the one for the same Apple TV), and makes it the last used. */
    fun add(tv: Tv, pairing: Pairing): SavedPairing {
        val record = pairing.id
        kv.put("$P$record.key", java.util.Base64.getEncoder().encodeToString(pairing.publicKey))
        kv.put("$P$record.id", pairing.id)
        kv.put("$P$record.name", tv.name)
        tv.networkId?.let { kv.put("$P$record.mrtid", it) }
        kv.put(LAST, record)
        return load(record)!!
    }

    /** After a verified connection: remember what was learned (its identifier, network ID, a new name) and that it was used. */
    fun connected(saved: SavedPairing, tv: Tv, verifiedId: String?) {
        val r = saved.record
        if (saved.tvId == null && verifiedId != null) kv.put("$P$r.id", verifiedId)
        if (tv.networkId != null && saved.networkId == null) kv.put("$P$r.mrtid", tv.networkId)
        if (tv.name != saved.name) kv.put("$P$r.name", tv.name)
        kv.put(LAST, r)
    }

    /** Forgets one Apple TV; any other with the same name stays paired. */
    fun remove(saved: SavedPairing) {
        listOf("key", "id", "name", "mrtid").forEach { kv.remove("$P${saved.record}.$it") }
        if (kv.get(LAST) == saved.record) kv.remove(LAST)
    }

    /** Whether [saved] is the one used last. */
    fun isLast(saved: SavedPairing) = kv.get(LAST) == saved.record

    companion object {
        private const val P = "tv."
        private const val LAST = "lastTvRecord"
    }
}

/**
 * Connects to [tv] with the first of its [candidates] whose secure check
 * passes. A check that fails (another Apple TV with the same name) moves on to
 * the next; only when none pass is it a different Apple TV, reported as
 * [PeerRejected]. Any other failure (no answer, say) stops at once.
 */
suspend fun connectFirst(
    tv: Tv, candidates: List<SavedPairing>, attempt: suspend (SavedPairing) -> Companion,
): Pair<SavedPairing, Companion> {
    if (candidates.isEmpty()) throw PeerRejected("${tv.name} isn't paired. Choose it to pair.")
    for (saved in candidates) {
        try {
            return saved to attempt(saved)
        } catch (e: PeerRejected) {
            DebugLog.add("pairing ${saved.record} doesn't match ${tv.name}: ${e.message}")
        }
    }
    throw DifferentAppleTv(tv.name)
}

/** An Apple TV with the name of a paired one, that isn't it (somewhere else, say). */
class DifferentAppleTv(name: String) : PeerRejected("This is a different Apple TV called $name. Tap it to pair.")
