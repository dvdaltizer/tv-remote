package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CaptionsNowPlayingTest {
    private val me = Identity("11111111-2222-3333-4444-555555555555", ByteArray(32) { 3 })
    private lateinit var tv: FakeAppleTv
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun quiet() { Logs.sink = {} }
    @After fun done() { scope.cancel(); if (::tv.isInitialized) tv.close() }

    private fun fixture(name: String): ByteArray = Fixtures.read(name)

    private fun controller(version: String = Companion.CLIENT_VERSION_PYATV) = Controller(scope, {
        Companion("127.0.0.1", tv.port, me, replyTimeoutMs = 400, clientVersion = version).also { it.connect(StoredPairing(tv.tvId, tv.publicKey)) }
    }, retryDelayMs = 50, pollMs = 100, pendingMs = 700)

    private suspend fun until(ms: Long = 3000, check: () -> Boolean) {
        withTimeout(ms) { while (!check()) delay(20) }
    }

    private fun mcc(code: Int) = tv.requests.filter { it.first == "_mcc" && (it.second["_mcc"] as Number).toInt() == code }

    // region Captions

    /** A fake caption setting: SetCaptionSettings with _mcs 4 flips always on and forced only, as captured in pyatv issue 2325. */
    private class CaptionTv(var mode: Int = 2, var get: String = "ok", var set: String = "ok")

    private fun captionTv(c: CaptionTv) = FakeAppleTv().also { t ->
        t.reply = { name, content ->
            if (name != "_mcc") emptyMap<String, Any>()
            else when ((content["_mcc"] as Number).toInt()) {
                Companion.GET_CAPTION_SETTINGS -> when (c.get) {
                    "refuse" -> mapOf("_em" to "unsupported"); "silent" -> FakeAppleTv.NO_REPLY; else -> mapOf("_mcs" to c.mode)
                }
                Companion.SET_CAPTION_SETTINGS -> {
                    val flip = { if ((content["_mcs"] as Number).toInt() == 4) c.mode = if (c.mode == 2) 3 else 2 }
                    when (c.set) {
                        "refuse" -> mapOf("_em" to "not now")
                        "ignore" -> emptyMap<String, Any>()
                        "silent-but-done" -> { flip(); FakeAppleTv.NO_REPLY }
                        else -> { flip(); emptyMap<String, Any>() }
                    }
                }
                else -> emptyMap<String, Any>()
            }
        }
    }

    private suspend fun checked(k: Controller): Captions {
        k.ensure()
        k.checkCaptions()
        until { k.captions.value !is Captions.Unknown && k.captions.value != Captions.Checking }
        return k.captions.value
    }

    @Test fun theSettingIsReadNotGuessed() = runBlocking {
        tv = captionTv(CaptionTv(mode = 3))
        val k = controller()
        k.ensure()
        until { k.captions.value is Captions.Mode }
        assertEquals("read when connecting", 3, (k.captions.value as Captions.Mode).code)
        assertTrue(mcc(Companion.SET_CAPTION_SETTINGS).isEmpty())
        k.stop()
        assertEquals(Captions.Disconnected, k.captions.value)
    }

    @Test fun oneTapSwitchesEvenBeforeTheSettingIsKnown() = runBlocking {
        val c = CaptionTv(mode = 2, get = "silent")
        tv = captionTv(c)
        val k = controller()
        k.ensure()
        until { k.captions.value is Captions.Unknown && (k.captions.value as Captions.Unknown).reason.contains("didn't answer") }
        c.get = "ok"
        k.pressCaptions()
        until { (k.captions.value as? Captions.Mode)?.code == 3 }
        assertEquals("read, then one switch", 1, mcc(Companion.SET_CAPTION_SETTINGS).size)
        k.stop()
    }

    @Test fun aSwitchIsShownOnlyAsReadBack() = runBlocking {
        tv = captionTv(CaptionTv(mode = 2))
        val k = controller()
        checked(k)
        k.toggleCaptions()
        until { k.captions.value == Captions.Switching }
        until { (k.captions.value as? Captions.Mode)?.code == 3 }
        assertEquals("a confirmed change needs no note", "", k.captionNote.value)
        assertEquals("Off", captionName(3))
        val sets = mcc(Companion.SET_CAPTION_SETTINGS)
        assertEquals(1, sets.size)
        assertEquals(4L, sets[0].second["_mcs"])
        k.stop()
    }

    @Test fun anAnsweredSwitchThatChangedNothingSaysSo() = runBlocking {
        tv = captionTv(CaptionTv(mode = 2, set = "ignore"))
        val k = controller()
        checked(k)
        k.toggleCaptions()
        until { k.captionNote.value.isNotEmpty() }
        assertEquals(2, (k.captions.value as Captions.Mode).code)
        assertEquals("Unchanged", k.captionNote.value)
        k.stop()
    }

    @Test fun aRefusedSwitchIsAnError() = runBlocking {
        tv = captionTv(CaptionTv(mode = 3, set = "refuse"))
        val k = controller()
        checked(k)
        k.toggleCaptions()
        until { k.captionNote.value.isNotEmpty() }
        assertEquals("Refused", k.captionNote.value)
        assertEquals(3, (k.captions.value as Captions.Mode).code)
        k.stop()
    }

    @Test fun anUnansweredSwitchIsNeverSentAgain() = runBlocking {
        val c = CaptionTv(mode = 2, set = "silent-but-done")
        tv = captionTv(c)
        val k = controller()
        checked(k)
        k.toggleCaptions()
        until { k.captionNote.value.isNotEmpty() }
        delay(600)
        assertEquals("one switch only", 1, mcc(Companion.SET_CAPTION_SETTINGS).size)
        assertEquals("No answer, not resent", k.captionNote.value)
        assertEquals("shows what it reads back", 3, (k.captions.value as Captions.Mode).code)
        assertEquals(3, c.mode)
        k.stop()
    }

    @Test fun anUnreadableSettingCantBeSwitched() = runBlocking {
        tv = captionTv(CaptionTv(get = "refuse"))
        val k = controller()
        assertTrue(checked(k) is Captions.Unavailable)
        k.toggleCaptions()
        delay(200)
        assertTrue(mcc(Companion.SET_CAPTION_SETTINGS).isEmpty())
        k.stop()

        tv.close()
        tv = captionTv(CaptionTv(get = "silent"))
        val k2 = controller()
        k2.ensure()
        k2.checkCaptions()
        until { k2.captions.value is Captions.Unknown && (k2.captions.value as Captions.Unknown).reason.contains("didn't answer") }
        k2.toggleCaptions()
        delay(200)
        assertTrue(mcc(Companion.SET_CAPTION_SETTINGS).isEmpty())
        k2.stop()
    }

    @Test fun switchingWhileDisconnectedSendsNothing() = runBlocking {
        tv = captionTv(CaptionTv(mode = 2))
        val k = controller()
        checked(k)
        tv.dropAll()
        until { k.captions.value == Captions.Disconnected }
        k.toggleCaptions()
        delay(300)
        assertTrue(mcc(Companion.SET_CAPTION_SETTINGS).isEmpty())
        k.stop()
    }

    @Test fun captionSettingsReadAsAppleNamesThem() {
        assertEquals(listOf("Auto", "On", "Off"), listOf(1, 2, 3).map { captionName(it) })
        assertTrue(captionName(9).startsWith("Unknown"))
    }

    @Test fun captionAvailabilityComesFromThePlayer() {
        val ep = NowPlayingInfo.parse(fixture("nowplaying-episode.bplist"))!!
        assertEquals(true, ep.captionOptions); assertEquals(true, ep.captionsOn)
        val empty = NowPlayingInfo.parse(fixture("nowplaying-empty.bplist"))!!
        assertNull("not reported: CC can't be offered", empty.captionOptions)
    }

    // endregion

    // region Now Playing: decoding the captures

    @Test fun anEpisodeIsDecoded() {
        val i = NowPlayingInfo.parse(fixture("nowplaying-episode.bplist"))!!
        assertEquals("avkit-0000", i.player)
        assertEquals(1.0, i.rate!!, 0.0)
        assertEquals(true, i.captionOptions)
        assertEquals(true, i.captionsOn)
    }

    @Test fun anotherCaptureIsDecoded() {
        val i = NowPlayingInfo.parse(fixture("nowplaying-netflix.bplist"))!!
        assertEquals("avkit-1111", i.player)
        assertEquals(true, i.captionOptions)
    }

    @Test fun anUpdateWithoutAPlayerSaysNothing() {
        val i = NowPlayingInfo.parse(fixture("nowplaying-empty.bplist"))!!
        assertNull(i.player)
        assertNull("not said: CC isn't greyed by it", i.captionOptions)
    }

    @Test fun badArchivesAreRejectedNotGuessed() {
        assertNull(NowPlayingInfo.parse(null))
        assertNull(NowPlayingInfo.parse(byteArrayOf(0, 1, 2)))
        assertNull(NowPlayingInfo.parse("bplist00".toByteArray() + ByteArray(40)))
        val good = fixture("nowplaying-episode.bplist")
        for (cut in listOf(20, good.size / 2, good.size - 1)) assertNull(NowPlayingInfo.parse(good.copyOf(cut)))
        // Not a keyed archive (no $top root).
        assertNull(NowPlayingInfo.parse(BinaryPlist.write(mapOf("a" to 1))))
    }

    @Test fun aLargeEventFromPyatvsEncoderDecodesWithoutStrayBytes() {
        val event = Opack.unpack(fixture("nowplaying-large-event.opack")) as Map<*, *>
        val key = (event["_c"] as Map<*, *>)["NowPlayingInfoKey"] as ByteArray
        assertArrayEquals(fixture("nowplaying-large.bplist"), key)
        assertEquals("bplist00", String(key, 0, 8))
        assertEquals("avkit-0000", NowPlayingInfo.parse(key)!!.player)
    }

    // endregion

    // region Now Playing: following it

    private fun episodeEvent() = mapOf("NowPlayingInfoKey" to fixture("nowplaying-episode.bplist"))

    private fun nowPlayingTv(onFetch: (FakeAppleTv) -> Any? = { t -> Thread { Thread.sleep(50); t.push("NowPlayingInfo", mapOf("NowPlayingInfoKey" to fixture("nowplaying-episode.bplist"))) }.start(); emptyMap<String, Any>() }) =
        FakeAppleTv().also { t -> t.reply = { name, _ -> if (name == "FetchCurrentNowPlayingInfoEvent") onFetch(t) else emptyMap<String, Any>() } }

    private fun interest() = tv.events.filter { it.first == "_interest" && (it.second["_regEvents"] as? List<*>)?.contains("NowPlayingInfo") == true }

    @Test fun whatsPlayingIsFollowedFromConnecting() = runBlocking {
        tv = nowPlayingTv()
        val k = controller()
        k.ensure()
        until { k.current.value?.player == "avkit-0000" }
        assertEquals("subscribed when connecting", 1, interest().size)
        tv.push("NowPlayingInfo", mapOf("NowPlayingInfoKey" to fixture("nowplaying-netflix.bplist")))
        until { k.current.value?.player == "avkit-1111" }
        k.stop()
        assertNull("forgotten on disconnect", k.current.value)
    }

    @Test fun eachUpdateReplacesTheLastWhole() = runBlocking {
        tv = nowPlayingTv()
        val k = controller()
        k.ensure()
        until { k.current.value?.player == "avkit-0000" }
        tv.push("NowPlayingInfo", episodeEvent()) // a duplicate
        delay(150)
        assertEquals("avkit-0000", k.current.value?.player)
        // Nothing playing any more.
        tv.push("NowPlayingInfo", mapOf("NowPlayingInfoKey" to fixture("nowplaying-empty.bplist")))
        until { k.current.value?.player == null }
        // Something unreadable changes nothing.
        tv.push("NowPlayingInfo", mapOf("NowPlayingInfoKey" to byteArrayOf(1, 2, 3)))
        tv.push("NowPlayingInfo", emptyMap())
        delay(200)
        assertNull(k.current.value?.player)
        k.stop()
    }

    @Test fun detailsAreDroppedOnDisconnectAndAskedForAgain() = runBlocking {
        tv = nowPlayingTv()
        val k = controller()
        k.ensure()
        until { k.current.value?.player == "avkit-0000" }
        tv.dropAll()
        until { k.current.value == null }
        assertTrue("a drop says so", (k.link.value as? Link.Failed)?.message == "Connection lost.")
        k.ensure()
        until { k.current.value?.player == "avkit-0000" }
        assertEquals("once per connection", 2, tv.requests.count { it.first == "FetchCurrentNowPlayingInfoEvent" })
        k.stop()
    }

    @Test fun aLargeUpdateArrivesWhole() = runBlocking {
        tv = nowPlayingTv { t -> Thread { Thread.sleep(50); t.pushRaw(fixture("nowplaying-large-event.opack")) }.start(); emptyMap<String, Any>() }
        val k = controller()
        k.ensure()
        until { k.current.value?.player == "avkit-0000" }
        k.stop()
    }

    // endregion

    @Test fun eventsDuringConnectingAreNotLost() = runBlocking {
        tv = FakeAppleTv()
        // Pushed while connecting, before anything is listening (as tvOS sends _iMC soon after the session starts).
        tv.reply = { name, _ -> if (name == "TVRCSessionStart") tv.push("_iMC", mapOf("_mcF" to 0x0200)); emptyMap<String, Any>() }
        val k = controller()
        k.ensure()
        until { k.media.value != null }
        assertTrue(k.media.value!!.skipForward)
        k.stop()
    }

    // region Client version

    @Test fun theClientVersionIsWhatWasChosen() = runBlocking {
        tv = FakeAppleTv()
        val a = controller()
        a.ensure()
        assertEquals("170.18", tv.requests.first { it.first == "_systemInfo" }.second["_sv"])
        a.stop()
        tv.requests.clear()
        val b = controller(Companion.CLIENT_VERSION_IOS18)
        b.ensure()
        assertEquals("600.41.1", tv.requests.first { it.first == "_systemInfo" }.second["_sv"])
        b.stop()
    }

    // endregion
}

/** The decoders on bad or large input: bounded, never past the end, matching pyatv's encoding. */
class DecoderBoundsTest {
    private fun bad(block: () -> Unit) {
        try { block(); fail("accepted bad input") } catch (e: IllegalArgumentException) {}
    }

    @Test fun largeDataUsesFourLengthBytes() {
        val data = ByteArray(70_000) { it.toByte() }
        val packed = Opack.pack(data)
        assertEquals(0x93, packed[0].toInt() and 0xFF)
        assertEquals(70_000, (packed[1].toInt() and 0xFF) or ((packed[2].toInt() and 0xFF) shl 8) or ((packed[3].toInt() and 0xFF) shl 16) or ((packed[4].toInt() and 0xFF) shl 24))
        assertArrayEquals(data, Opack.unpack(packed) as ByteArray)
        // pyatv's own test vector.
        assertArrayEquals(ByteArray(65536) { 0x61 }, Opack.unpack(byteArrayOf(0x93.toByte(), 0, 0, 1, 0) + ByteArray(65536) { 0x61 }) as ByteArray)
        val text = "é".repeat(40_000)
        assertEquals(text, Opack.unpack(Opack.pack(text)))
    }

    @Test fun containersAreNotBackReferenceTargets() {
        // {"k": ["x"], "r": <ref 2>} — objects are "k", "x", "r", so ref 2 is "r" (as in pyatv).
        val d = byteArrayOf(0xE2.toByte(), 0x41, 'k'.code.toByte(), 0xD1.toByte(), 0x41, 'x'.code.toByte(), 0x41, 'r'.code.toByte(), 0xA2.toByte())
        assertEquals(mapOf("k" to listOf("x"), "r" to "r"), Opack.unpack(d))
    }

    @Test fun malformedOpackIsRejected() {
        bad { Opack.unpack(byteArrayOf()) }
        bad { Opack.unpack(byteArrayOf(0x93.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 1, 2)) } // claims 2 GB
        bad { Opack.unpack(byteArrayOf(0x94.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) }
        bad { Opack.unpack(byteArrayOf(0x45, 'a'.code.toByte())) } // string cut short
        bad { Opack.unpack(byteArrayOf(0xA5.toByte())) } // reference to nothing
        bad { Opack.unpack(byteArrayOf(0xE1.toByte(), 0x41, 'k'.code.toByte())) } // dictionary missing its value
        bad { Opack.unpack(byteArrayOf(0xDF.toByte(), 0x09)) } // endless list never ended
        bad { Opack.unpack(byteArrayOf(0x07)) } // unknown type
        bad { Opack.unpack(ByteArray(10_000) { 0xD1.toByte() }) } // nested without end
    }

    /** A bplist of [levels] arrays, each holding [width] references to the next: tiny, but huge if expanded. */
    private fun sharedBomb(levels: Int, width: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val offsets = mutableListOf<Int>()
        for (k in 0 until levels) { offsets += out.size(); out.write(0xA0 or width); repeat(width) { out.write(k + 1) } }
        offsets += out.size(); out.write(0x10); out.write(1)
        val table = out.size()
        offsets.forEach { out.write(it) }
        out.write(ByteArray(6)); out.write(1); out.write(1)
        for (n in listOf(offsets.size.toLong(), 0L, table.toLong())) for (b in 7 downTo 0) out.write((n ushr (8 * b)).toInt())
        return out.toByteArray()
    }

    @Test fun malformedPlistsAreRejected() {
        bad { BinaryPlist.read("bplist00".toByteArray() + ByteArray(40)) }
        bad { BinaryPlist.read(ByteArray(100)) }
        assertNotNull(BinaryPlist.read(sharedBomb(3, 10))) // 1,000 visits: fine
        bad { BinaryPlist.read(sharedBomb(8, 10)) } // 100 million if expanded: refused
        val good = BinaryPlist.write(mapOf("a" to listOf(1, 2.5, "ü")))
        val wrecked = good.copyOf().also { it[it.size - 9] = 0x7F } // table offset past the end
        bad { BinaryPlist.read(wrecked) }
        bad { BinaryPlist.read(good, maxBytes = 10) }
    }

    @Test fun realsAndDatesAreRead() {
        // plistlib-encoded captures carry floats (the position, "timeOffset") — read as doubles.
        val u = NowPlayingUpdate.parse(Fixtures.read("nowplaying-netflix.bplist"))!!
        assertEquals(1234.5, u.timeOffset!!, 1e-9)
    }
}
