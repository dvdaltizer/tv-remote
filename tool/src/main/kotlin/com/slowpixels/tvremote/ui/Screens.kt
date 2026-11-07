package com.slowpixels.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.slowpixels.tvremote.Hub
import com.slowpixels.tvremote.model.Touchpad
import com.slowpixels.tvremote.net.Companion
import com.slowpixels.tvremote.net.DebugLog
import com.slowpixels.tvremote.net.Keyboard
import com.slowpixels.tvremote.net.Link
import com.slowpixels.tvremote.net.TextResult
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// region Pairing and finding

/** A keypad of [keys] rows ("" for a blank, "⌫" for delete) that fills the space it's given. */
@Composable
private fun ColumnScope.Keypad(rows: List<List<String>>, canDelete: Boolean, tap: (String) -> Unit, delete: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp())) {
        for (row in rows) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (k in row) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                when {
                    k == "⌫" && canDelete -> Box(Modifier.fillMaxSize().lightClickable(onClick = delete).semantics { contentDescription = "Delete" },
                        contentAlignment = Alignment.Center) { LightIcon(LightIcons.DELETE, contentDescription = null) }
                    k.isNotEmpty() && k != "⌫" -> Box(Modifier.fillMaxSize().lightClickable { tap(k) }, contentAlignment = Alignment.Center) {
                        LightText(k, LightTextVariant.Heading)
                    }
                }
            }
        }
    }
}

private val DIGITS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))

/** The code the Apple TV shows, typed once to pair. */
class PairScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    override val needsTv = false
    override fun willShow() { super.willShow(); Hub.pairUp = true }
    /** Leaving by any route (the arrow, a swipe) cancels; the code screen is up again only when the Apple TV is asked again. */
    override fun onScreenDestroy() { Hub.pairUp = false; if (remote.pairing.value != null) remote.cancelPairing() }

    @Composable
    override fun Body() {
        val tv by remote.pairing.collectAsState()
        val status by remote.status.collectAsState()
        if (tv == null) LaunchedEffect(this) { goBack() }
        var pin by remember { mutableStateOf("") }
        Themed {
            BackBar("Pair with ${tv?.name ?: "Apple TV"}") { remote.cancelPairing() }
            Note("Type the 4‑digit code shown on the TV. You only do this once.", Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
            Box(Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
                Figures((0..3).joinToString(" ") { pin.getOrNull(it)?.toString() ?: "–" }, size = 80f,
                    modifier = Modifier.semantics { contentDescription = if (pin.isEmpty()) "No digits yet" else "Code so far: ${pin.toList().joinToString(" ")}" })
            }
            Keypad(DIGITS + listOf(listOf("", "0", "⌫")), canDelete = pin.isNotEmpty(), tap = { if (pin.length < 4) pin += it }, delete = { pin = pin.dropLast(1) })
            if (status.isNotEmpty()) Note(status, Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
            LightBottomBar(listOf(if (pin.length == 4 && status != "Pairing…") LightBarButton.Text("PAIR", onClick = { remote.finishPairing(pin) }) else null))
        }
    }
}

/**
 * An Apple TV's address, typed by hand, for a Wi‑Fi that doesn't pass the
 * finder's questions along. The tool then asks that address directly, every
 * time it looks.
 */
class AddressScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    override val needsTv = false

    @Composable
    override fun Body() {
        val addresses by remote.addresses.collectAsState()
        var typed by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var asking by remember { mutableStateOf(false) }
        val valid = typed.split(".").let { parts -> parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true && (p.length == 1 || !p.startsWith("0")) } }
        Themed {
            BackBar("By address") { goBack() }
            LightScrollView(Modifier.weight(1.1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                Note("If your Apple TV isn't found, type its IP address. On the Apple TV it's under Settings › Network.")
                Box(Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
                    Figures(typed.ifEmpty { "–" }, size = 44f, modifier = Modifier.semantics { contentDescription = typed.ifEmpty { "No address yet" } })
                }
                if (message.isNotEmpty()) Note(message)
                for (a in addresses) Row(Modifier.fillMaxWidth().padding(vertical = 0.4f.gridUnitsAsDp()), verticalAlignment = Alignment.CenterVertically) {
                    LightText(a, LightTextVariant.Copy, Modifier.weight(1f), lighten = true)
                    Box(Modifier.size(2.5f.gridUnitsAsDp()).lightClickable { remote.removeAddress(a) }.semantics { contentDescription = "Stop asking $a" },
                        contentAlignment = Alignment.Center) { LightIcon(LightIcons.CLOSE, size = 1.5f, contentDescription = null) }
                }
            }
            Keypad(DIGITS + listOf(listOf(".", "0", "⌫")), canDelete = typed.isNotEmpty(),
                tap = { k -> if (typed.length < 15 && !(k == "." && (typed.isEmpty() || typed.endsWith(".") || typed.count { it == '.' } >= 3))) typed += k },
                delete = { typed = typed.dropLast(1) })
            LightBottomBar(listOf(if (valid && !asking) LightBarButton.Text("FIND", onClick = {
                asking = true
                message = "Asking $typed…"
                val address = typed
                remote.addAddress(address) { answered ->
                    asking = false
                    message = if (answered) "Found an Apple TV at $address." else "Nothing answered at $address. Check the address, and that the Apple TV is on."
                    if (answered) { typed = ""; goBack() }
                }
            }) else null))
        }
    }
}

// endregion

// region Typing on the Apple TV

/**
 * Typing into the Apple TV's search (or any) field, with the phone's keyboard.
 * It opens by itself when the Apple TV opens a field ([auto]), or from the
 * keyboard key on the touch surface. It belongs to that one field: if the
 * Apple TV moves to another field, nothing more is typed until "Use this field".
 */
class KeyboardScreen(sealed: SealedLightActivity, private val auto: Boolean) : TvPage(sealed) {
    override fun willShow() { super.willShow(); Hub.keyboardUp = true }
    override fun onScreenDestroy() { Hub.keyboardUp = false }

    @Composable
    override fun Body() {
        val k = remote.controller
        val keyboard by k.keyboard.collectAsState()
        val prompt by k.prompt.collectAsState()
        val options = rememberKeyboardOptions()
        val scope = rememberCoroutineScope()
        var boundId by remember { mutableStateOf(if (auto) prompt else (keyboard as? Keyboard.Field)?.id) }
        var message by remember { mutableStateOf("") }
        val field = keyboard as? Keyboard.Field
        val usable = field != null && field.id == boundId
        // Opened by the Apple TV, it closes with the field; the keyboard key's stays until Done.
        if (auto && prompt == null) LaunchedEffect(this) { goBack() }
        LaunchedEffect(Unit) { if (boundId == null) k.refreshKeyboard() }
        LaunchedEffect(keyboard) { if (boundId == null && field != null) boundId = field.id }
        fun leave() { k.dismissPrompt(boundId ?: prompt); goBack() }
        Themed {
            if (!usable) {
                BackBar("Keyboard") { leave() }
                Note(when {
                    keyboard is Keyboard.Unavailable -> "Not connected to the Apple TV."
                    field == null -> "No text field is open on the Apple TV. Open a search or a sign-in on the TV first, then try again."
                    else -> "The Apple TV has moved to a different text field."
                }, Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
                Box(Modifier.weight(1f))
                LightBottomBar(listOf(
                    if (field != null) LightBarButton.Text("USE THIS FIELD", onClick = { boundId = field.id; message = "" })
                    else LightBarButton.Text("CHECK AGAIN", onClick = { scope.launch { k.refreshKeyboard() } }),
                ))
            } else key(boundId) {
                val id = boundId!!
                val text = rememberTextFieldState(k.textFor(id))
                // Each change goes to the Apple TV after a short pause, so a word goes as one change.
                LaunchedEffect(text) {
                    snapshotFlow { text.text.toString() }.collectLatest { v ->
                        if (v == k.textFor(id)) return@collectLatest
                        delay(350)
                        message = when (k.setText(id, v)) {
                            TextResult.Sent -> ""
                            TextResult.FieldChanged -> "Not sent: different field"
                            TextResult.NoField -> "Not sent: no field"
                            TextResult.NotSent -> "Not sent: offline"
                            TextResult.Uncertain -> "Connection dropped"
                        }
                    }
                }
                LightTextInputEditor(
                    title = message.ifEmpty { "Type on the Apple TV" }, state = text, keyboardOptionsFlow = options, submitLabel = "DONE", singleLine = true,
                    onSubmit = { v -> scope.launch { k.setText(id, v.toString()) }; leave() },
                    onBack = { leave() },
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                )
            }
        }
    }
}

// endregion

// region Menu

/** Short actions only. */
class MenuScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        val k = remote.controller
        val captions by k.captions.collectAsState()
        val note by k.captionNote.collectAsState()
        val power by k.power.collectAsState()
        // Reading the caption setting changes nothing, so it's asked for each time the menu opens.
        LaunchedEffect(Unit) { k.checkCaptions() }
        ListPage("Menu", back = { goBack() }) {
            Item("Captions", captionValue(captions), detail = note.ifEmpty { null }) { k.tapCaptions() }
            Item("Control Center") { goBack(); remote.press(Companion.Button.Home, holdMs = 1000) }
            Item("Power", powerValue(power)) { navigateTo(::PowerScreen) }
            Item("Settings") { navigateTo(::SettingsScreen) }
        }
    }
}

/** The Apple TV's sleep state, with Sleep and Wake both always offered. */
class PowerScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        val k = remote.controller
        val power by k.power.collectAsState()
        ListPage("Power", back = { goBack() }) {
            Item("Apple TV", powerValue(power))
            Item("Sleep") { k.setPower(false); goBack() }
            Item("Wake") { k.setPower(true); goBack() }
            Item("Check") { k.checkPower() }
        }
    }
}

class SettingsScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        val hints by remote.hints.collectAsState()
        val touchpad by remote.touchpad.collectAsState()
        val tv by remote.current.collectAsState()
        ListPage("Settings", back = { goBack() }) {
            Item("Show hints", if (hints) "On" else "Off") { remote.setHints(!hints) }
            Item("Touchpad", touchpad.label) { navigateTo(::TouchpadScreen) }
            Item("Apple TV", tv?.name ?: "") { navigateTo(::DeviceScreen) }
            Item("Advanced") { navigateTo(::AdvancedScreen) }
            Item("Help") { navigateTo(::HelpScreen) }
            Item("About") { navigateTo(::AboutScreen) }
        }
    }
}

/** How the touch surface moves the Apple TV's focus: each choice on its own row. */
class TouchpadScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        val touchpad by remote.touchpad.collectAsState()
        ListPage("Touchpad", back = { goBack() }) {
            Choice("Swipe", touchpad == Touchpad.Swipe, "Swipe to move") { remote.setTouchpad(Touchpad.Swipe) }
            Choice("Arrows", touchpad == Touchpad.Arrows, "Tap an edge to move") { remote.setTouchpad(Touchpad.Arrows) }
            Choice("Both", touchpad == Touchpad.Both, "Arrows and swiping") { remote.setTouchpad(Touchpad.Both) }
        }
    }
}

class DeviceScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        val tv by remote.current.collectAsState()
        val t = tv ?: return
        ListPage("Apple TV", back = { goBack() }) {
            Item("Name", t.name)
            if (t.model.isNotEmpty()) Item("Model", t.model)
            Item("Address", t.host)
            Item("Choose another Apple TV") { remote.disconnect() }
            Item("Forget pairing") { navigateTo(::ForgetScreen) }
        }
    }
}

/** The one confirmation: forgetting means typing the TV's code again. */
class ForgetScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        val tv by remote.current.collectAsState()
        val t = tv ?: return
        Themed {
            BackBar("Forget pairing") { goBack() }
            Note("Forget ${t.name}? Pairing again needs the code shown on the TV.", Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
            Box(Modifier.weight(1f))
            LightBottomBar(listOf(
                LightBarButton.Text("CANCEL", onClick = { goBack() }),
                LightBarButton.Text("FORGET", onClick = { remote.forget(t) }),
            ))
        }
    }
}

/** Client compatibility and what the remote currently knows (read-only). */
class AdvancedScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        val k = remote.controller
        val version by remote.clientVersion.collectAsState()
        val link by k.link.collectAsState()
        val media by k.media.collectAsState()
        val current by k.current.collectAsState()
        val captions by k.captions.collectAsState()
        val newer = version == Companion.CLIENT_VERSION_IOS18
        ListPage("Advanced", back = { goBack() }) {
            Item("Client", if (newer) "iOS 18" else "Standard", detail = "Announced to the Apple TV as $version. Change it only if the standard one won't connect.") {
                remote.setClientVersion(if (newer) Companion.CLIENT_VERSION_PYATV else Companion.CLIENT_VERSION_IOS18)
            }
            Heading("Diagnostics")
            Item("Connection", when (link) { Link.Connected -> "Connected"; Link.Connecting -> "Connecting…"; Link.Idle -> "Offline"; is Link.Failed -> "Failed" })
            Item("Media flags", media?.let { "0x%04X".format(it.flags) } ?: "None")
            Item("Player", if (current?.player != null) "Reported" else "None")
            Item("Playback rate", current?.rate?.let { "%.1f".format(it) } ?: "None")
            Item("Playback state", current?.state?.toString() ?: "None")
            Item("Captions offered", when (current?.captionOptions) { true -> "Yes"; false -> "No"; null -> "Not said" })
            Item("Captions", captionValue(captions))
            Item("Last update", current?.let { "${(System.currentTimeMillis() - it.receivedAt) / 1000} s ago" } ?: "None")
            Item("Debug log") { navigateTo(::LogScreen) }
        }
    }
}

/** The latest debug log lines, newest first. Nothing in it leaves the phone. */
class LogScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        var lines by remember { mutableStateOf(DebugLog.recent(200).reversed()) }
        ListPageWithBar("Debug log", back = { goBack() }, bar = listOf(
            LightBarButton.Text("REFRESH", onClick = { lines = DebugLog.recent(200).reversed() }),
            LightBarButton.Text("CLEAR", onClick = { DebugLog.clear(); lines = emptyList() }),
        )) {
            if (lines.isEmpty()) Note("Empty")
            for (l in lines) LightText(l, LightTextVariant.Micro, Modifier.fillMaxWidth().padding(vertical = 2.dp()), monospace = true)
        }
    }
    @Composable private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
}

class HelpScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    @Composable
    override fun Body() {
        ListPage("Help", back = { goBack() }) {
            Item("Swipe", "Move")
            Item("Tap", "Select")
            Item("Hold", "Options")
            Item("Hold TV", "Control Center")
            Item("Side keys", "Volume")
            Item("Arrows touchpad", "Tap an edge")
            Item("Both", "Arrows and swiping")
            Item("CC Off", "Foreign parts still shown")
            Item("Keyboard", "Type into the TV's field")
        }
    }
}

class AboutScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    override val needsTv = false

    @Composable
    override fun Body() {
        ListPage("About", back = { goBack() }) {
            val gap = Modifier.padding(bottom = 1f.gridUnitsAsDp())
            LightText("A remote for your Apple TV: swipe and tap on the touch surface, Back, TV and Play/Pause below, and the side keys for volume.", LightTextVariant.Paragraph, gap)
            LightText("It talks to the Apple TV directly over your Wi‑Fi. No account, no cloud: nothing leaves your home network.", LightTextVariant.Paragraph, gap)
            LightText("Unofficial. Not made by, affiliated with or endorsed by Apple. Apple TV is a trademark of Apple Inc. The Apple TV's remote protocol is implemented from the descriptions in the open-source pyatv project.", LightTextVariant.Paragraph, gap)
            LightText("Version ${Hub.VERSION}", LightTextVariant.Paragraph, lighten = true)
            LightText("Made by Slow Pixels", LightTextVariant.Paragraph, lighten = true)
        }
    }
}

// endregion
