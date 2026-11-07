package com.slowpixels.tvremote.net

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A record of what the remote sent and heard, for finding out why something
 * didn't work: each request and its answer (or silence), what the Apple TV
 * pushed (summarised: no artwork, no titles), and what was tapped where.
 *
 * Kept in memory (shown in Menu › Settings › Advanced › Debug log), written to
 * logcat (tag TVRemote), and to a file in the tool's own private folder.
 * Nothing leaves the phone.
 */
object DebugLog {
    private const val KEEP = 3000
    private const val FILE_MAX = 1_000_000L
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var enabled = true
    @Volatile var file: File? = null

    fun add(msg: String) {
        Logs.i(msg)
        if (!enabled) return
        val line = synchronized(time) { time.format(Date()) } + "  " + msg
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > KEEP) lines.removeFirst()
        }
        file?.let { f ->
            runCatching {
                if (f.length() > FILE_MAX) f.renameTo(File(f.parentFile, f.name + ".1"))
                f.appendText(line + "\n")
            }
        }
    }

    /** The latest [n] lines, newest last. */
    fun recent(n: Int = 300): List<String> = synchronized(lines) { lines.toList().takeLast(n) }

    fun clear() {
        synchronized(lines) { lines.clear() }
        file?.let { runCatching { it.writeText("") } }
    }

    /** A short, artwork-free description of a message's content. */
    fun summary(v: Any?, depth: Int = 0): String = when (v) {
        null -> "null"
        is ByteArray -> "<${v.size} bytes>"
        is String -> if (v.length > 40) "\"${v.take(40)}…\"" else "\"$v\""
        is Map<*, *> -> if (depth > 2) "{…}" else v.entries.joinToString(", ", "{", "}") { "${it.key}=${summary(it.value, depth + 1)}" }
        is List<*> -> if (depth > 2) "[…]" else v.joinToString(", ", "[", "]") { summary(it, depth + 1) }
        is Double -> "%.3f".format(Locale.US, v)
        else -> v.toString()
    }.let { if (it.length > 300) it.take(300) + "…" else it }
}
