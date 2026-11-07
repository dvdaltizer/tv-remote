package com.slowpixels.tvremote.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** The connection to the Apple TV in use. */
sealed class Link {
    object Idle : Link()
    object Connecting : Link()
    object Connected : Link()
    class Failed(val message: String, val wrongTv: Boolean = false) : Link()
}

/**
 * What the remote knows about the Apple TV's own sleep state (not the
 * television's: that follows only if HDMI-CEC is on). Never guessed: when the
 * Apple TV hasn't said, it's [Unknown].
 */
sealed class Power {
    object Disconnected : Power()
    class Unknown(val reason: String) : Power()
    object Awake : Power()
    object Asleep : Power()
    /** Sleep or wake was sent; waiting (for a few seconds at most) for the Apple TV to confirm. */
    class Pending(val toAwake: Boolean) : Power()
}

/**
 * Mute, as the Apple TV's volume control allows it: the Companion protocol has
 * no mute command, so muting sets the volume to zero and unmuting puts back the
 * level it had. Only offered when the Apple TV says it controls the volume
 * (AirPods or a speaker it drives, say); many TVs on HDMI-CEC or IR don't.
 */
sealed class Mute {
    object Disconnected : Mute()
    class Unavailable(val reason: String) : Mute()
    class Unknown(val reason: String) : Mute()
    object Muted : Mute()
    class Unmuted(val level: Double) : Mute()
    class Pending(val toMuted: Boolean) : Mute()
}

/**
 * The Apple TV's caption preference ("_mcs", as the pyatv maintainer
 * documented it): 1 automatic, 2 always on, 3 forced only (just the parts in
 * another language). Apps may ignore it.
 */
sealed class Captions {
    object Disconnected : Captions()
    class Unknown(val reason: String) : Captions()
    object Checking : Captions()
    class Mode(val code: Int) : Captions()
    object Switching : Captions()
    class Unavailable(val reason: String) : Captions()
}

/** The Apple TV's on-screen text field, as the keyboard sees it. */
sealed class Keyboard {
    class Unavailable(val reason: String) : Keyboard()
    object NoField : Keyboard()
    class Field(val id: String, val text: String) : Keyboard()
}

/** What happened to a piece of typing. */
enum class TextResult { Sent, FieldChanged, NoField, NotSent, Uncertain }

/** Which media controls the Apple TV says work right now (its "_iMC" flags, as pyatv reads them). */
class MediaControls(val flags: Int) {
    val play get() = flags and 0x0001 != 0
    val pause get() = flags and 0x0002 != 0
    val volume get() = flags and 0x0100 != 0
    val skipForward get() = flags and 0x0200 != 0
    val skipBackward get() = flags and 0x0400 != 0
}

/**
 * Everything that happens over the connection, kept off Android so it can be
 * tested: one connection at a time (made by [open]), a bounded number of
 * attempts, commands sent in order, states reported truthfully, holds that are
 * always released, and typing that only ever goes to the field it was meant for.
 *
 * A command is sent again only when it surely wasn't sent (not connected);
 * if it may have reached the Apple TV, it isn't repeated and [notice] says so.
 */
class Controller(
    private val scope: CoroutineScope,
    private val open: suspend () -> Companion,
    private val attempts: Int = 2,
    private val retryDelayMs: Long = 700,
    private val pollMs: Long = 1500,
    private val pendingMs: Long = 8000,
    /** The shortest press the Apple TV sees for a hold, so a hold is never mistaken for a click. */
    private val minHoldMs: Long = 1000,
    /** A hold is let go on its own after this long, whatever happens to the finger. */
    private val maxHoldMs: Long = 10_000,
    /**
     * Where the snapshots another app has replaced are remembered, so a repeat
     * of one is still recognised after the app restarts (null: memory only).
     */
    private val staleStore: StaleStore? = null,
) {
    private val _link = MutableStateFlow<Link>(Link.Idle)
    private val _power = MutableStateFlow<Power>(Power.Disconnected)
    private val _mute = MutableStateFlow<Mute>(Mute.Disconnected)
    private val _keyboard = MutableStateFlow<Keyboard>(Keyboard.Unavailable("Not connected"))
    private val _media = MutableStateFlow<MediaControls?>(null)
    private val _notice = MutableStateFlow("")
    private val _prompt = MutableStateFlow<String?>(null)
    private val _captions = MutableStateFlow<Captions>(Captions.Disconnected)
    private val _captionNote = MutableStateFlow("")
    val captions: StateFlow<Captions> = _captions
    /** What happened to the last caption switch ("" when nothing to say). */
    val captionNote: StateFlow<String> = _captionNote
    private val _current = MutableStateFlow<NowPlayingInfo?>(null)
    /** What's playing on this connection, followed while connected (null before any update, and after a disconnect). */
    val current: StateFlow<NowPlayingInfo?> = _current
    val link: StateFlow<Link> = _link
    val power: StateFlow<Power> = _power
    val mute: StateFlow<Mute> = _mute
    val keyboard: StateFlow<Keyboard> = _keyboard
    /** What the Apple TV last said about its media controls; null until it says (or when disconnected). */
    val media: StateFlow<MediaControls?> = _media
    /** A short message about the last thing that went wrong ("" when nothing has). */
    val notice: StateFlow<String> = _notice
    /**
     * The field the text sheet should open for by itself: a field the Apple TV
     * opened while this app is in front and connected, and that hasn't been
     * dismissed. Null when there's none (the sheet closes if it opened itself).
     */
    val prompt: StateFlow<String?> = _prompt
    /** Whether the app is on screen (only then does the keyboard open by itself). */
    val foreground = MutableStateFlow(false)

    private val connectLock = Mutex()
    private val commandLock = Mutex()
    private var connection: Companion? = null
    private var watcher: Job? = null
    private var pendingJob: Job? = null
    private var muteJob: Job? = null
    @Volatile private var textBusy = false
    /** What this remote last put in the field (to send only what changed). */
    private var sentText: String? = null
    private var sentField: String? = null
    /** The field whose sheet was dismissed: it doesn't open again by itself. */
    private var dismissedField: String? = null
    private val muteBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val powerBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** The volume to go back to when unmuting. */
    private var restoreLevel: Double? = null
    private val holds = CopyOnWriteArrayList<Hold>()
    /** Full snapshots another app has since replaced; a repeat of one is old news (kept across reconnects and restarts). */
    private val staleSnapshots = java.util.Collections.synchronizedSet(LinkedHashSet<String>(staleStore?.load() ?: emptySet()))

    private val live: Companion? get() = connection?.takeIf { !it.closed.value }

    init {
        scope.launch {
            combine(_keyboard, _link, foreground) { k, l, f -> Triple(k, l, f) }.collect { (k, l, f) ->
                val field = k as? Keyboard.Field
                when {
                    field == null || l !is Link.Connected -> _prompt.value = null
                    !f -> {} // nothing opens while in the back
                    field.id != dismissedField -> _prompt.value = field.id
                }
            }
        }
    }

    // region Connecting

    /**
     * Returns the live connection, connecting if needed. Callers at the same
     * time share one attempt; a failure is retried [attempts] times at most,
     * but never when the device isn't the paired Apple TV.
     */
    suspend fun ensure(): Companion? = connectLock.withLock {
        live?.let { return it }
        connection = null
        _link.value = Link.Connecting
        var last: Throwable? = null
        for (attempt in 1..attempts) {
            try {
                val c = open()
                adopt(c)
                _link.value = Link.Connected
                _notice.value = ""
                return c
            } catch (e: PeerRejected) {
                _link.value = Link.Failed(e.message ?: "This isn't the paired Apple TV.", wrongTv = true)
                markDisconnected()
                return null
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                Logs.i("connect attempt $attempt failed: ${e::class.simpleName} ${e.message ?: ""}")
                if (attempt < attempts) delay(retryDelayMs * attempt)
            }
        }
        _link.value = Link.Failed(friendly(last))
        markDisconnected()
        null
    }

    private fun friendly(e: Throwable?): String = when (e) {
        is java.net.SocketTimeoutException -> "The Apple TV didn't answer. Is it on the same Wi-Fi?"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "Couldn't reach the Apple TV on the Wi-Fi."
        null -> "Couldn't connect."
        else -> "Couldn't connect (${e.message ?: e::class.simpleName})."
    }

    /** Starts following a new connection: its events, its power and volume, and when it drops. */
    private fun adopt(c: Companion) {
        connection = c
        watcher?.cancel()
        sentField = null; sentText = null
        _keyboard.value = Rti.field(c.textStart?.get("_c")?.let { (it as? Map<*, *>)?.get("_tiD") as? ByteArray })
            ?.let { Keyboard.Field(it.id, it.text) } ?: if (c.textStart != null) Keyboard.NoField else Keyboard.Unavailable("This Apple TV didn't offer its keyboard.")
        _power.value = Power.Unknown("Checking…")
        _mute.value = Mute.Unknown("Checking…")
        _media.value = null
        _captions.value = Captions.Unknown("Not asked yet")
        _captionNote.value = ""
        _current.value = null
        watcher = scope.launch {
            launch { c.events.collect { handle(c, it) } }
            launch { refreshPower(c) }
            launch { refreshVolume(c) }
            // The caption setting is read up front (reading changes nothing), so CC shows it and switches on the first tap.
            launch { if (connection === c && _captions.value !is Captions.Switching) _captions.value = captionsFrom(readCaptions(c)) }
            // What's playing (for CC) is asked once, then followed through the Apple TV's updates.
            launch { fetchNowPlaying(c) }
            c.closed.first { it }
            if (connection === c) {
                connection = null
                markDisconnected()
                // A drop says so (Idle is kept for "not started", which shows nothing).
                if (_link.value is Link.Connected) _link.value = Link.Failed("Connection lost.")
            }
            coroutineContext[Job]?.cancel()
        }
    }

    private fun markDisconnected() {
        pendingJob?.cancel()
        muteJob?.cancel()
        _power.value = Power.Disconnected
        _mute.value = Mute.Disconnected
        _media.value = null
        _keyboard.value = Keyboard.Unavailable("Not connected")
        _captions.value = Captions.Disconnected
        _current.value = null
    }

    /** Connects now (e.g. when the app comes to the front). */
    fun start() { scope.launch { ensure() } }

    /**
     * Closes the connection (when the app goes to the back, or another TV is
     * chosen). Any button still held is let go first; the connection closes
     * once that's sent (a second at most).
     */
    fun stop() {
        val c = connection
        watcher?.cancel(); watcher = null
        connection = null
        markDisconnected()
        _link.value = Link.Idle
        val held = snapshotOf(holds) // a hold finishing on another thread may remove itself right now
        held.forEach { it.release(cancelled = true) }
        if (held.isEmpty()) c?.close()
        else scope.launch { withTimeoutOrNull(1000) { held.forEach { it.done.await() } }; c?.close() }
    }

    // endregion

    // region Commands

    /**
     * Sends one command in order with the others. If it surely wasn't sent,
     * reconnects and sends it once more; if it may have arrived, it's left.
     */
    private suspend fun <T> command(what: String, block: suspend (Companion) -> T): T? = commandLock.withLock {
        for (round in 1..2) {
            val c = ensure() ?: return null
            try {
                return block(c)
            } catch (e: NotConnected) {
                if (round == 2) _notice.value = "$what not sent: offline"
            } catch (e: NoReply) {
                _notice.value = "$what: no answer, not resent"
                return null
            } catch (e: Rejected) {
                _notice.value = "$what refused"
                return null
            }
        }
        null
    }

    /**
     * A button's down, then (after [waitForUp]) its up. The up is always tried
     * once a down may have arrived, so nothing is left held; nothing is ever
     * sent twice. A down that surely wasn't sent is reported as [NotConnected].
     */
    private suspend fun downUp(c: Companion, code: Int, waitForUp: suspend () -> Unit) {
        var downFailure: CommandFailure? = null
        try {
            c.request("_hidC", mapOf("_hBtS" to 1, "_hidC" to code))
        } catch (e: NoReply) {
            downFailure = e
        }
        withContext(NonCancellable) {
            if (downFailure == null) runCatching { waitForUp() }
            try {
                c.request("_hidC", mapOf("_hBtS" to 2, "_hidC" to code))
            } catch (e: CommandFailure) {
                if (downFailure == null && e !is NotConnected) downFailure = e
            }
        }
        downFailure?.let { throw it }
    }

    /** A click (down, then up), or a press held for [holdMs]. */
    fun press(button: Companion.Button, holdMs: Long = 0, label: String = button.name) {
        scope.launch {
            command(label) { c -> downUp(c, button.code) { if (holdMs > 0) delay(holdMs) } }
        }
    }

    /**
     * A button held for as long as the finger stays down: [start] sends the
     * down, [Hold.release] the up (no sooner than [minHoldMs] after the down,
     * so the Apple TV sees a hold, not a click). Released on its own after
     * [maxHoldMs], when the connection drops, or when the app stops.
     */
    inner class Hold internal constructor(val button: Companion.Button) {
        private val released = CompletableDeferred<Boolean>()
        internal val done = CompletableDeferred<Unit>()
        /** Lets go. [cancelled] (the gesture was interrupted) sends the up at once. */
        fun release(cancelled: Boolean = false) { released.complete(!cancelled) }
        internal val isReleased get() = released.isCompleted
        internal suspend fun waitForRelease(c: Companion, downAt: Long) {
            // A dropped connection lets go too (there's nothing left to send the up on).
            val watch = scope.launch { c.closed.first { it }; released.complete(false) }
            try {
                val normal = withTimeoutOrNull(maxHoldMs) { released.await() } ?: false
                if (normal) {
                    val left = minHoldMs - (System.currentTimeMillis() - downAt)
                    if (left > 0) delay(left)
                }
            } finally {
                watch.cancel()
            }
        }
    }

    fun hold(button: Companion.Button, label: String = "Holding ${button.name}"): Hold {
        val h = Hold(button)
        holds += h
        scope.launch {
            try {
                command(label) { c ->
                    // Let go before the down could be sent (still connecting, say): send nothing, rather than a late click.
                    if (h.isReleased) return@command
                    downUp(c, button.code) { h.waitForRelease(c, System.currentTimeMillis()) }
                }
            } finally {
                holds -= h
                h.done.complete(Unit)
            }
        }
        return h
    }

    /** Skips back or forward by [seconds] in what's playing. */
    fun skip(seconds: Double) {
        scope.launch {
            command(if (seconds < 0) "Skip back" else "Skip forward") { c ->
                c.request("_mcc", mapOf("_mcc" to Companion.SKIP_BY, "_skpS" to seconds))
            }
        }
    }

    // endregion

    // region Events

    private suspend fun handle(c: Companion, e: TvEvent) {
        when (e.name) {
            "SystemStatus", "TVSystemStatus" -> {
                val p = stateOf((e.content["state"] as? Number)?.toInt()) ?: return
                val pending = _power.value as? Power.Pending
                _power.value = p
                if (pending != null) pendingJob?.cancel()
            }
            "_iMC" -> {
                val flags = (e.content["_mcF"] as? Number)?.toInt() ?: return
                if (_media.value?.flags == flags) return // a repeat changes nothing
                _media.value = MediaControls(flags)
                if (_mute.value !is Mute.Pending) scope.launch { refreshVolume(c) }
            }
            "NowPlayingInfo" -> if (connection === c) {
                val u = NowPlayingUpdate.parse(e.content["NowPlayingInfoKey"] as? ByteArray)
                if (u == null) { DebugLog.add("  now playing: unreadable update"); return }
                val sig = u.snapshotSignature()
                if (sig != null && sig in staleSnapshots) {
                    // tvOS answers "what's playing?" with the last app that shared details, long after another app took
                    // over (seen on David's Apple TV: an Apple TV+ movie kept coming back while Netflix played).
                    DebugLog.add("  now playing: ignored a repeat of a superseded snapshot")
                    return
                }
                val before = _current.value
                if (u.hasPlayer && before?.player != null && u.player != before.player) before.signature?.let { markStale(it) }
                if (sig != null && staleSnapshots.remove(sig)) saveStale()
                val info = (before ?: NowPlayingInfo.EMPTY).merge(u).let { if (sig != null) it.withSignature(sig) else it.withSignature(before?.signature?.takeIf { !u.hasPlayer || u.player == before.player }) }
                _current.value = info
                DebugLog.add("  now playing: player=${info.player != null} rate=${info.rate} state=${info.state} offset=${u.timeOffset} " +
                    "captionsOffered=${info.captionOptions} captionsOn=${info.captionsOn}${if (u.hasMetadata) "" else " (partial)"}")
            }
            "_tiStarted" -> if (!textBusy) {
                val field = Rti.field(e.content["_tiD"] as? ByteArray)
                _keyboard.value = field?.let { Keyboard.Field(it.id, it.text) } ?: Keyboard.NoField
            }
            "_tiStopped" -> if (!textBusy) _keyboard.value = Keyboard.NoField
        }
    }

    // endregion

    // region Power

    private fun stateOf(code: Int?): Power? = when (code) {
        1 -> Power.Asleep
        2, 3, 4 -> Power.Awake // screensaver and idle are awake
        else -> null
    }

    private suspend fun fetchPower(c: Companion): Power? =
        runCatching { stateOf(((c.request("FetchAttentionState", emptyMap())["_c"] as? Map<*, *>)?.get("state") as? Number)?.toInt()) }.getOrNull()

    private suspend fun refreshPower(c: Companion) {
        val p = fetchPower(c)
        if (connection === c && _power.value !is Power.Pending) {
            _power.value = p ?: Power.Unknown("The Apple TV didn't say whether it's awake.")
        }
    }

    /** Asks again (the power button's caption offers this when it's unknown). */
    fun checkPower() { scope.launch { live?.let { refreshPower(it) } ?: ensure() } }

    /**
     * Puts the Apple TV to sleep ([awake] false) or wakes it. Shows Pending
     * until the Apple TV confirms; after [pendingMs] without a confirmation the
     * state is what it last said, or Unknown. Never assumed to have worked.
     */
    fun setPower(awake: Boolean) {
        // One Sleep or Wake at a time: a second tap while one is under way does nothing (taken here, before any waiting).
        if (!powerBusy.compareAndSet(false, true)) return
        scope.launch {
          try {
            if (_power.value is Power.Pending) return@launch
            val before = _power.value
            _power.value = Power.Pending(awake)
            // pyatv sends sleep and wake as a single button-up.
            val sent = command(if (awake) "Wake" else "Sleep") { c ->
                c.request("_hidC", mapOf("_hBtS" to 2, "_hidC" to (if (awake) Companion.Button.Wake else Companion.Button.Sleep).code))
                c
            }
            if (sent == null) {
                if (_power.value is Power.Pending) {
                    _power.value = if (live == null) Power.Disconnected else Power.Unknown(
                        if (before is Power.Unknown) before.reason else "Not sure whether it's awake.",
                    )
                }
                return@launch
            }
            pendingJob = scope.launch {
                var last: Power? = null
                val confirmed = withTimeoutOrNull(pendingMs) {
                    while (true) {
                        delay(pollMs)
                        val c = live ?: return@withTimeoutOrNull false
                        last = fetchPower(c) ?: continue
                        if ((last is Power.Awake) == awake) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (_power.value is Power.Pending) {
                    _power.value = when {
                        live == null -> Power.Disconnected
                        confirmed == true -> if (awake) Power.Awake else Power.Asleep
                        last != null -> last!!.also { _notice.value = if (awake) "Didn't wake" else "Didn't sleep" }
                        else -> Power.Unknown("No answer after ${if (awake) "Wake" else "Sleep"}.")
                    }
                }
            }
            pendingJob?.join()
          } finally {
            powerBusy.set(false)
          }
        }
    }

    // endregion

    // region Volume and mute

    private val noVolume = "The Apple TV doesn't control the volume of this TV or speaker (common with TVs on HDMI-CEC or IR). Use the TV's own remote."

    /** The volume (0 to 1) the Apple TV reports, or why there isn't one. */
    private suspend fun fetchVolume(c: Companion): Result<Double> = runCatching {
        val reply = c.request("_mcc", mapOf("_mcc" to Companion.GET_VOLUME))
        ((reply["_c"] as? Map<*, *>)?.get("_vol") as? Number)?.toDouble() ?: throw Rejected("Volume", "no level")
    }

    private suspend fun refreshVolume(c: Companion) {
        if (_media.value?.volume == false) {
            if (connection === c && _mute.value !is Mute.Pending) _mute.value = Mute.Unavailable(noVolume)
            return
        }
        val v = fetchVolume(c)
        if (connection !== c || _mute.value is Mute.Pending) return
        _mute.value = v.fold(
            onSuccess = { if (it <= 0.0) Mute.Muted else Mute.Unmuted(it) },
            onFailure = { e -> if (e is Rejected) Mute.Unavailable(noVolume) else Mute.Unknown("The Apple TV didn't say what the volume is.") },
        )
    }

    /** Asks the Apple TV for the volume again. */
    fun checkVolume() { scope.launch { live?.let { refreshVolume(it) } ?: ensure() } }

    /**
     * Mutes (volume to zero) or unmutes (back to the level it had), then reads
     * the volume back: the button changes only when the Apple TV confirms.
     */
    fun toggleMute() {
        // One change at a time: a second tap while one is under way does nothing (taken here, before any waiting).
        if (!muteBusy.compareAndSet(false, true)) return
        scope.launch {
          try {
            val now = _mute.value
            val target: Double = when (now) {
                is Mute.Unmuted -> { restoreLevel = now.level; 0.0 }
                Mute.Muted -> restoreLevel ?: run {
                    _notice.value = "Volume is 0: use the side buttons"
                    return@launch
                }
                is Mute.Unknown -> { checkVolume(); return@launch }
                else -> return@launch
            }
            val muting = target == 0.0
            _mute.value = Mute.Pending(muting)
            val sent = command(if (muting) "Mute" else "Unmute") { c ->
                c.request("_mcc", mapOf("_mcc" to Companion.SET_VOLUME, "_vol" to target)); c
            }
            if (sent == null) {
                if (_mute.value is Mute.Pending) _mute.value = if (live == null) Mute.Disconnected else Mute.Unknown("Not sure whether it's muted.")
                return@launch
            }
            muteJob = scope.launch {
                var last: Double? = null
                withTimeoutOrNull(pendingMs) {
                    while (true) {
                        delay(pollMs / 3)
                        val c = live ?: break
                        last = fetchVolume(c).getOrNull() ?: continue
                        if ((last!! <= 0.0) == muting) break
                    }
                }
                if (_mute.value is Mute.Pending) {
                    _mute.value = when (val l = last) {
                        null -> if (live == null) Mute.Disconnected else Mute.Unknown("No answer after ${if (muting) "Mute" else "Unmute"}.")
                            .also { _notice.value = "${if (muting) "Mute" else "Unmute"}: no confirmation" }
                        else -> (if (l <= 0.0) Mute.Muted else Mute.Unmuted(l)).also {
                            if ((l <= 0.0) != muting) _notice.value = if (muting) "Volume didn't change" else "Volume didn't return"
                        }
                    }
                }
            }
            muteJob?.join()
          } finally {
            muteBusy.set(false)
          }
        }
    }

    // endregion

    // region Captions

    private suspend fun readCaptions(c: Companion): Result<Int> = runCatching {
        val v = ((c.request("_mcc", mapOf("_mcc" to Companion.GET_CAPTION_SETTINGS))["_c"] as? Map<*, *>)?.get("_mcs") as? Number)?.toInt()
        v ?: throw Rejected("Caption settings", "no setting in the answer")
    }

    private fun captionsFrom(r: Result<Int>): Captions = r.fold(
        onSuccess = { Captions.Mode(it) },
        onFailure = { e ->
            when (e) {
                is Rejected -> Captions.Unavailable("This Apple TV doesn't share its caption setting with remote apps (${e.reason}).")
                is NotConnected -> Captions.Disconnected
                else -> Captions.Unknown("The Apple TV didn't answer about captions.")
            }
        },
    )

    /** The CC button: switches captions, reading the setting first if it isn't known yet. */
    fun pressCaptions() {
        scope.launch {
            if (_captions.value !is Captions.Mode) {
                val c = ensure() ?: return@launch
                commandLock.withLock { _captions.value = Captions.Checking; _captions.value = captionsFrom(readCaptions(c)) }
            }
            if (_captions.value is Captions.Mode) toggleCaptions()
        }
    }

    /** Reads the caption setting (changes nothing). */
    fun checkCaptions() {
        scope.launch {
            if (_captions.value is Captions.Switching) return@launch
            commandLock.withLock {
                val c = ensure() ?: return@launch
                _captions.value = Captions.Checking
                _captions.value = captionsFrom(readCaptions(c))
            }
        }
    }

    /**
     * Switches the caption setting the way the Siri Remote app does (one
     * "toggle" request; tvOS flips always on and forced only), then reads it
     * back. Shown only as the Apple TV reports it afterwards. If the switch got
     * no answer it may or may not have happened, so it's never sent again.
     */
    fun toggleCaptions() {
        scope.launch {
            val before = (_captions.value as? Captions.Mode)?.code ?: return@launch
            commandLock.withLock {
                val c = live
                if (c == null) { _captionNote.value = "Not connected"; return@launch }
                _captions.value = Captions.Switching
                _captionNote.value = ""
                val outcome: String? = try {
                    c.request("_mcc", mapOf("_mcc" to Companion.SET_CAPTION_SETTINGS, "_mcs" to 4)); null
                } catch (e: NotConnected) {
                    _captions.value = Captions.Disconnected
                    _captionNote.value = "Not connected"
                    return@launch
                } catch (e: Rejected) {
                    "Refused"
                } catch (e: NoReply) {
                    "No answer, not resent"
                }
                val after = if (live === c) readCaptions(c) else Result.failure(NotConnected())
                _captions.value = captionsFrom(after)
                val now = after.getOrNull()
                _captionNote.value = when {
                    now == null -> if (outcome != null) "$outcome, couldn't read back" else "Couldn't read back"
                    outcome != null -> outcome
                    now != before -> ""
                    else -> "Unchanged"
                }
            }
        }
    }

    // endregion

    // region Now Playing

    /** Asks the Apple TV to send what's playing (the answer comes as a NowPlayingInfo update). */
    private suspend fun fetchNowPlaying(c: Companion) {
        runCatching { c.request("FetchCurrentNowPlayingInfoEvent", emptyMap()) }
    }

    /** Remembers [sig] as replaced by another app (the most recent [StaleStore.KEEP] are kept). */
    private fun markStale(sig: String) {
        synchronized(staleSnapshots) {
            staleSnapshots.remove(sig)
            staleSnapshots.add(sig)
            while (staleSnapshots.size > StaleStore.KEEP) staleSnapshots.remove(staleSnapshots.first())
        }
        saveStale()
    }

    private fun saveStale() { staleStore?.save(synchronized(staleSnapshots) { LinkedHashSet(staleSnapshots) }) }

    // endregion

    // region Typing

    /**
     * Makes the Apple TV's field read [text]. Sends only to field [fieldId]
     * (the one the text box was opened for), as the Apple TV last described
     * it; if another field is open, or none, nothing is typed.
     */
    suspend fun setText(fieldId: String, text: String): TextResult {
        var result = TextResult.NotSent
        command("Typing") { c ->
            val field = _keyboard.value
            if (field !is Keyboard.Field) { result = TextResult.NoField; return@command }
            if (field.id != fieldId) { result = TextResult.FieldChanged; return@command }
            val session = hex(field.id)
            // Only what this remote typed is known for sure; otherwise start from what the Apple TV said.
            val current = if (sentField == fieldId) sentText ?: field.text else field.text
            result = TextResult.Uncertain
            if (text.startsWith(current)) {
                val added = text.substring(current.length)
                if (added.isNotEmpty()) c.event("_tiC", mapOf("_tiV" to 1, "_tiD" to Rti.insert(session, added)))
            } else {
                c.event("_tiC", mapOf("_tiV" to 1, "_tiD" to Rti.clear(session)))
                if (text.isNotEmpty()) c.event("_tiC", mapOf("_tiV" to 1, "_tiD" to Rti.insert(session, text)))
            }
            sentField = fieldId
            sentText = text
            result = TextResult.Sent
        }
        if (result == TextResult.Uncertain) { sentField = null; sentText = null }
        return result
    }

    private fun hex(id: String) = ByteArray(id.length / 2) { id.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Asks the Apple TV which field is open now. */
    suspend fun refreshKeyboard(): Keyboard {
        command("Keyboard") { c ->
            textBusy = true
            try {
                val reply = c.request("_tiStart", emptyMap())
                _keyboard.value = Rti.field((reply["_c"] as? Map<*, *>)?.get("_tiD") as? ByteArray)
                    ?.let { Keyboard.Field(it.id, it.text) } ?: Keyboard.NoField
            } finally {
                textBusy = false
            }
        }
        return _keyboard.value
    }

    /** The text sheet for [fieldId] was closed by hand: it doesn't open by itself again for that field. */
    fun dismissPrompt(fieldId: String?) {
        dismissedField = fieldId ?: (_keyboard.value as? Keyboard.Field)?.id
        _prompt.value = null
    }

    /** What the text box should start with for [fieldId]. */
    fun textFor(fieldId: String): String =
        if (sentField == fieldId) sentText ?: "" else (_keyboard.value as? Keyboard.Field)?.takeIf { it.id == fieldId }?.text ?: ""

    // endregion

    fun clearNotice() { _notice.value = "" }

    /** For previews and tests only: media-control flags, as if the Apple TV had reported them. */
    @androidx.annotation.VisibleForTesting
    internal fun previewMedia(flags: Int?) { _media.value = flags?.let { MediaControls(it) } }

    /** For previews and tests only: a power state, as if the Apple TV had reported it. */
    @androidx.annotation.VisibleForTesting
    internal fun previewPower(p: Power) { _power.value = p }

    /** For previews and tests only: a mute state, as if the Apple TV had reported it. */
    @androidx.annotation.VisibleForTesting
    internal fun previewMute(m: Mute) { _mute.value = m }

    /** For previews and tests only: what's playing, as if the Apple TV had said so. */
    @androidx.annotation.VisibleForTesting
    internal fun preview(info: NowPlayingInfo?) { _current.value = info }
}

/**
 * One consistent copy of a collection other threads change. Kotlin's toList()
 * reads the size and then the items in separate steps, so a removal in
 * between throws (seen as "Index 0 out of bounds for length 0" when a hold
 * finished while the app stopped); this copies the backing array in one step.
 */
internal fun <T> snapshotOf(items: Collection<T>): List<T> = ArrayList(items)

/** How the caption setting reads, in Apple's words: Auto, On, or Off (Off still subtitles parts in another language). */
fun captionName(code: Int): String = when (code) {
    1 -> "Auto"
    2 -> "On"
    3 -> "Off" // as Apple labels it: parts in another language are still subtitled
    else -> "Unknown ($code)"
}

/** Where replaced now-playing snapshots are kept between runs of the app. */
interface StaleStore {
    fun load(): Set<String>
    fun save(signatures: Set<String>)

    companion object {
        /** Plenty: each app switch adds one. */
        const val KEEP = 20
    }
}
