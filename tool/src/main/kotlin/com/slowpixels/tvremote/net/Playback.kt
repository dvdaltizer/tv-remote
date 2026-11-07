package com.slowpixels.tvremote.net

/**
 * One NowPlayingInfo update as the Apple TV sends it (a TVRCNowPlayingInfo
 * keyed archive). Only what the remote uses is read: the player, its rate and
 * state, whether it offers captions, and when its position was taken (to
 * recognise a repeated old snapshot). Updates can be partial: a pause or play
 * arrives as an archive holding only "playbackRate" (pyatv issue 2325), so a
 * key that's absent means "unchanged", while a key that's present but `$null`
 * means "none".
 */
class NowPlayingUpdate(
    val hasPlayer: Boolean, val player: String?,
    val hasMetadata: Boolean,
    val hasRate: Boolean, val rate: Double?,
    val hasState: Boolean, val state: Int?,
    /** Whether the player has captions to offer (what the iPhone app greys CC by), and whether they're on. */
    val captionOptions: Boolean?,
    val captionsOn: Boolean?,
    /** Where playback was ("timeOffset", seconds) at "timestamp" (seconds since 2001, the Apple TV's clock). */
    val timeOffset: Double? = null, val timestamp: Double? = null,
) {
    /**
     * What identifies one full snapshot: the player and the moment its position
     * was taken. tvOS repeats an old snapshot unchanged, so the same signature
     * after another app's update means it's stale. Null for partial updates.
     */
    fun snapshotSignature(): String? =
        if (hasMetadata && player != null && (timeOffset != null || timestamp != null)) "$player|$timeOffset|$timestamp" else null

    companion object {
        /** Null if [data] isn't a NowPlayingInfo archive. */
        fun parse(data: ByteArray?): NowPlayingUpdate? {
            val a = KeyedArchive.open(data) ?: return null
            val root = a.root ?: return null
            val meta = a.map(root, "metadata")
            return NowPlayingUpdate(
                hasPlayer = root.containsKey("playerIdentifier"), player = a.string(root, "playerIdentifier"),
                hasMetadata = root.containsKey("metadata"),
                hasRate = root.containsKey("playbackRate"), rate = a.number(root, "playbackRate")?.takeIf { it in -64.0..64.0 },
                hasState = root.containsKey("playbackState"), state = a.number(root, "playbackState")?.toInt(),
                captionOptions = a.get(root, "hasValidCaptionOptions") as? Boolean,
                captionsOn = a.get(root, "captionsEnabled") as? Boolean,
                timeOffset = a.number(meta, "timeOffset")?.takeIf { it >= 0 && it < 48 * 3600 },
                timestamp = a.number(meta, "timestamp"),
            )
        }
    }
}

/**
 * What's playing on this connection, built from the updates so far: a new
 * player starts afresh (nothing carries over from another app); a partial
 * update changes only what it holds. Used for CC (greyed when the app says it
 * has no captions) and Settings › Advanced.
 */
class NowPlayingInfo(
    val player: String?,
    val rate: Double?,
    val state: Int?,
    val receivedAt: Long,
    /** True when the player says it has captions; false means CC can't work here; null means not said. */
    val captionOptions: Boolean? = null,
    val captionsOn: Boolean? = null,
    /** The signature of the full snapshot this came from (see [NowPlayingUpdate.snapshotSignature]). */
    val signature: String? = null,
) {
    fun withSignature(s: String?) = NowPlayingInfo(player, rate, state, receivedAt, captionOptions, captionsOn, s)

    /** This, changed by [u]. */
    fun merge(u: NowPlayingUpdate, now: Long = System.currentTimeMillis()): NowPlayingInfo {
        val fresh = u.hasPlayer && u.player != player
        val base = if (fresh) EMPTY else this
        return NowPlayingInfo(
            player = if (u.hasPlayer) u.player else base.player,
            rate = if (u.hasRate) u.rate else base.rate,
            state = if (u.hasState) u.state else base.state,
            receivedAt = now,
            // Only an explicit yes or no counts: Netflix mixes "no" with blank updates, and CC mustn't flicker.
            captionOptions = u.captionOptions ?: base.captionOptions,
            captionsOn = u.captionsOn ?: base.captionsOn,
        )
    }

    companion object {
        val EMPTY = NowPlayingInfo(null, null, null, 0)

        /** A single archive read on its own. */
        fun parse(data: ByteArray?, now: Long = System.currentTimeMillis()): NowPlayingInfo? =
            NowPlayingUpdate.parse(data)?.let { EMPTY.merge(it, now) }
    }
}
