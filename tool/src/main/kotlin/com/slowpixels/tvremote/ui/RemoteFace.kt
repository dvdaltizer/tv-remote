package com.slowpixels.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slowpixels.tvremote.Hub
import com.slowpixels.tvremote.model.Remote
import com.slowpixels.tvremote.model.Touchpad
import com.slowpixels.tvremote.net.Captions
import com.slowpixels.tvremote.net.Companion
import com.slowpixels.tvremote.net.Controller
import com.slowpixels.tvremote.net.Link
import com.slowpixels.tvremote.net.Mute
import com.slowpixels.tvremote.net.Power
import com.slowpixels.tvremote.net.Tv
import com.slowpixels.tvremote.net.captionName
import com.thelightphone.sdk.rememberHapticsEnabled
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** How long a pressed button stays inverted at least, so even a quick tap shows. */
private const val PRESSED_MS = 120L

/** How long a finger stays still before a press becomes a hold (Select's options, TV's Control Center). */
private const val HOLD_MS = 450L

/**
 * The remote, laid out like Apple's iPhone remote: mute, the Apple TV's name
 * and power along the top; the touch surface with −10, Keyboard, Captions and
 * +10 along its bottom; Back, TV and Play/Pause below; Menu in the bottom bar.
 */
@Composable
fun ColumnScope.RemoteFace(remote: Remote, tv: Tv, open: Opener) {
    val k = remote.controller
    val link by k.link.collectAsState()
    val notice by k.notice.collectAsState()
    val power by k.power.collectAsState()
    val mute by k.mute.collectAsState()
    val haptics by rememberHapticsEnabled().collectAsState()
    val colors = LightThemeTokens.colors

    // Top: mute, the Apple TV's name (it opens its page), power.
    Row(Modifier.fillMaxWidth().height(3f.gridUnitsAsDp()), verticalAlignment = Alignment.CenterVertically) {
        run {
            // Mute keeps the last confirmed look while a change is under way (no flash), and ignores taps meanwhile.
            val confirmed = remember { arrayOfNulls<Boolean>(1) }
            when (mute) { Mute.Muted -> confirmed[0] = true; is Mute.Unmuted -> confirmed[0] = false; Mute.Disconnected -> confirmed[0] = null; else -> {} }
            val shownMuted = confirmed[0] == true
            TopIcon(if (shownMuted) Glyph.SpeakerMuted else Glyph.Speaker, "Mute", state = muteValue(mute),
                faded = mute is Mute.Unavailable || mute == Mute.Disconnected,
                mark = if (mute is Mute.Unknown) "?" else "", enabled = mute !is Mute.Unavailable && mute !is Mute.Pending, haptics = haptics) {
                when (mute) {
                    is Mute.Unmuted, Mute.Muted, is Mute.Unknown -> k.toggleMute()
                    Mute.Disconnected -> remote.retry()
                    else -> {}
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().semantics(mergeDescendants = true) {}
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) open(::DeviceScreen) } },
            contentAlignment = Alignment.Center) {
            LightText(tv.name, LightTextVariant.Fine, align = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        run {
            // Power, like mute: while Sleep or Wake waits for the Apple TV, the icon keeps its last confirmed look.
            val awake = remember { arrayOfNulls<Boolean>(1) }
            when (power) { Power.Awake -> awake[0] = true; Power.Asleep -> awake[0] = false; Power.Disconnected -> awake[0] = null; else -> {} }
            TopIcon(Glyph.Power, "Power", state = powerValue(power), faded = awake[0] != true,
                mark = if (power is Power.Unknown) "?" else "", enabled = power !is Power.Pending, haptics = haptics) {
                when (power) {
                    Power.Awake -> k.setPower(false)
                    Power.Asleep -> k.setPower(true)
                    is Power.Unknown -> open(::PowerScreen) // explicit Sleep and Wake
                    Power.Disconnected -> remote.retry()
                    is Power.Pending -> {}
                }
            }
        }
    }
    // Quiet when all is well: "Connecting…" only if it takes a while, nothing before the first attempt.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(link) { slow = false; if (link == Link.Connecting) { delay(1500); slow = true } }
    val status = when (val l = link) {
        Link.Connecting -> if (slow) "Connecting…" else ""
        is Link.Failed -> l.message + if (l.wrongTv) "" else " Tap to retry."
        Link.Idle -> ""
        Link.Connected -> notice
    }
    // One line of fixed height, so a message never moves the surface.
    Box(Modifier.fillMaxWidth().height(1.5f.gridUnitsAsDp()).padding(horizontal = 1f.gridUnitsAsDp())
        .pointerInput(status) { if (status.isEmpty()) return@pointerInput; awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) {
            if (link !is Link.Connected) remote.retry() else if (notice.isNotEmpty()) k.clearNotice()
        } } }, contentAlignment = Alignment.Center) {
        if (status.isNotEmpty()) LightText(status, LightTextVariant.Fine, align = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    TouchSurface(remote, haptics, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) { open({ KeyboardScreen(it, auto = false) }) }
    // Back, TV (a tap goes home; holding it opens Control Center, as on the Siri Remote) and Play/Pause.
    Row(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(0.75f.gridUnitsAsDp())) {
        RemoteButton(Glyph.Back, "Back", Modifier.weight(1f), haptics) { remote.press(Companion.Button.Menu) }
        RemoteButton(Glyph.Tv, "TV", Modifier.weight(1f), haptics, holdLabel = "Control Center",
            onHold = { k.hold(Companion.Button.Home, "Control Center") }) { remote.press(Companion.Button.Home) }
        RemoteButton(Glyph.PlayPause, "Play or pause", Modifier.weight(1f), haptics) { remote.press(Companion.Button.PlayPause) }
    }
    LightBottomBar(listOf(LightBarButton.Text("MENU", onClick = { open(::MenuScreen) })))
    @Suppress("UNUSED_VARIABLE") val unused = colors
}

/** A top-row icon: a 3-unit target, a 28dp glyph. [state] is read out after the name ("Mute, Muted"). */
@Composable
private fun TopIcon(glyph: Glyph, description: String, faded: Boolean, mark: String, enabled: Boolean = true, state: String? = null,
                    haptics: Boolean, onClick: () -> Unit) {
    val (down, setDown) = pressedState()
    Box(Modifier.size(3f.gridUnitsAsDp())
        .then(if (enabled) Modifier.tapOrHold(onClick, null, pressed = { setDown(it); if (it) Hub.buzz(haptics) }) else Modifier)
        .semantics {
            role = Role.Button
            contentDescription = description
            if (state != null) stateDescription = state
            if (!enabled) disabled()
        }, contentAlignment = Alignment.Center) {
        // Faded only for "can't" (unavailable, offline, asleep); a busy icon keeps its look.
        Box(Modifier.alpha(if (faded) 0.35f else 1f)) { GlyphIcon(glyph, 28.dp, if (down) LightThemeTokens.colors.contentSecondary else LightThemeTokens.colors.content) }
        if (mark.isNotEmpty()) LightText(mark, LightTextVariant.Fine)
    }
}

fun powerValue(p: Power) = when (p) {
    Power.Awake -> "Awake"
    Power.Asleep -> "Asleep"
    is Power.Pending -> if (p.toAwake) "Waking…" else "Sleeping…"
    is Power.Unknown -> "Unknown"
    Power.Disconnected -> "Offline"
}

fun muteValue(m: Mute) = when (m) {
    Mute.Muted -> "Muted"
    is Mute.Unmuted -> "On, ${(m.level * 100).toInt()}%"
    is Mute.Pending -> if (m.toMuted) "Muting…" else "Unmuting…"
    is Mute.Unknown -> "Unknown"
    is Mute.Unavailable -> "Unavailable"
    Mute.Disconnected -> "Offline"
}

fun captionValue(c: Captions) = when (c) {
    is Captions.Mode -> captionName(c.code)
    Captions.Checking -> "Checking…"
    Captions.Switching -> "Switching…"
    is Captions.Unknown -> "Unknown"
    is Captions.Unavailable -> "Unavailable"
    Captions.Disconnected -> "Offline"
}

fun Controller.tapCaptions() = when (captions.value) {
    Captions.Checking, Captions.Switching, is Captions.Unavailable -> {}
    else -> pressCaptions()
}

/**
 * A press that's a tap if let go quickly, or a hold (down until the finger
 * lifts) if [startHold] is given and the finger stays. An interrupted gesture
 * (scrolling away, the screen changing, the tool leaving) still lets go.
 */
private fun Modifier.tapOrHold(
    onTap: () -> Unit, startHold: (() -> Controller.Hold)?, pressed: (Boolean) -> Unit = {}, held: () -> Unit = {},
): Modifier = pointerInput(startHold != null) {
    awaitEachGesture {
        awaitFirstDown()
        pressed(true)
        var hold: Controller.Hold? = null
        try {
            if (startHold == null) { if (waitForUpOrCancellation() != null) onTap(); return@awaitEachGesture }
            var finished = false
            val up = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation().also { finished = true } }
            if (finished) { if (up != null) onTap(); return@awaitEachGesture }
            hold = startHold()
            held()
            waitForUpOrCancellation()
            hold.release()
        } finally {
            hold?.release(cancelled = true) // no effect if already let go
            pressed(false)
        }
    }
}

/** A button's pressed look: inverted while the finger is down, and for [PRESSED_MS] at least so a quick tap shows. */
@Composable
private fun pressedState(): Pair<Boolean, (Boolean) -> Unit> {
    var down by remember { mutableStateOf(false) }
    var since by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    return down to { now: Boolean ->
        if (now) { down = true; since = System.currentTimeMillis() }
        else scope.launch { delay((PRESSED_MS - (System.currentTimeMillis() - since)).coerceAtLeast(0)); down = false }
    }
}

@Composable
private fun RemoteButton(
    glyph: Glyph, label: String, modifier: Modifier, haptics: Boolean, enabled: Boolean = true,
    holdLabel: String? = null, onHold: (() -> Controller.Hold)? = null, onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val shape = RoundedCornerShape(0.75f.gridUnitsAsDp())
    val (down, setDown) = pressedState()
    Box(modifier.height(3.6f.gridUnitsAsDp()).border(2.dp, colors.content.copy(alpha = if (enabled) 1f else 0.35f), shape)
        // Pressed, a button inverts: the outline fills and the glyph takes the background's colour.
        .background(if (down) colors.content else colors.background, shape)
        .then(if (enabled) Modifier.tapOrHold(onClick, onHold,
            pressed = { setDown(it); if (it) Hub.buzz(haptics) },
            held = { Hub.buzz(haptics) }) else Modifier)
        .semantics {
            role = Role.Button
            contentDescription = label
            if (enabled) {
                onClick(label) { onClick(); true }
                // For screen readers, a long press is a one-second hold.
                if (holdLabel != null && onHold != null) onLongClick(holdLabel) { onHold().release(); true }
            } else disabled()
        },
        contentAlignment = Alignment.Center) {
        Box(Modifier.alpha(if (enabled) 1f else 0.35f)) { GlyphIcon(glyph, 30.dp, if (down) colors.background else colors.content) }
    }
}

/**
 * The touch surface. Swipe (the default): a step for each short stretch of a
 * swipe. Arrows: tap an edge to move a step. Both: arrows shown, and swiping
 * works too. Anywhere else, tap to select and hold still to hold select (an
 * app's options, say). Screen readers get the four directions, select and
 * hold as actions. −10, Keyboard, Captions and +10 sit along its bottom.
 * Edge swipes here are the remote's, not the phone's back gesture.
 */
@Composable
private fun TouchSurface(remote: Remote, haptics: Boolean, modifier: Modifier, openKeyboard: () -> Unit) {
    val k = remote.controller
    val touchpad by remote.touchpad.collectAsState()
    val hints by remote.hints.collectAsState()
    val colors = LightThemeTokens.colors
    val shape = RoundedCornerShape(0.75f.gridUnitsAsDp())
    Column(modifier.clip(shape).border(2.dp, colors.content, shape).systemGestureExclusion()) {
    Box(
        Modifier.weight(1f).fillMaxWidth()
            .semantics {
                contentDescription = "Touch surface. Swipe to move, tap to select, hold for options."
                onClick("Select") { remote.press(Companion.Button.Select); true }
                onLongClick("Hold select") { k.hold(Companion.Button.Select).release(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Up") { remote.press(Companion.Button.Up); true },
                    CustomAccessibilityAction("Down") { remote.press(Companion.Button.Down); true },
                    CustomAccessibilityAction("Left") { remote.press(Companion.Button.Left); true },
                    CustomAccessibilityAction("Right") { remote.press(Companion.Button.Right); true },
                )
            }
            .pointerInput(touchpad, haptics) {
                val step = 60.dp.toPx()
                fun edgeArrow(p: Offset): Companion.Button? {
                    if (!touchpad.showsArrows) return null
                    val edge = minOf(size.width, size.height) * 0.22f
                    return when {
                        p.y < edge -> Companion.Button.Up
                        p.y > size.height - edge -> Companion.Button.Down
                        p.x < edge -> Companion.Button.Left
                        p.x > size.width - edge -> Companion.Button.Right
                        else -> null
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    var dx = 0f
                    var dy = 0f
                    var moved = false
                    var released = false
                    var finished = false
                    var hold: Controller.Hold? = null
                    try {
                        // First: a tap, the start of a swipe, or (if the finger stays still) a hold.
                        withTimeoutOrNull(HOLD_MS) {
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (c == null) { finished = true; return@withTimeoutOrNull }
                                if (!c.pressed) { released = true; finished = true; return@withTimeoutOrNull }
                                val d = c.positionChange()
                                dx += d.x; dy += d.y
                                c.consume()
                                if (abs(dx) > slop || abs(dy) > slop) { moved = true; finished = true; return@withTimeoutOrNull }
                            }
                        }
                        val arrow = edgeArrow(down.position)
                        if (released) {
                            Hub.buzz(haptics)
                            remote.press(arrow ?: Companion.Button.Select)
                            return@awaitEachGesture
                        }
                        if (!finished) {
                            // Held still: select is held down until the finger lifts (an edge arrow just waits for it).
                            if (arrow == null) { hold = k.hold(Companion.Button.Select); Hub.buzz(haptics) }
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                            }
                            hold?.release() ?: arrow?.let { remote.press(it) }
                            return@awaitEachGesture
                        }
                        // A swipe: a step each stretch along one axis (Arrows alone doesn't swipe).
                        while (moved) {
                            if (touchpad.swipes && (abs(dx) >= step || abs(dy) >= step)) {
                                Hub.buzz(haptics)
                                remote.press(if (abs(dx) > abs(dy)) (if (dx > 0) Companion.Button.Right else Companion.Button.Left)
                                             else (if (dy > 0) Companion.Button.Down else Companion.Button.Up))
                                dx = 0f; dy = 0f
                            }
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val d = c.positionChange()
                            dx += d.x; dy += d.y
                            c.consume()
                        }
                    } finally {
                        hold?.release(cancelled = true) // no effect if already let go
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (touchpad.showsArrows) {
            Arrow("▲", Modifier.align(Alignment.TopCenter).padding(top = 1f.gridUnitsAsDp()))
            Arrow("▼", Modifier.align(Alignment.BottomCenter).padding(bottom = 1f.gridUnitsAsDp()))
            Arrow("◀", Modifier.align(Alignment.CenterStart).padding(start = 1f.gridUnitsAsDp()))
            Arrow("▶", Modifier.align(Alignment.CenterEnd).padding(end = 1f.gridUnitsAsDp()))
        }
        if (hints) LightText(when (touchpad) { Touchpad.Swipe -> "Swipe · Tap · Hold"; Touchpad.Arrows -> "Tap an arrow · Tap to select"; Touchpad.Both -> "Swipe or tap an arrow" },
            LightTextVariant.Fine, align = TextAlign.Center, lighten = true)
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.content))
    PlaybackStrip(remote, haptics, openKeyboard)
    }
}

/** −10, Keyboard, Captions, +10, along the bottom of the touch surface. */
@Composable
private fun PlaybackStrip(remote: Remote, haptics: Boolean, openKeyboard: () -> Unit) {
    val k = remote.controller
    val captions by k.captions.collectAsState()
    val current by k.current.collectAsState()
    // CC is offered unless the app playing says outright that it has no captions.
    val usable = current?.captionOptions != false
    // Skipping fades when the Apple TV says it isn't possible now (e.g. the home screen); unknown counts as possible.
    val media by k.media.collectAsState()
    val canBack = media?.skipBackward != false
    val canAhead = media?.skipForward != false
    // The last setting read stays shown while it's being read again (no flicker).
    val lastMode = remember { arrayOfNulls<Int>(1) }
    (captions as? Captions.Mode)?.let { lastMode[0] = it.code }
    if (captions == Captions.Disconnected) lastMode[0] = null
    val ccValue = lastMode[0]?.let { captionName(it) }
    Row(Modifier.fillMaxWidth().height(3.5f.gridUnitsAsDp())) {
        StripButton("Back 10 seconds", Modifier.weight(1f), haptics, enabled = canBack, onClick = { remote.skip(-10.0) }) { ink -> GlyphIcon(Glyph.SkipBack, 28.dp, ink) }
        StripButton("Keyboard", Modifier.weight(1f), haptics, onClick = openKeyboard) { ink -> GlyphIcon(Glyph.Keyboard, 28.dp, ink) }
        StripButton(if (usable) "Captions: ${if (captions == Captions.Switching) "switching" else ccValue ?: "unknown"}" else "No captions in this app",
            Modifier.weight(1f), haptics, enabled = usable, onClick = { k.tapCaptions() }) { ink ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphIcon(Glyph.Captions, 28.dp, ink)
                val label = if (captions == Captions.Switching) "…" else ccValue
                if (usable && label != null) LightText(label, LightTextVariant.Micro, maxLines = 1, color = ink)
            }
        }
        StripButton("Ahead 10 seconds", Modifier.weight(1f), haptics, enabled = canAhead, onClick = { remote.skip(10.0) }) { ink -> GlyphIcon(Glyph.SkipForward, 28.dp, ink) }
    }
}

/** A strip button: inverted while pressed (as the other buttons), pale and inert when [enabled] is false. */
@Composable
private fun StripButton(label: String, modifier: Modifier, haptics: Boolean, enabled: Boolean = true, onClick: () -> Unit,
                        content: @Composable (androidx.compose.ui.graphics.Color) -> Unit) {
    val colors = LightThemeTokens.colors
    val (down, setDown) = pressedState()
    Box(modifier.fillMaxHeight().background(if (down) colors.content else colors.background)
        .then(if (enabled) Modifier.tapOrHold(onClick, null, pressed = { setDown(it); if (it) Hub.buzz(haptics) }) else Modifier)
        .semantics {
            role = Role.Button
            contentDescription = label
            if (enabled) onClick(label) { onClick(); true } else disabled()
        },
        contentAlignment = Alignment.Center) {
        Box(Modifier.alpha(if (enabled) 1f else 0.35f)) { content(if (down) colors.background else colors.content) }
    }
}

@Composable
private fun Arrow(glyph: String, modifier: Modifier) = LightText(glyph, LightTextVariant.Heading, modifier, lighten = true)
