package com.slowpixels.tvremote.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OPACK, Apple's compact binary format for Companion messages (dictionaries of
 * strings, numbers, data and lists). Encoding never uses back-references;
 * decoding understands them, as the Apple TV sends them.
 */
object Opack {
    fun pack(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun le(n: Long, bytes: Int) = ByteArray(bytes) { i -> (n ushr (8 * i)).toByte() }

    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(0x04)
            true -> out.write(0x01)
            false -> out.write(0x02)
            is Int -> writeInt(out, v.toLong())
            is Long -> writeInt(out, v)
            is Double -> { out.write(0x36); out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()) }
            is Float -> { out.write(0x35); out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()) }
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                when {
                    b.size <= 0x20 -> out.write(0x40 + b.size)
                    b.size <= 0xFF -> { out.write(0x61); out.write(le(b.size.toLong(), 1)) }
                    b.size <= 0xFFFF -> { out.write(0x62); out.write(le(b.size.toLong(), 2)) }
                    else -> { out.write(0x64); out.write(le(b.size.toLong(), 4)) }
                }
                out.write(b)
            }
            is ByteArray -> {
                when {
                    v.size <= 0x20 -> out.write(0x70 + v.size)
                    v.size <= 0xFF -> { out.write(0x91); out.write(le(v.size.toLong(), 1)) }
                    v.size <= 0xFFFF -> { out.write(0x92); out.write(le(v.size.toLong(), 2)) }
                    else -> { out.write(0x93); out.write(le(v.size.toLong(), 4)) }
                }
                out.write(v)
            }
            is List<*> -> {
                if (v.size < 15) out.write(0xD0 + v.size) else out.write(0xDF)
                v.forEach { write(out, it) }
                if (v.size >= 15) out.write(0x03)
            }
            is Map<*, *> -> {
                if (v.size < 15) out.write(0xE0 + v.size) else out.write(0xEF)
                for ((k, x) in v) { write(out, k); write(out, x) }
                if (v.size >= 15) out.write(0x03)
            }
            else -> error("OPACK can't encode ${v::class.simpleName}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, n: Long) {
        when {
            n in 0..0x27 -> out.write(0x08 + n.toInt())
            n in 0..0xFF -> { out.write(0x30); out.write(le(n, 1)) }
            n in 0..0xFFFF -> { out.write(0x31); out.write(le(n, 2)) }
            n in 0..0xFFFFFFFFL -> { out.write(0x32); out.write(le(n, 4)) }
            else -> { out.write(0x33); out.write(le(n, 8)) }
        }
    }

    fun unpack(data: ByteArray): Any? = Reader(data).read(0)

    /**
     * Decoding as pyatv does: data lengths take 1, 2, 4 or 8 bytes (0x91–0x94),
     * string lengths 1–4 (0x61–0x64); lists and dictionaries aren't
     * back-reference targets. Malformed input fails with an exception (never
     * reads past the end, never nests without limit).
     */
    private class Reader(val d: ByteArray) {
        var i = 0
        val objects = mutableListOf<Any?>()

        fun need(n: Long) { if (n < 0 || n > d.size - i) throw IllegalArgumentException("OPACK: truncated") }
        fun u(n: Int): Long { need(n.toLong()); var v = 0L; for (k in 0 until n) v = v or ((d[i + k].toLong() and 0xFF) shl (8 * k)); i += n; return v }
        fun bytes(n: Long): ByteArray { need(n); return d.copyOfRange(i, i + n.toInt()).also { i += n.toInt() } }
        fun ref(n: Long): Any? {
            if (n < 0 || n >= objects.size) throw IllegalArgumentException("OPACK: bad reference")
            return objects[n.toInt()]
        }

        fun read(depth: Int): Any? {
            if (depth > 32) throw IllegalArgumentException("OPACK: nested too deep")
            need(1)
            val t = d[i++].toInt() and 0xFF
            var add = true
            val v: Any? = when {
                t == 0x01 -> { add = false; true }
                t == 0x02 -> { add = false; false }
                t == 0x04 -> { add = false; null }
                t == 0x05 -> bytes(16)
                t == 0x06 -> u(8)
                t in 0x08..0x2F -> { add = false; (t - 8).toLong() }
                t == 0x35 -> ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
                t == 0x36 -> ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).double
                t in 0x30..0x33 -> u(1 shl (t and 0xF))
                t in 0x40..0x60 -> String(bytes((t - 0x40).toLong()), Charsets.UTF_8)
                t in 0x61..0x64 -> String(bytes(u(t and 0xF)), Charsets.UTF_8)
                t in 0x70..0x90 -> bytes((t - 0x70).toLong())
                t in 0x91..0x94 -> bytes(u(1 shl ((t and 0xF) - 1)))
                t in 0xA0..0xC0 -> { add = false; ref((t - 0xA0).toLong()) }
                t in 0xC1..0xC4 -> { add = false; ref(u(t - 0xC0)) }
                t and 0xF0 == 0xD0 -> {
                    add = false
                    val n = t and 0xF
                    val list = mutableListOf<Any?>()
                    if (n == 0xF) { while (true) { need(1); if (d[i].toInt() and 0xFF == 0x03) break; list += read(depth + 1) }; i++ }
                    else repeat(n) { list += read(depth + 1) }
                    list
                }
                t and 0xF0 == 0xE0 -> {
                    add = false
                    val n = t and 0xF
                    val map = LinkedHashMap<Any?, Any?>()
                    if (n == 0xF) { while (true) { need(1); if (d[i].toInt() and 0xFF == 0x03) break; val k = read(depth + 1); map[k] = read(depth + 1) }; i++ }
                    else repeat(n) { val k = read(depth + 1); map[k] = read(depth + 1) }
                    map
                }
                else -> throw IllegalArgumentException("OPACK: unknown type 0x%02x".format(t))
            }
            if (add && objects.none { it == v || (it is ByteArray && v is ByteArray && it.contentEquals(v)) }) objects += v
            return v
        }
    }
}

/** HomeKit's TLV8: type, length (up to 255) and value; longer values continue in more items of the same type. */
object Tlv8 {
    fun write(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((t, v) in items) {
            if (v.isEmpty()) { out.write(t); out.write(0); continue }
            var at = 0
            while (at < v.size) {
                val n = minOf(255, v.size - at)
                out.write(t); out.write(n); out.write(v, at, n)
                at += n
            }
        }
        return out.toByteArray()
    }

    fun read(d: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var i = 0
        var last = -1
        while (i + 1 < d.size) {
            val t = d[i].toInt() and 0xFF
            val n = d[i + 1].toInt() and 0xFF
            val v = d.copyOfRange(i + 2, i + 2 + n)
            out[t] = if (t == last && out[t] != null) out[t]!! + v else v
            last = t
            i += 2 + n
        }
        return out
    }

    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val NAME = 0x11
}
