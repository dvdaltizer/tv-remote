package com.slowpixels.tvremote

import com.slowpixels.tvremote.model.Remote
import com.slowpixels.tvremote.model.Store
import com.slowpixels.tvremote.net.DebugLog
import com.thelightphone.sdk.LightVibrator
import com.thelightphone.sdk.SealedLightContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The remote for the life of the process, so it outlives the screens: LightOS
 * can drop and reopen a screen whenever it likes.
 */
object Hub {
    lateinit var remote: Remote
        private set
    var vibrator: LightVibrator? = null
        private set
    /** Whether the code screen or the keyboard is up, so the home screen doesn't open a second one. */
    var pairUp = false
    var keyboardUp = false
    /** As in lighttool.toml. */
    const val VERSION = "1.0.0"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The tool's first screen is up. */
    fun attach(context: SealedLightContext, vibrator: LightVibrator) {
        this.vibrator = vibrator
        if (::remote.isInitialized) return
        DebugLog.file = File(context.filesDir, "tvremote-log.txt")
        remote = Remote(Store(File(context.filesDir, "remote.properties")), scope)
    }

    /** A short buzz for a press, when the phone's haptics are on. */
    fun buzz(enabled: Boolean) { if (enabled) runCatching { vibrator?.click() } }
}
