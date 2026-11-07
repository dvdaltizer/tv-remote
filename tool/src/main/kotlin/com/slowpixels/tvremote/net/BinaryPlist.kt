package com.slowpixels.tvremote.net

import java.io.ByteArrayOutputStream

/** A keyed-archive object reference (bplist UID). */
data class Uid(val index: Int)

/**
 * Apple's binary property list (bplist00), just enough for the keyboard's
 * keyed archives: dictionaries, arrays, strings, data, integers, booleans and
 * UIDs. Text is ASCII when it can be, otherwise UTF-16 (so any Unicode works).
 */
object BinaryPlist {
    fun write(root: Any): ByteArray {
        val objects = mutableListOf<Any>()
        fun flatten(v: Any): Int {
            val at = objects.size
            objects += v
            when (v) {
                is Map<*, *> -> {
                    val keys = v.keys.map { flatten(it as String) }
                    val values = v.values.map { flatten(it!!) }
                    objects[at] = Flat(keys + values, isDict = true)
                }
                is List<*> -> objects[at] = Flat(v.map { flatten(it!!) }, isDict = false)
            }
            return at
        }
        flatten(root)
        val refSize = if (objects.size < 256) 1 else 2
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val offsets = IntArray(objects.size)
        fun count(marker: Int, n: Int) {
            if (n < 15) out.write(marker or n) else { out.write(marker or 0xF); int(out, n.toLong()) }
        }
        objects.forEachIndexed { i, v ->
            offsets[i] = out.size()
            when (v) {
                is Boolean -> out.write(if (v) 0x09 else 0x08)
                is Int -> int(out, v.toLong())
                is Long -> int(out, v)
                is Double -> { out.write(0x23); val b = java.lang.Double.doubleToRawLongBits(v); for (k in 7 downTo 0) out.write((b ushr (8 * k)).toInt()) }
                is Uid -> { if (v.index < 256) { out.write(0x80); out.write(v.index) } else { out.write(0x81); out.write(v.index shr 8); out.write(v.index) } }
                is ByteArray -> { count(0x40, v.size); out.write(v) }
                is String -> if (v.all { it.code < 128 }) {
                    count(0x50, v.length); out.write(v.toByteArray(Charsets.US_ASCII))
                } else {
                    count(0x60, v.length); out.write(v.toByteArray(Charsets.UTF_16BE))
                }
                is Flat -> {
                    count(if (v.isDict) 0xD0 else 0xA0, if (v.isDict) v.refs.size / 2 else v.refs.size)
                    v.refs.forEach { r -> if (refSize == 1) out.write(r) else { out.write(r shr 8); out.write(r) } }
                }
                else -> error("bplist can't encode ${v::class.simpleName}")
            }
        }
        val tableAt = out.size()
        val offsetSize = if (tableAt < 256) 1 else if (tableAt < 65536) 2 else 4
        offsets.forEach { o -> for (b in offsetSize - 1 downTo 0) out.write(o ushr (8 * b)) }
        out.write(ByteArray(6))
        out.write(offsetSize); out.write(refSize)
        for (n in listOf(objects.size.toLong(), 0L, tableAt.toLong())) for (b in 7 downTo 0) out.write((n ushr (8 * b)).toInt())
        return out.toByteArray()
    }

    private class Flat(val refs: List<Int>, val isDict: Boolean)

    private fun int(out: ByteArrayOutputStream, n: Long) {
        val bytes = when { n in 0..0xFF -> 1; n in 0..0xFFFF -> 2; n in 0..0xFFFFFFFFL -> 4; else -> 8 }
        out.write(0x10 or Integer.numberOfTrailingZeros(bytes))
        for (b in bytes - 1 downTo 0) out.write((n ushr (8 * b)).toInt())
    }

    /**
     * Reads a bplist into Maps, Lists, Strings, ByteArrays, Longs, Doubles,
     * Booleans and [Uid]s (keyed-archive references stay references). Bounded:
     * every offset and length is checked, nesting is limited, and so is the
     * total work (shared objects can't blow it up). Malformed input fails with
     * an exception.
     */
    fun read(d: ByteArray, maxBytes: Int = 4 shl 20, maxVisits: Int = 50_000): Any? {
        require(d.size in 41..maxBytes && String(d, 0, 8, Charsets.US_ASCII) == "bplist00") { "Not a binary plist" }
        val t = d.size - 32
        val offsetSize = d[t + 6].toInt() and 0xFF
        val refSize = d[t + 7].toInt() and 0xFF
        require(offsetSize in 1..8 && refSize in 1..8) { "Bad plist trailer" }
        val count = be(d, t + 8, 8)
        val top = be(d, t + 16, 8)
        val table = be(d, t + 24, 8)
        require(count in 1..1_000_000 && top in 0 until count && table in 8 until t && table + count * offsetSize <= t) { "Bad plist trailer" }
        var visits = 0
        fun offset(i: Int): Int = be(d, table.toInt() + i * offsetSize, offsetSize).also { require(it in 8 until table) { "Bad plist offset" } }.toInt()
        fun check(at: Int, n: Long) = require(n >= 0 && at + n <= table) { "Bad plist length" }

        fun obj(i: Long, depth: Int): Any? {
            require(i in 0 until count && depth < 64 && ++visits <= maxVisits) { "Bad plist reference" }
            var at = offset(i.toInt())
            val marker = d[at++].toInt() and 0xFF
            fun length(): Long {
                val low = marker and 0xF
                if (low != 0xF) return low.toLong()
                val m = d[at++].toInt() and 0xF
                require(m <= 3) { "Bad plist length" }
                val n = 1 shl m
                check(at, n.toLong())
                return be(d, at, n).also { at += n }
            }
            return when (marker shr 4) {
                0x0 -> when (marker) { 0x08 -> false; 0x09 -> true; else -> null }
                0x1 -> { val n = 1 shl (marker and 0xF); require(n <= 8) { "Bad plist int" }; check(at, n.toLong()); be(d, at, n) }
                0x2 -> when (marker and 0xF) {
                    2 -> { check(at, 4); java.lang.Float.intBitsToFloat(be(d, at, 4).toInt()).toDouble() }
                    3 -> { check(at, 8); java.lang.Double.longBitsToDouble(be(d, at, 8)) }
                    else -> throw IllegalArgumentException("Bad plist real")
                }
                0x3 -> { check(at, 8); java.lang.Double.longBitsToDouble(be(d, at, 8)) } // a date: seconds since 2001
                0x4 -> { val n = length(); check(at, n); d.copyOfRange(at, at + n.toInt()) }
                0x5 -> { val n = length(); check(at, n); String(d, at, n.toInt(), Charsets.US_ASCII) }
                0x6 -> { val n = length(); check(at, n * 2); String(d, at, n.toInt() * 2, Charsets.UTF_16BE) }
                0x8 -> { val n = (marker and 0xF) + 1; check(at, n.toLong()); Uid(be(d, at, n).toInt()) }
                0xA -> {
                    val n = length(); check(at, n * refSize)
                    List(n.toInt()) { k -> obj(be(d, at + k * refSize, refSize), depth + 1) }
                }
                0xD -> {
                    val n = length(); check(at, n * 2 * refSize)
                    val map = LinkedHashMap<Any?, Any?>()
                    for (k in 0 until n.toInt()) {
                        map[obj(be(d, at + k * refSize, refSize), depth + 1)] = obj(be(d, at + (n.toInt() + k) * refSize, refSize), depth + 1)
                    }
                    map
                }
                else -> throw IllegalArgumentException("Unsupported plist type 0x%02x".format(marker))
            }
        }
        return obj(top, 0)
    }

    private fun be(d: ByteArray, at: Int, n: Int): Long {
        require(at >= 0 && at + n <= d.size) { "Bad plist offset" }
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (d[at + k].toLong() and 0xFF)
        return v
    }
}

/**
 * Reading an NSKeyedArchiver archive: values in its objects are either inline
 * or references ([Uid]) into `$objects`, and reference 0 (`$null`) means none.
 */
class KeyedArchive(private val objects: List<*>, val root: Map<*, *>?) {
    /** [key] of [obj], following one reference; null when missing, `$null`, or of another type. */
    fun get(obj: Map<*, *>?, key: String): Any? {
        val v = obj?.get(key)
        return if (v is Uid) objects.getOrNull(v.index)?.takeIf { v.index != 0 && it != "\$null" } else v
    }
    fun string(obj: Map<*, *>?, key: String) = (get(obj, key) as? String)?.takeIf { it.isNotBlank() }
    fun number(obj: Map<*, *>?, key: String) = (get(obj, key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
    fun map(obj: Map<*, *>?, key: String) = get(obj, key) as? Map<*, *>

    companion object {
        /** Opens an archive whose top object is "root"; null if it isn't a keyed archive. */
        fun open(data: ByteArray?): KeyedArchive? {
            data ?: return null
            val plist = runCatching { BinaryPlist.read(data) as? Map<*, *> }.getOrNull() ?: return null
            val objects = plist["\$objects"] as? List<*> ?: return null
            val top = plist["\$top"] as? Map<*, *> ?: return null
            val root = (top["root"] as? Uid)?.let { objects.getOrNull(it.index) as? Map<*, *> }
            return KeyedArchive(objects, root)
        }
    }
}

/**
 * The Apple TV's remote keyboard (RTI) archives, laid out as pyatv sends them,
 * and reading which text field is active from what the Apple TV sends.
 */
object Rti {
    /** Which field the Apple TV has open: its session and the text already in it. */
    class Field(val session: ByteArray, val text: String) {
        val id: String get() = session.joinToString("") { "%02x".format(it) }
    }

    /** Reads a `_tiD` keyed archive; null if it describes no field. */
    fun field(archive: ByteArray?): Field? {
        archive ?: return null
        val root = runCatching { BinaryPlist.read(archive) as? Map<*, *> }.getOrNull() ?: return null
        val objects = root["\$objects"] as? List<*> ?: return null
        fun follow(v: Any?): Any? = if (v is Uid) objects.getOrNull(v.index) else v
        fun path(vararg keys: String): Any? {
            var e: Any? = root["\$top"]
            for (k in keys) e = follow((e as? Map<*, *>)?.get(k) ?: return null)
            return e
        }
        val session = path("sessionUUID") as? ByteArray ?: return null
        val text = path("documentState", "docSt", "contextBeforeInput") as? String ?: ""
        return Field(session, text)
    }

    private fun classOf(name: String) = mapOf("\$classname" to name, "\$classes" to listOf(name, "NSObject"))

    /** Empties the field. */
    fun clear(session: ByteArray): ByteArray = BinaryPlist.write(mapOf(
        "\$version" to 100000,
        "\$archiver" to "RTIKeyedArchiver",
        "\$top" to mapOf("textOperations" to Uid(1)),
        "\$objects" to listOf(
            "\$null",
            mapOf("\$class" to Uid(7), "targetSessionUUID" to Uid(5), "keyboardOutput" to Uid(2), "textToAssert" to Uid(4)),
            mapOf("\$class" to Uid(3)),
            classOf("TIKeyboardOutput"),
            "",
            mapOf("NS.uuidbytes" to session, "\$class" to Uid(6)),
            classOf("NSUUID"),
            classOf("RTITextOperations"),
        ),
    ))

    /** Types [text] at the end of the field. */
    fun insert(session: ByteArray, text: String): ByteArray = BinaryPlist.write(mapOf(
        "\$version" to 100000,
        "\$archiver" to "RTIKeyedArchiver",
        "\$top" to mapOf("textOperations" to Uid(1)),
        "\$objects" to listOf(
            "\$null",
            mapOf("keyboardOutput" to Uid(2), "\$class" to Uid(7), "targetSessionUUID" to Uid(5)),
            mapOf("insertionText" to Uid(3), "\$class" to Uid(4)),
            text,
            classOf("TIKeyboardOutput"),
            mapOf("NS.uuidbytes" to session, "\$class" to Uid(6)),
            classOf("NSUUID"),
            classOf("RTITextOperations"),
        ),
    ))
}
