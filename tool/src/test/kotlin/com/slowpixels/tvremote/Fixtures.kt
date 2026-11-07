package com.slowpixels.tvremote

/** Captures from a real Apple TV, in src/test/resources/fixtures. */
object Fixtures {
    fun read(name: String): ByteArray =
        Thread.currentThread().contextClassLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()
}
