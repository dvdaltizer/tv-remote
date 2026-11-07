package com.slowpixels.tvremote

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: a fake Apple TV left running on a fixed port for trying the tool
 * in the simulator (with the Mac's Bonjour announcing it: see docs in README).
 * Runs only with STAND=<port> in the environment, until the file build/stand.stop appears.
 */
class Stand {
    @Test fun stand() {
        val port = System.getenv("STAND")?.toIntOrNull()
        assumeTrue(port != null)
        val stop = File("build/stand.stop").also { it.delete() }
        val tv = FakeAppleTv(pin = "1234", fixedPort = port!!)
        // A search field is open on this "TV", with "dog" typed in it, so the keyboard can be tried.
        val field = FakeAppleTv.textArchive(ByteArray(16) { 0x5 }, "dog")
        tv.reply = { name, _ -> when (name) {
            "FetchAttentionState" -> mapOf("state" to 3)
            "_mcc" -> mapOf("_vol" to 0.4, "_mcs" to 2)
            "_tiStart" -> mapOf("_tiD" to field)
            else -> emptyMap<String, Any>()
        } }
        println("fake Apple TV on port ${tv.port}, code 1234")
        while (!stop.exists()) Thread.sleep(500)
        println("requests: ${tv.names()}")
        tv.close()
    }
}
