package com.slowpixels.tvremote.net

/** An Apple TV found on the Wi‑Fi; [networkId] is its permanent "rpMRtID" (tvOS 16+), when announced. */
data class Tv(val name: String, val host: String, val port: Int, val model: String, val networkId: String? = null)

/** Whether a Companion service's model ("rpMd") is an Apple TV, e.g. "AppleTV11,1"; Macs send none. */
fun isAppleTv(model: String?) = model?.startsWith("AppleTV") == true
