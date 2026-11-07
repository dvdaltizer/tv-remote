package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

class RemoteTest {
    private val me = Identity("11111111-2222-3333-4444-555555555555", ByteArray(32) { 3 })
    private lateinit var tv: FakeAppleTv
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun quiet() { Logs.sink = {} }
    @After fun done() { scope.cancel(); if (::tv.isInitialized) tv.close() }

    private fun companion(timeout: Long = 400) = Companion("127.0.0.1", tv.port, me, replyTimeoutMs = timeout)
    private fun pairing(id: String? = tv.tvId, key: ByteArray = tv.publicKey) = StoredPairing(id, key)

    private fun controller(store: () -> StoredPairing = { pairing() }) =
        Controller(scope, { companion().also { it.connect(store()) } }, retryDelayMs = 50, pollMs = 100, pendingMs = 700)

    private suspend fun until(ms: Long = 3000, check: () -> Boolean) {
        withTimeout(ms) { while (!check()) delay(20) }
    }

    // region Pair-verify

    @Test fun verifiesTheAppleTv() = runBlocking {
        tv = FakeAppleTv()
        val c = companion()
        c.connect(pairing())
        assertEquals(tv.tvId, c.verifiedTvId)
        assertTrue(tv.names().containsAll(listOf("_systemInfo", "_sessionStart", "TVRCSessionStart", "_tiStart")))
        until { tv.events.any { it.first == "_interest" } }
        c.close()
    }

    @Test fun rejectsAWrongSignature() = runBlocking {
        tv = FakeAppleTv(signingSeed = ByteArray(32) { 9 })
        try { companion().connect(pairing()); fail("connected to an impostor") } catch (e: PeerRejected) {}
        delay(100)
        assertTrue("nothing is sent to an unverified device", tv.requests.isEmpty())
    }

    @Test fun rejectsAWrongIdentifier() = runBlocking {
        tv = FakeAppleTv(claimedId = "00:00:00:00:00:00", signingSeed = ByteArray(32) { 7 })
        try { companion().connect(pairing()); fail("connected to the wrong Apple TV") } catch (e: PeerRejected) {}
    }

    @Test fun oldRecordsAreVerifiedByKey() = runBlocking {
        tv = FakeAppleTv()
        val c = companion()
        c.connect(pairing(id = null))
        assertEquals("the proven identifier is learned", tv.tvId, c.verifiedTvId)
        c.close()
        try { companion().connect(pairing(id = null, key = Crypto.publicKey(ByteArray(32) { 1 }))); fail() } catch (e: PeerRejected) {}
    }

    // endregion

    // region Replies

    @Test fun errorRepliesAreRejections() = runBlocking {
        tv = FakeAppleTv()
        tv.reply = { name, _ -> if (name == "_hidC") mapOf("_em" to "nope") else emptyMap<String, Any>() }
        val c = companion(); c.connect(pairing())
        try { c.request("_hidC", mapOf("_hBtS" to 1)); fail() } catch (e: Rejected) { assertEquals("nope", e.reason); assertTrue(!e.maybeDelivered) }
        c.close()
    }

    @Test fun silenceIsUncertainAndALateReplyGoesNowhere() = runBlocking {
        tv = FakeAppleTv()
        tv.reply = { name, _ ->
            if (name == "slow") { Thread.sleep(500); mapOf("who" to "slow") } else mapOf("who" to name)
        }
        val c = companion(timeout = 200); c.connect(pairing())
        try { c.request("slow", emptyMap()); fail() } catch (e: NoReply) { assertTrue(e.maybeDelivered) }
        // The slow reply arrives now: it must not be taken as the answer to this one.
        assertEquals("fast", (c.request("fast", emptyMap(), 2000)["_c"] as Map<*, *>)["who"])
        c.close()
    }

    @Test fun concurrentRequestsGetTheirOwnReplies() = runBlocking {
        tv = FakeAppleTv()
        tv.reply = { name, _ -> mapOf("who" to name) }
        val c = companion(2000); c.connect(pairing())
        val names = (1..20).map { "r$it" }
        val got = names.map { n -> async(Dispatchers.Default) { (c.request(n, emptyMap())["_c"] as Map<*, *>)["who"] } }.awaitAll()
        assertEquals(names, got)
        c.close()
    }

    @Test fun aDroppedConnectionFailsWhatsWaiting() = runBlocking {
        tv = FakeAppleTv()
        tv.reply = { name, _ -> if (name == "doomed") FakeAppleTv.DROP else emptyMap<String, Any>() }
        val c = companion(3000); c.connect(pairing())
        try { c.request("doomed", emptyMap()); fail() } catch (e: NoReply) {}
        until { c.closed.value }
        try { c.request("after", emptyMap()); fail() } catch (e: NotConnected) { assertTrue(!e.maybeDelivered) }
    }

    @Test fun staleRepliesAreDropped() = runBlocking {
        tv = FakeAppleTv()
        val c = companion(); c.connect(pairing())
        c.dispatch(mapOf("_t" to 3, "_x" to 999999L, "_c" to emptyMap<String, Any>()))
        c.dispatch(mapOf("_t" to 3)) // no ID
        c.dispatch(mapOf("_x" to 1L)) // no type
        c.request("still", emptyMap())
        c.close()
    }

    // endregion

    // region Controller: connecting and commands

    @Test fun simultaneousCallersShareOneConnection() = runBlocking {
        tv = FakeAppleTv()
        val k = controller()
        val results = (1..8).map { async(Dispatchers.Default) { k.ensure() } }.awaitAll()
        assertTrue(results.all { it != null && it === results[0] })
        assertEquals(1, tv.connections.get())
        k.stop()
    }

    @Test fun retriesAreBounded() = runBlocking {
        tv = FakeAppleTv()
        tv.refuseConnections = 10
        val k = controller()
        assertNull(k.ensure())
        assertEquals(2, tv.connections.get())
        assertTrue(k.link.value is Link.Failed)
        assertEquals(Power.Disconnected, k.power.value)
    }

    @Test fun anImpostorIsNotRetried() = runBlocking {
        tv = FakeAppleTv(signingSeed = ByteArray(32) { 9 })
        val k = controller()
        assertNull(k.ensure())
        assertEquals(1, tv.connections.get())
        assertTrue((k.link.value as Link.Failed).wrongTv)
    }

    @Test fun aPressWhoseDownMayNotHaveArrivedIsReleasedButNeverRepeated() = runBlocking {
        tv = FakeAppleTv()
        tv.reply = { name, _ -> if (name == "_hidC") FakeAppleTv.NO_REPLY else emptyMap<String, Any>() }
        val k = controller()
        k.press(Companion.Button.Select)
        until { k.notice.value.isNotEmpty() }
        delay(600)
        val hid = tv.requests.filter { it.first == "_hidC" }.map { it.second["_hBtS"] }
        assertEquals("one down, then an up so nothing stays held", listOf(1L, 2L), hid)
        assertTrue(k.notice.value.contains("no answer, not resent"))
        k.stop()
    }

    @Test fun aCommandAfterADropReconnectsAndIsSentOnce() = runBlocking {
        tv = FakeAppleTv()
        val k = controller()
        k.ensure()
        tv.dropAll()
        until { k.link.value !is Link.Connected }
        k.press(Companion.Button.PlayPause)
        until { tv.names().count { it == "_hidC" } == 2 }
        delay(200)
        assertEquals("down and up, once", 2, tv.names().count { it == "_hidC" })
        assertEquals(2, tv.connections.get())
        k.stop()
    }

    // endregion

    // region Power

    private fun powerTv(state: () -> Any?): FakeAppleTv = FakeAppleTv().also { t ->
        t.reply = { name, _ -> if (name == "FetchAttentionState") state() else emptyMap<String, Any>() }
    }

    @Test fun powerStartsUnknownAndFollowsTheAppleTv() = runBlocking {
        var state: Any? = mapOf("state" to 3)
        tv = powerTv { state }
        val k = controller()
        k.ensure()
        until { k.power.value == Power.Awake }
        tv.push("SystemStatus", mapOf("state" to 1))
        until { k.power.value == Power.Asleep }
        tv.push("TVSystemStatus", mapOf("state" to 2))
        until { k.power.value == Power.Awake }
        k.stop()
        assertEquals(Power.Disconnected, k.power.value)
    }

    @Test fun unansweredPowerIsUnknownNotOn() = runBlocking {
        tv = powerTv { mapOf("_em" to "unsupported") }
        val k = controller()
        k.ensure()
        until { k.power.value is Power.Unknown && (k.power.value as Power.Unknown).reason != "Checking…" }
        k.stop()
    }

    @Test fun sleepIsPendingUntilConfirmed() = runBlocking {
        var state: Any? = mapOf("state" to 3)
        tv = powerTv { state }
        val k = controller()
        k.ensure()
        until { k.power.value == Power.Awake }
        k.setPower(false)
        until { k.power.value is Power.Pending }
        until { tv.requests.any { it.first == "_hidC" } }
        val sent = tv.requests.first { it.first == "_hidC" }.second
        assertEquals(12L, sent["_hidC"]); assertEquals(2L, sent["_hBtS"])
        state = mapOf("state" to 1)
        until { k.power.value == Power.Asleep }
        k.stop()
    }

    @Test fun aDoubleTapOnPowerSendsOneSleep() = runBlocking {
        var state: Any? = mapOf("state" to 3)
        tv = powerTv { state }
        val k = controller()
        k.ensure()
        until { k.power.value == Power.Awake }
        k.setPower(false); k.setPower(false); k.setPower(true)
        until { tv.requests.any { it.first == "_hidC" } }
        state = mapOf("state" to 1)
        until { k.power.value == Power.Asleep }
        delay(300)
        assertEquals("one Sleep, nothing else", listOf(12L), tv.requests.filter { it.first == "_hidC" }.map { it.second["_hidC"] })
        // Once confirmed, the next tap works.
        k.setPower(true)
        until { tv.requests.count { it.first == "_hidC" } == 2 }
        k.stop()
    }

    @Test fun anUnconfirmedSleepShowsWhatTheAppleTvLastSaid() = runBlocking {
        tv = powerTv { mapOf("state" to 3) }
        val k = controller()
        k.ensure()
        until { k.power.value == Power.Awake }
        k.setPower(false)
        until { k.power.value is Power.Pending }
        until(3000) { k.power.value !is Power.Pending }
        assertEquals(Power.Awake, k.power.value)
        assertTrue(k.notice.value.contains("Didn't sleep"))
        k.stop()
    }

    @Test fun aPowerCommandWithNoAnswerIsUnknown() = runBlocking {
        tv = FakeAppleTv()
        tv.reply = { name, _ -> when (name) { "FetchAttentionState" -> mapOf("state" to 1); "_hidC" -> FakeAppleTv.NO_REPLY; else -> emptyMap<String, Any>() } }
        val k = controller()
        k.ensure()
        until { k.power.value == Power.Asleep }
        k.setPower(true)
        until(3000) { k.power.value is Power.Unknown }
        k.stop()
    }

    // endregion

    // region Typing

    private val sessionA = ByteArray(16) { 0xA }
    private val sessionB = ByteArray(16) { 0xB }

    private fun textTv(field: () -> ByteArray?): FakeAppleTv = FakeAppleTv().also { t ->
        t.reply = { name, _ ->
            if (name == "_tiStart") field()?.let { mapOf("_tiD" to it) } ?: emptyMap<String, Any>() else emptyMap<String, Any>()
        }
    }

    /** What each `_tiC` did: "clear" or the inserted text. */
    private fun typed(): List<String> = tv.events.filter { it.first == "_tiC" }.map { (_, c) ->
        val root = BinaryPlist.read(c["_tiD"] as ByteArray) as Map<*, *>
        val objects = root["\$objects"] as List<*>
        val ops = objects[((root["\$top"] as Map<*, *>)["textOperations"] as Uid).index] as Map<*, *>
        if (ops.containsKey("textToAssert")) "clear"
        else ((objects[((objects[(ops["keyboardOutput"] as Uid).index] as Map<*, *>)["insertionText"] as Uid).index]) as String)
    }

    @Test fun typingSendsOnlyWhatChanged() = runBlocking {
        tv = textTv { FakeAppleTv.textArchive(sessionA, "") }
        val k = controller()
        k.ensure()
        val id = (k.keyboard.value as Keyboard.Field).id
        assertEquals(TextResult.Sent, k.setText(id, "ted"))
        // The Apple TV's field now holds what was typed.
        tv.reply = { name, _ -> if (name == "_tiStart") mapOf("_tiD" to FakeAppleTv.textArchive(sessionA, "ted")) else emptyMap<String, Any>() }
        assertEquals(TextResult.Sent, k.setText(id, "ted lasso"))
        until { typed().size == 2 }
        assertEquals(listOf("ted", " lasso"), typed())
        k.stop()
    }

    @Test fun deletingReplacesTheText() = runBlocking {
        tv = textTv { FakeAppleTv.textArchive(sessionA, "severance") }
        val k = controller()
        k.ensure()
        val id = (k.keyboard.value as Keyboard.Field).id
        assertEquals(TextResult.Sent, k.setText(id, "sever"))
        until { typed().size == 2 }
        assertEquals(listOf("clear", "sever"), typed())
        k.stop()
    }

    @Test fun nothingIsTypedIntoADifferentField() = runBlocking {
        tv = textTv { FakeAppleTv.textArchive(sessionA, "") }
        val k = controller()
        k.ensure()
        val id = (k.keyboard.value as Keyboard.Field).id
        tv.push("_tiStarted", mapOf("_tiD" to FakeAppleTv.textArchive(sessionB, "")))
        until { (k.keyboard.value as Keyboard.Field).id != id }
        assertEquals(TextResult.FieldChanged, k.setText(id, "secret"))
        delay(100)
        assertTrue(typed().isEmpty())
        k.stop()
    }

    @Test fun noFieldMeansNothingTyped() = runBlocking {
        tv = textTv { FakeAppleTv.textArchive(sessionA, "") }
        val k = controller()
        k.ensure()
        val id = (k.keyboard.value as Keyboard.Field).id
        tv.push("_tiStopped", emptyMap())
        until { k.keyboard.value == Keyboard.NoField }
        assertEquals(TextResult.NoField, k.setText(id, "x"))
        delay(100)
        assertTrue(typed().isEmpty())
        k.stop()
    }

    @Test fun keyboardEventsFollowTheAppleTv() = runBlocking {
        tv = textTv { null }
        val k = controller()
        k.ensure()
        assertEquals(Keyboard.NoField, k.keyboard.value)
        tv.push("_tiStarted", mapOf("_tiD" to FakeAppleTv.textArchive(sessionB, "hi")))
        until { (k.keyboard.value as? Keyboard.Field)?.text == "hi" }
        tv.push("_tiStopped", emptyMap())
        until { k.keyboard.value == Keyboard.NoField }
        k.stop()
        assertTrue(k.keyboard.value is Keyboard.Unavailable)
    }

    @Test fun unicodeAndCompositionSurvive() = runBlocking {
        tv = textTv { FakeAppleTv.textArchive(sessionA, "") }
        val k = controller()
        k.ensure()
        val id = (k.keyboard.value as Keyboard.Field).id
        // Composing text replaced by its final form (e.g. "ka" → "か") goes as clear + the whole text.
        assertEquals(TextResult.Sent, k.setText(id, "ka"))
        tv.reply = { name, _ -> if (name == "_tiStart") mapOf("_tiD" to FakeAppleTv.textArchive(sessionA, "ka")) else emptyMap<String, Any>() }
        assertEquals(TextResult.Sent, k.setText(id, "か 🎬 café"))
        until { typed().size == 3 }
        assertEquals(listOf("ka", "clear", "か 🎬 café"), typed())
        k.stop()
    }

    // endregion

    // region Holds

    private fun hids() = tv.requests.filter { it.first == "_hidC" }.map { (it.second["_hidC"] as Long) to (it.second["_hBtS"] as Long) }

    private fun holdController() = Controller(scope, { companion().also { it.connect(pairing()) } },
        retryDelayMs = 50, pollMs = 100, pendingMs = 700, minHoldMs = 300, maxHoldMs = 1500)

    @Test fun aHoldStaysDownUntilReleasedAndLastsLongEnough() = runBlocking {
        tv = FakeAppleTv()
        val k = holdController()
        k.ensure()
        val h = k.hold(Companion.Button.Select)
        until { hids().size == 1 }
        delay(500)
        assertEquals("still held", listOf(6L to 1L), hids())
        val releasedAt = System.currentTimeMillis()
        h.release()
        until { hids().size == 2 }
        assertEquals(listOf(6L to 1L, 6L to 2L), hids())
        assertTrue(System.currentTimeMillis() - releasedAt < 300)
        k.stop()
    }

    @Test fun stoppingWhileAHoldIsFinishingIsSafe() = runBlocking {
        // Controlled order: the Apple TV has the release, but its answer is held back, so the hold is still
        // finishing (still listed) when the app stops. Then the answer goes through and the hold removes itself.
        val answerUp = java.util.concurrent.CountDownLatch(1)
        tv = FakeAppleTv()
        tv.reply = { name, c -> if (name == "_hidC" && c["_hBtS"] == 2L) answerUp.await(); emptyMap<String, Any>() }
        val k = holdController()
        k.ensure()
        val h = k.hold(Companion.Button.Select)
        until { hids().size == 1 }
        h.release()
        until(2000) { hids().size == 2 } // the release reached the Apple TV; its answer hasn't come back
        k.stop()                         // the hold is still finishing here
        answerUp.countDown()
        delay(400)
        assertEquals("one down, one up, nothing repeated", listOf(6L to 1L, 6L to 2L), hids())
        assertEquals(Link.Idle, k.link.value)
    }

    @Test fun aQuickHoldIsStillLongEnoughToBeAHold() = runBlocking {
        tv = FakeAppleTv()
        val k = holdController()
        k.ensure()
        val h = k.hold(Companion.Button.Home)
        until { hids().size == 1 }
        val downAt = System.currentTimeMillis()
        h.release()
        until { hids().size == 2 }
        assertTrue("the Apple TV sees at least the minimum hold", System.currentTimeMillis() - downAt >= 250)
        assertEquals(listOf(7L to 1L, 7L to 2L), hids())
        k.stop()
    }

    @Test fun stoppingTheAppLetsGoOfAHoldBeforeClosing() = runBlocking {
        tv = FakeAppleTv()
        val k = holdController()
        k.ensure()
        k.hold(Companion.Button.Select)
        until { hids().size == 1 }
        k.stop()
        until { hids().size == 2 }
        assertEquals(6L to 2L, hids()[1])
    }

    @Test fun anInterruptedGestureLetsGoAtOnce() = runBlocking {
        tv = FakeAppleTv()
        val k = holdController()
        k.ensure()
        val h = k.hold(Companion.Button.Select)
        until { hids().size == 1 }
        h.release(cancelled = true)
        h.release() // a second release changes nothing
        until { hids().size == 2 }
        delay(400)
        assertEquals(2, hids().size)
        k.stop()
    }

    @Test fun aHoldIsLetGoOnItsOwnEventually() = runBlocking {
        tv = FakeAppleTv()
        val k = holdController()
        k.ensure()
        k.hold(Companion.Button.Select)
        until(3000) { hids().size == 2 }
        k.stop()
    }

    @Test fun aDroppedConnectionEndsAHoldWithoutRepeatingIt() = runBlocking {
        tv = FakeAppleTv()
        val k = holdController()
        k.ensure()
        val h = k.hold(Companion.Button.Select)
        until { hids().size == 1 }
        tv.dropAll()
        until { k.link.value !is Link.Connected }
        h.release()
        delay(500)
        assertEquals("no down again on a new connection", 1, hids().count { it.second == 1L })
        k.stop()
    }

    @Test fun aHoldLetGoBeforeItCouldBeSentSendsNothing() = runBlocking {
        tv = FakeAppleTv()
        tv.refuseConnections = 1 // the first attempt fails, so the hold waits for the retry
        val k = holdController()
        val h = k.hold(Companion.Button.Select)
        h.release()
        delay(600)
        assertTrue(hids().isEmpty())
        k.stop()
    }

    @Test fun repeatedHoldsEachSendOneDownAndOneUp() = runBlocking {
        tv = FakeAppleTv()
        val k = holdController()
        k.ensure()
        repeat(3) { n ->
            val h = k.hold(Companion.Button.Select)
            until { hids().size == n * 2 + 1 }
            h.release(cancelled = true)
            until { hids().size == n * 2 + 2 }
        }
        delay(300)
        assertEquals(List(3) { listOf(6L to 1L, 6L to 2L) }.flatten(), hids())
        k.stop()
    }

    // endregion

    // region Keyboard opening by itself

    @Test fun theKeyboardOpensOnlyInFrontAndOncePerField() = runBlocking {
        tv = textTv { null }
        val k = controller()
        k.ensure()
        tv.push("_tiStarted", mapOf("_tiD" to FakeAppleTv.textArchive(sessionA, "")))
        until { k.keyboard.value is Keyboard.Field }
        delay(100)
        assertNull("not while the app is in the back", k.prompt.value)
        k.foreground.value = true
        until { k.prompt.value != null }
        val a = k.prompt.value!!
        // The same field announced again: nothing changes.
        tv.push("_tiStarted", mapOf("_tiD" to FakeAppleTv.textArchive(sessionA, "")))
        delay(150)
        assertEquals(a, k.prompt.value)
        // Dismissed: stays closed, even when the same field is announced again.
        k.dismissPrompt(a)
        tv.push("_tiStarted", mapOf("_tiD" to FakeAppleTv.textArchive(sessionA, "")))
        delay(150)
        assertNull(k.prompt.value)
        // A new field opens it again.
        tv.push("_tiStarted", mapOf("_tiD" to FakeAppleTv.textArchive(sessionB, "")))
        until { k.prompt.value != null && k.prompt.value != a }
        // The field closing closes it.
        tv.push("_tiStopped", emptyMap())
        until { k.prompt.value == null }
        k.stop()
    }

    @Test fun theKeyboardClosesOnDisconnectAndADismissedFieldStaysDismissed() = runBlocking {
        tv = textTv { FakeAppleTv.textArchive(sessionA, "") }
        val k = controller()
        k.foreground.value = true
        k.ensure()
        until { k.prompt.value != null }
        k.dismissPrompt(k.prompt.value)
        k.stop()
        assertNull(k.prompt.value)
        k.ensure()
        delay(200)
        assertNull("the same field after reconnecting doesn't reopen", k.prompt.value)
        k.stop()
    }

    @Test fun typedTextIsRememberedForTheField() = runBlocking {
        tv = textTv { FakeAppleTv.textArchive(sessionA, "abc") }
        val k = controller()
        k.ensure()
        val id = (k.keyboard.value as Keyboard.Field).id
        assertEquals("abc", k.textFor(id))
        k.setText(id, "abcd")
        assertEquals("abcd", k.textFor(id))
        assertEquals("", k.textFor("ffff"))
        k.stop()
    }

    // endregion

    // region Media controls, volume and captions

    /** A fake whose volume (0–1) and media flags can be set; [setWorks] false ignores SetVolume. */
    private class Audio(var volume: Double? = 0.5, var setWorks: Boolean = true, var getRefused: Boolean = false)

    private fun audioTv(a: Audio): FakeAppleTv = FakeAppleTv().also { t ->
        t.reply = { name, c ->
            if (name != "_mcc") emptyMap<String, Any>()
            else when ((c["_mcc"] as Number).toInt()) {
                Companion.GET_VOLUME -> if (a.getRefused) mapOf("_em" to "no volume") else a.volume?.let { mapOf("_vol" to it) } ?: emptyMap<String, Any>()
                Companion.SET_VOLUME -> { if (a.setWorks) a.volume = (c["_vol"] as Number).toDouble(); emptyMap<String, Any>() }
                Companion.GET_CAPTION_SETTINGS -> mapOf("_em" to "unsupported")
                else -> emptyMap<String, Any>()
            }
        }
    }

    @Test fun mediaFlagsComeOnlyFromTheAppleTv() = runBlocking {
        tv = audioTv(Audio())
        val k = controller()
        k.ensure()
        assertNull("unknown until the Apple TV says", k.media.value)
        tv.push("_iMC", mapOf("_mcF" to (0x0200 or 0x0002 or 0x0100)))
        until { k.media.value != null }
        val m = k.media.value!!
        assertTrue(m.skipForward && m.pause && m.volume)
        assertTrue(!m.skipBackward && !m.play)
        k.stop()
        assertNull("forgotten when disconnected", k.media.value)
    }

    @Test fun muteSetsZeroAndUnmuteRestoresOnlyWhenConfirmed() = runBlocking {
        val a = Audio(volume = 0.5)
        tv = audioTv(a)
        val k = controller()
        k.ensure()
        until { k.mute.value is Mute.Unmuted }
        k.toggleMute()
        until { k.mute.value is Mute.Pending }
        until { k.mute.value == Mute.Muted }
        assertEquals(0.0, a.volume!!, 0.0)
        k.toggleMute()
        until { k.mute.value is Mute.Unmuted }
        assertEquals(0.5, a.volume!!, 0.0)
        k.stop()
    }

    @Test fun anUnconfirmedMuteIsNotShownAsMuted() = runBlocking {
        tv = audioTv(Audio(volume = 0.4, setWorks = false))
        val k = controller()
        k.ensure()
        until { k.mute.value is Mute.Unmuted }
        k.toggleMute()
        until { k.mute.value is Mute.Pending }
        until(3000) { k.mute.value !is Mute.Pending }
        assertTrue(k.mute.value is Mute.Unmuted)
        assertTrue(k.notice.value.contains("didn't change"))
        k.stop()
    }

    @Test fun muteIsUnavailableWhenTheAppleTvDoesntControlVolume() = runBlocking {
        val a = Audio(getRefused = true)
        tv = audioTv(a)
        val k = controller()
        k.ensure()
        until { k.mute.value is Mute.Unavailable }
        k.toggleMute() // does nothing
        delay(200)
        assertTrue(tv.requests.none { it.first == "_mcc" && (it.second["_mcc"] as Number).toInt() == Companion.SET_VOLUME })
        // Volume available, then the flags say it isn't (switched to the TV's speakers, say).
        a.getRefused = false
        tv.push("_iMC", mapOf("_mcF" to 0x0100))
        until { k.mute.value is Mute.Unmuted }
        tv.push("_iMC", mapOf("_mcF" to 0x0001))
        until { k.mute.value is Mute.Unavailable }
        k.stop()
        assertEquals(Mute.Disconnected, k.mute.value)
    }

    @Test fun aDoubleTapOnMuteSendsOneChange() = runBlocking {
        val a = Audio(volume = 0.5)
        tv = audioTv(a)
        val k = controller()
        k.ensure()
        until { k.mute.value is Mute.Unmuted }
        k.toggleMute(); k.toggleMute(); k.toggleMute()
        until { k.mute.value == Mute.Muted }
        delay(300)
        assertEquals(1, tv.requests.count { it.first == "_mcc" && (it.second["_mcc"] as Number).toInt() == Companion.SET_VOLUME })
        // Once confirmed, the next tap works (unmute), again just once.
        k.toggleMute(); k.toggleMute()
        until { k.mute.value is Mute.Unmuted }
        delay(300)
        assertEquals(2, tv.requests.count { it.first == "_mcc" && (it.second["_mcc"] as Number).toInt() == Companion.SET_VOLUME })
        k.stop()
    }

    @Test fun aMuteWithNoAnswerSaysSoAndIsntRetried() = runBlocking {
        val a = Audio(volume = 0.5)
        tv = audioTv(a)
        tv.reply = { name, c ->
            if (name == "_mcc" && (c["_mcc"] as Number).toInt() == Companion.GET_VOLUME && a.volume == 0.0) FakeAppleTv.NO_REPLY
            else if (name == "_mcc" && (c["_mcc"] as Number).toInt() == Companion.SET_VOLUME) { a.volume = 0.0; emptyMap<String, Any>() }
            else if (name == "_mcc" && (c["_mcc"] as Number).toInt() == Companion.GET_VOLUME) mapOf("_vol" to a.volume!!)
            else emptyMap<String, Any>()
        }
        val k = controller()
        k.ensure()
        until { k.mute.value is Mute.Unmuted }
        k.toggleMute()
        until(4000) { k.mute.value is Mute.Unknown }
        assertEquals("Mute: no confirmation", k.notice.value)
        assertEquals(1, tv.requests.count { it.first == "_mcc" && (it.second["_mcc"] as Number).toInt() == Companion.SET_VOLUME })
        k.stop()
    }

    @Test fun mutedWithNoKnownLevelDoesntGuessOne() = runBlocking {
        tv = audioTv(Audio(volume = 0.0))
        val k = controller()
        k.ensure()
        until { k.mute.value == Mute.Muted }
        k.toggleMute()
        delay(200)
        assertEquals(Mute.Muted, k.mute.value)
        assertTrue(k.notice.value.contains("side buttons"))
        k.stop()
    }

    // endregion

    // region Archives

    @Test fun archivesRoundTrip() {
        val f = Rti.field(FakeAppleTv.textArchive(sessionA, "naïve 🍿"))
        assertNotNull(f)
        assertArrayEquals(sessionA, f!!.session)
        assertEquals("naïve 🍿", f.text)
        assertNull(Rti.field(byteArrayOf(1, 2, 3)))
        assertNull(Rti.field(null))
        // Big enough for two-byte references and offsets.
        val big = (1..300).map { "item $it" }
        assertEquals(big, BinaryPlist.read(BinaryPlist.write(big)))
    }

    @Test fun signaturesAreChecked() {
        val seed = ByteArray(32) { 5 }
        val sig = Crypto.sign(seed, "hello".toByteArray())
        assertTrue(Crypto.verify(Crypto.publicKey(seed), "hello".toByteArray(), sig))
        assertTrue(!Crypto.verify(Crypto.publicKey(seed), "hellp".toByteArray(), sig))
        assertTrue(!Crypto.verify(ByteArray(5), "hello".toByteArray(), sig))
        assertTrue(!Crypto.verify(Crypto.publicKey(seed), "hello".toByteArray(), ByteArray(3)))
    }

    // endregion
}

/** Writes the archives for scripts/check_rti.py to compare with pyatv's own encoder. */
class RtiExport {
    @Test fun export() {
        val dir = java.io.File("build/rti").apply { mkdirs() }
        val session = ByteArray(16) { it.toByte() }
        java.io.File(dir, "clear.bplist").writeBytes(Rti.clear(session))
        java.io.File(dir, "insert.bplist").writeBytes(Rti.insert(session, "café 🎬 か"))
    }
}
