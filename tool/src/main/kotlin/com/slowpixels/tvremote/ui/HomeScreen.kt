package com.slowpixels.tvremote.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.slowpixels.tvremote.Hub
import com.slowpixels.tvremote.model.Remote
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay

/** What a screen opens: a factory for the next one. */
typealias Opener = ((SealedLightActivity) -> SimpleLightScreen<Unit>) -> Unit

/**
 * The tool's first screen: the Apple TVs on the Wi‑Fi until one is chosen,
 * then the remote for it. An Apple TV asking for its code brings up the code
 * screen; a text field opening on the Apple TV brings up the keyboard.
 */
@InitialScreen
class HomeScreen(sealed: SealedLightActivity) : TvPage(sealed) {
    override val needsTv = false
    init { Hub.attach(lightContext, sealed.vibrator) }

    @Composable
    override fun Body() {
        val current by remote.current.collectAsState()
        val pairing by remote.pairing.collectAsState()
        val prompt by remote.controller.prompt.collectAsState()
        LaunchedEffect(pairing) { if (pairing != null && !Hub.pairUp) navigateTo(::PairScreen) }
        LaunchedEffect(prompt, current) { if (prompt != null && current != null && !Hub.keyboardUp) navigateTo({ KeyboardScreen(it, auto = true) }) }
        var wifi by remember { mutableStateOf<Boolean?>(null) }
        if (current == null) LaunchedEffect(Unit) {
            while (true) { wifi = runCatching { lightContext.connectivity.currentStatus.isWifi }.getOrNull(); delay(3000) }
        }
        Themed {
            val tv = current
            if (tv == null) TvList(remote, wifi, open = { navigateTo(it) }) else RemoteFace(remote, tv, open = { navigateTo(it) })
        }
    }
}

/**
 * Apple TVs on this Wi‑Fi: tap one to pair (the first time) or control it.
 * Says plainly what's going on for someone who doesn't know how it works:
 * how to start the first time, and what to do when their Apple TV isn't here.
 */
@Composable
private fun ColumnScope.TvList(remote: Remote, wifi: Boolean?, open: Opener) {
    val tvs by remote.tvs.collectAsState()
    val status by remote.status.collectAsState()
    LightTopBar(center = LightTopBarCenter.Text("TV Remote"), rightButton = LightBarButton.Text("ABOUT", onClick = { open(::AboutScreen) }))
    val paired = remember(tvs) { remote.pairedNames() }
    val missing = paired.filter { name -> tvs.none { it.name == name } }
    // Give the Wi‑Fi a few seconds to answer before saying something isn't here (at home it's usually found in one).
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(5000); searched = true }
    val awayFromAll = paired.isNotEmpty() && missing.size == paired.size
    LightScrollView(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
        when {
            paired.isEmpty() -> {
                Heading("Control your Apple TV")
                Note("This phone works as a remote for an Apple TV on the same Wi‑Fi.")
                Note("1. Turn on the Apple TV.\n2. Connect this phone to the same Wi‑Fi.\n3. Choose the Apple TV below, then type the code it shows on the TV.")
                if (tvs.isEmpty()) Note(when {
                    wifi == false -> "This phone isn't on Wi‑Fi."
                    searched -> "No Apple TV found yet. Check that both are on the same Wi‑Fi, or add it by address."
                    else -> "Looking for Apple TVs…"
                })
            }
            awayFromAll && !searched -> Note("Looking for ${missing.joinToString(" and ")}…")
            awayFromAll -> {
                val name = if (missing.size == 1) missing[0] else "Your Apple TV"
                Heading("$name isn't on this Wi‑Fi")
                Note(if (wifi == false) "This phone isn't on Wi‑Fi." else "To use it, connect this phone to the same Wi‑Fi as the Apple TV, and check the TV is on.")
                if (tvs.isEmpty()) Note("No other Apple TV is on this Wi‑Fi.")
                else Note("Or choose an Apple TV below and type the code it shows. $name stays paired.")
            }
            else -> Heading("Choose an Apple TV")
        }
        for (tv in tvs) Item(tv.name, if (remote.isPaired(tv)) "Paired" else "Tap to pair", caps = false) { remote.use(tv) }
        if (status.isNotEmpty()) Note(status)
    }
    LightBottomBar(listOf(LightBarButton.Text("BY ADDRESS", onClick = { open(::AddressScreen) })))
}
