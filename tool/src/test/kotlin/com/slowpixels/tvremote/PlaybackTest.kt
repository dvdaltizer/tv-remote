package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.*
import com.slowpixels.tvremote.model.Touchpad

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Archives shaped like the captures in pyatv issue 2325: full updates, and partial ones holding only the rate. */
object NowPlayingArchives {
    private val infoClass = mapOf("\$classname" to "TVRCNowPlayingInfo", "\$classes" to listOf("TVRCNowPlayingInfo", "NSObject"))
    private val metaClass = mapOf("\$classname" to "TVRCNowPlayingMetadata", "\$classes" to listOf("TVRCNowPlayingMetadata", "NSObject"))

    private fun archive(objects: List<Any>) = BinaryPlist.write(mapOf(
        "\$version" to 100000, "\$archiver" to "NSKeyedArchiver", "\$top" to mapOf("root" to Uid(1)), "\$objects" to objects,
    ))

    /** A full update with a position: [offset] seconds into [duration], at the Apple TV's [timestamp] (seconds since 2001). */
    fun timeline(player: String, rate: Double, duration: Double, offset: Double, timestamp: Double, captions: Boolean? = null) = archive(listOf(
        "\$null",
        mapOf("\$class" to Uid(10), "playerIdentifier" to Uid(2), "playbackRate" to Uid(3), "metadata" to Uid(4),
            "hasValidCaptionOptions" to Uid(when (captions) { true -> 11; false -> 12; null -> 0 })),
        player, rate,
        mapOf("\$class" to Uid(9), "title" to Uid(5), "duration" to Uid(6), "timeOffset" to Uid(7), "timestamp" to Uid(8)),
        "Dog Man", duration, offset, timestamp, metaClass, infoClass, true, false,
    ))

    /** Now, in the Apple TV's terms (seconds since 2001). */
    fun now2001(): Double = System.currentTimeMillis() / 1000.0 - 978_307_200.0

    /** A pause (0.0) or play (1.0), as tvOS sends it: just the rate. */
    fun rateOnly(rate: Double) = archive(listOf("\$null", mapOf("\$class" to Uid(3), "playbackRate" to Uid(2)), rate, infoClass))

    /** A full update; a null [player] is sent as `$null`, as in the YouTube capture. */
    fun full(player: String?, rate: Double?, state: Int?, title: String?, captions: Boolean? = null, captionsOn: Boolean? = null) = archive(listOf(
        "\$null",
        mapOf("\$class" to Uid(8), "playerIdentifier" to Uid(if (player != null) 2 else 0), "playbackRate" to Uid(if (rate != null) 3 else 0),
            "playbackState" to Uid(if (state != null) 4 else 0), "metadata" to Uid(if (title != null) 5 else 0),
            "hasValidCaptionOptions" to Uid(when (captions) { true -> 9; false -> 10; null -> 0 }),
            "captionsEnabled" to Uid(when (captionsOn) { true -> 9; false -> 10; null -> 0 })),
        player ?: "", rate ?: 0.0, state ?: 0,
        mapOf("\$class" to Uid(7), "title" to Uid(6)),
        title ?: "", metaClass, infoClass, true, false,
    ))
}

class PlaybackTest {
    private val me = Identity("11111111-2222-3333-4444-555555555555", ByteArray(32) { 3 })
    private lateinit var tv: FakeAppleTv
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun quiet() { Logs.sink = {} }
    @After fun done() { scope.cancel(); if (::tv.isInitialized) tv.close() }

    private suspend fun until(ms: Long = 3000, check: () -> Boolean) { withTimeout(ms) { while (!check()) delay(20) } }

    private fun info(player: String? = "avkit-1", rate: Double? = 1.0, state: Int? = 1, captions: Boolean? = true) =
        NowPlayingInfo.parse(NowPlayingArchives.full(player, rate, state, "Dog Man", captions = captions))!!

    /** Saved snapshots, standing in for the app's preferences across a restart. */
    private class MemoryStore : StaleStore {
        var saved: Set<String> = emptySet()
        override fun load() = saved
        override fun save(signatures: Set<String>) { saved = LinkedHashSet(signatures) }
    }

    private fun controller(store: StaleStore? = null) = Controller(scope, {
        Companion("127.0.0.1", tv.port, me, replyTimeoutMs = 400).also { it.connect(StoredPairing(tv.tvId, tv.publicKey)) }
    }, retryDelayMs = 50, pollMs = 100, pendingMs = 700, staleStore = store)

    private fun np(bytes: ByteArray) = mapOf("NowPlayingInfoKey" to bytes)
    private fun fetches() = tv.requests.count { it.first == "FetchCurrentNowPlayingInfoEvent" }

    // region Merging updates

    @Test fun aPauseKeepsThePlayerAndCaptions() {
        val paused = info().merge(NowPlayingUpdate.parse(NowPlayingArchives.rateOnly(0.0))!!)
        assertEquals("avkit-1", paused.player)
        assertEquals(0.0, paused.rate!!, 0.0)
        assertEquals(true, paused.captionOptions)
    }

    @Test fun anotherPlayerStartsAfresh() {
        val other = info().merge(NowPlayingUpdate.parse(NowPlayingArchives.full("avkit-2", 1.0, null, null))!!)
        assertEquals("avkit-2", other.player)
        assertNull("nothing carried over", other.state)
    }

    @Test fun aClearedPlayerClearsEverything() {
        val gone = info(captions = null).merge(NowPlayingUpdate.parse(NowPlayingArchives.full(null, null, null, null))!!)
        assertNull(gone.player); assertNull(gone.rate); assertNull(gone.state)
    }

    // endregion

    // region Over the connection

    @Test fun whatsPlayingIsAskedOnlyWhenConnecting() = runBlocking {
        // Re-asking brought back stale snapshots on David's Apple TV, so the remote listens instead.
        tv = FakeAppleTv()
        val k = controller()
        k.ensure()
        until { fetches() == 1 }
        repeat(3) { tv.push("SupportedActions", mapOf("GuideSupportedKey" to false)) }
        tv.push("_iMC", mapOf("_mcF" to 1806))
        delay(400)
        assertEquals(1, fetches())
        k.stop()
    }

    @Test fun netflixKeepsCcGreyedWithoutFlicker() = runBlocking {
        // David's log: Netflix sends "captions offered: no" between blank updates; the old Apple TV app snapshot says yes.
        val atvApp = NowPlayingArchives.timeline("avkit-1", 1.0, 5369.0, 3696.0, 783_000_000.0, captions = true)
        tv = FakeAppleTv()
        tv.reply = { name, _ ->
            if (name == "FetchCurrentNowPlayingInfoEvent") Thread { Thread.sleep(30); tv.push("NowPlayingInfo", np(atvApp)) }.start()
            emptyMap<String, Any>()
        }
        val k = controller()
        k.ensure()
        until { k.current.value?.captionOptions == true }
        tv.push("NowPlayingInfo", np(NowPlayingArchives.full(null, null, 2, null, captions = null)))
        tv.push("NowPlayingInfo", np(NowPlayingArchives.full(null, null, null, null, captions = false)))
        until { k.current.value?.captionOptions == false }
        val seen = mutableListOf<Boolean?>()
        val watch = scope.launch { k.current.collect { seen += it?.captionOptions } }
        repeat(3) {
            tv.push("NowPlayingInfo", np(NowPlayingArchives.full(null, null, 1 + it % 2, null, captions = null)))
            tv.push("NowPlayingInfo", np(atvApp)) // the stale snapshot again
        }
        delay(400)
        watch.cancel()
        assertTrue("never offered again: $seen", seen.all { it == false })
        k.stop()
    }

    @Test fun skipFlagsComeFromTheAppleTv() = runBlocking {
        // From David's logs: 256 (volume only) at the home screen, 1795 while a show plays.
        tv = FakeAppleTv()
        val k = controller()
        k.ensure()
        tv.push("_iMC", mapOf("_mcF" to 256))
        until { k.media.value?.flags == 256 }
        assertFalse(k.media.value!!.skipForward || k.media.value!!.skipBackward)
        tv.push("_iMC", mapOf("_mcF" to 1795))
        until { k.media.value?.flags == 1795 }
        assertTrue(k.media.value!!.skipForward && k.media.value!!.skipBackward)
        k.stop()
        assertNull("unknown again when disconnected", k.media.value)
    }

    // endregion

    // region Stale snapshots

    @Test fun aStaleSnapshotFromTheLastAppIsIgnored() = runBlocking {
        // David's log, 28 Oct: an Apple TV+ movie, then Netflix (which shares no player, only state 1/2),
        // while every "what's playing?" answer repeated the Apple TV+ snapshot unchanged.
        val atvPlus = NowPlayingArchives.timeline("avkit-1", 0.0, 5369.279, 3696.084, 783_000_000.0, captions = true)
        tv = FakeAppleTv()
        tv.reply = { name, _ ->
            if (name == "FetchCurrentNowPlayingInfoEvent") Thread { Thread.sleep(30); tv.push("NowPlayingInfo", np(atvPlus)) }.start()
            emptyMap<String, Any>()
        }
        val k = controller()
        k.ensure()
        until { k.current.value?.player == "avkit-1" }
        // Netflix: no player, state 2 (paused), then an all-empty update saying no captions.
        tv.push("NowPlayingInfo", np(NowPlayingArchives.full(null, null, 2, null)))
        tv.push("NowPlayingInfo", np(NowPlayingArchives.full(null, null, null, null, captions = false)))
        until { k.current.value?.player == null && k.current.value?.captionOptions == false }
        // The Apple TV answers with the old Apple TV+ snapshot again: old news.
        tv.push("NowPlayingInfo", np(atvPlus))
        delay(300)
        assertNull("doesn't go back to the Apple TV+ movie", k.current.value?.player)
        assertEquals(false, k.current.value?.captionOptions)
        // Even after reconnecting.
        tv.dropAll()
        until { k.link.value !is Link.Connected }
        k.ensure()
        delay(300)
        assertNull(k.current.value?.player)
        // Back in Apple TV+, playing on: a new snapshot (a new position) counts.
        tv.push("NowPlayingInfo", np(NowPlayingArchives.timeline("avkit-1", 1.0, 5369.279, 3710.0, 783_000_100.0)))
        until { k.current.value?.player == "avkit-1" }
        k.stop()
    }

    @Test fun aStaleSnapshotIsStillRecognisedAfterARestart() = runBlocking {
        val atvPlus = NowPlayingArchives.timeline("avkit-1", 0.0, 5369.279, 3696.084, 783_000_000.0, captions = true)
        tv = FakeAppleTv()
        tv.reply = { name, _ ->
            if (name == "FetchCurrentNowPlayingInfoEvent") Thread { Thread.sleep(30); tv.push("NowPlayingInfo", np(atvPlus)) }.start()
            emptyMap<String, Any>()
        }
        val store = MemoryStore()
        val first = controller(store)
        first.ensure()
        until { first.current.value?.player == "avkit-1" }
        tv.push("NowPlayingInfo", np(NowPlayingArchives.full(null, null, 2, null, captions = false))) // Netflix takes over
        until { store.saved.isNotEmpty() }
        first.stop()
        // The app restarts mid-Netflix: a new controller, the same saved preferences.
        val second = controller(store)
        second.ensure()
        until { fetches() == 2 }
        delay(300)
        assertNull("the Apple TV+ snapshot from the fetch is ignored", second.current.value?.player)
        assertTrue("CC isn't offered from it", second.current.value?.captionOptions != true)
        second.stop()
    }

    @Test fun onlyTheLatestReplacedSnapshotsAreKept() = runBlocking {
        tv = FakeAppleTv()
        val store = MemoryStore()
        val k = controller(store)
        k.ensure()
        repeat(StaleStore.KEEP + 5) { n ->
            tv.push("NowPlayingInfo", np(NowPlayingArchives.timeline("p$n", 1.0, 100.0, n.toDouble(), 783_000_000.0 + n)))
            tv.push("NowPlayingInfo", np(NowPlayingArchives.full(null, null, 1, null)))
        }
        until { store.saved.size == StaleStore.KEEP && store.saved.last().startsWith("p${StaleStore.KEEP + 4}|") }
        assertTrue("oldest dropped", store.saved.none { it.startsWith("p0|") })
        k.stop()
    }

    // endregion

    // region Devices and settings

    @Test fun onlyAppleTvsAreListed() {
        // From the Kompakt on David's office Wi-Fi: Macs advertise Companion with no model, and were listed as "Tap to pair".
        assertTrue(isAppleTv("AppleTV11,1"))
        assertTrue(isAppleTv("AppleTV14,1"))
        assertFalse(isAppleTv(""))
        assertFalse(isAppleTv(null))
        assertFalse(isAppleTv("MacBookPro18,1"))
        assertFalse(isAppleTv("AudioAccessory5,1"))
    }

    @Test fun touchpadChoices() {
        assertEquals(Touchpad.Swipe, Touchpad.from(null))
        assertEquals("an unknown saved choice reads as Swipe", Touchpad.Swipe, Touchpad.from("touch"))
        assertEquals(Touchpad.Both, Touchpad.from("both"))
        assertTrue(Touchpad.Both.showsArrows && Touchpad.Both.swipes)
        assertTrue(!Touchpad.Arrows.swipes && !Touchpad.Swipe.showsArrows)
    }

    // endregion
}
