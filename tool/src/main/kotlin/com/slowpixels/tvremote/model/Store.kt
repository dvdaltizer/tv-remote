package com.slowpixels.tvremote.model

import com.slowpixels.tvremote.net.KeyValues
import java.io.File
import java.util.Properties

/**
 * The tool's settings and pairings: one small file in its own storage, read
 * once and written whole after every change (to a part file first, then
 * swapped in, so a kill mid-write loses nothing).
 */
class Store(private val file: File) : KeyValues {
    private val p = Properties().also { props -> runCatching { file.inputStream().use(props::load) } }

    @Synchronized override fun get(key: String): String? = p.getProperty(key)
    @Synchronized override fun put(key: String, value: String) { p.setProperty(key, value); save() }
    @Synchronized override fun remove(key: String) { p.remove(key); save() }
    @Synchronized override fun keys(): Set<String> = p.stringPropertyNames().toSet()

    private fun save() {
        runCatching {
            val part = File(file.path + ".part")
            part.outputStream().use { p.store(it, null) }
            if (!part.renameTo(file)) part.delete()
        }
    }
}
