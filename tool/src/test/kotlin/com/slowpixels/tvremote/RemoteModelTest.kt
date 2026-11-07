package com.slowpixels.tvremote

import com.slowpixels.tvremote.model.Remote
import com.slowpixels.tvremote.net.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The remote's state with a stand-in finder: what's listed, what connects by itself, what a typed address does. */
class RemoteModelTest {
    private class MapKv(val m: MutableMap<String, String> = mutableMapOf()) : KeyValues {
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun keys() = m.keys.toSet()
    }

    /** Answers each ask with the services it's been given, like a Wi‑Fi full of Apple devices. */
    private class FakeFinder(val found: (Service) -> Unit) : TvFinder {
        var services = listOf<Service>()
        val direct = mutableSetOf<String>()
        var asks = 0
        var closed = false
        override fun ask() { asks++; services.forEach(found) }
        override fun addDirect(address: String) { direct += address }
        override fun removeDirect(address: String) { direct -= address }
        override fun close() { closed = true }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var finder: FakeFinder
    private lateinit var tv: FakeAppleTv
    private val kv = MapKv()

    @Before fun quiet() { Logs.sink = {} }
    @After fun done() { scope.cancel(); if (::tv.isInitialized) tv.close() }

    private fun remote() = Remote(kv, scope) { found -> FakeFinder(found).also { finder = it } }
    private suspend fun until(ms: Long = 3000, check: () -> Boolean) { withTimeout(ms) { while (!check()) delay(20) } }

    private val livingRoom = Service("Living Room", "127.0.0.1", 49152, "AppleTV11,1", "NET-1")
    private val mac = Service("David’s MacBook", "192.168.1.3", 49153, "", null)
    private val homePod = Service("Kitchen", "192.168.1.4", 49154, "AudioAccessory5,1", null)

    @Test fun onlyAppleTvsAreListedAndTheIdentityIsKept() = runBlocking {
        val r = remote()
        r.resume()
        finder.services = listOf(mac, livingRoom, homePod)
        until { r.tvs.value.isNotEmpty() }
        delay(100)
        assertEquals(listOf("Living Room"), r.tvs.value.map { it.name })
        assertTrue(!r.isPaired(r.tvs.value[0]))
        val id = r.identity.pairingId
        r.pause()
        assertTrue(finder.closed)
        assertEquals(id, Remote(kv, scope) { FakeFinder(it) }.identity.pairingId)
        assertEquals(kv.m["pairingId"], id)
    }

    @Test fun pairingThenConnectingByItselfNextTime() = runBlocking {
        tv = FakeAppleTv(pin = "2468")
        val r = remote()
        r.resume()
        finder.services = listOf(livingRoom.copy(port = tv.port))
        until { r.tvs.value.size == 1 }
        val found = r.tvs.value[0]
        r.use(found) // not paired: asks for a code
        until { r.pairing.value != null }
        r.finishPairing("2468")
        until { r.current.value != null }
        until { r.controller.link.value == Link.Connected }
        assertEquals(listOf("Living Room"), r.pairedNames())
        r.pause()
        until { r.controller.link.value == Link.Idle }
        // Next time the tool opens, the paired Apple TV turning up connects on its own.
        val next = Remote(kv, scope) { f -> FakeFinder(f).also { finder = it } }
        next.resume()
        finder.services = listOf(livingRoom.copy(port = tv.port))
        until { next.controller.link.value == Link.Connected }
        assertEquals("Living Room", next.current.value?.name)
        next.forget(next.current.value!!)
        assertNull(next.current.value)
        assertTrue(next.pairedNames().isEmpty())
        next.pause()
    }

    @Test fun choosingAnotherAppleTvStaysOnTheListUntilTheToolComesBack() = runBlocking {
        tv = FakeAppleTv(pin = "2468")
        val r = remote()
        r.resume()
        finder.services = listOf(livingRoom.copy(port = tv.port))
        until { r.tvs.value.size == 1 }
        r.use(r.tvs.value[0]); until { r.pairing.value != null }
        r.finishPairing("2468"); until { r.controller.link.value == Link.Connected }
        r.disconnect()
        // Screens come and go while the tool stays in front: nothing reconnects.
        repeat(3) { r.resume(); finder.ask(); delay(50) }
        delay(2000)
        assertNull(r.current.value)
        // Back from the toolbox: the paired Apple TV connects by itself again.
        r.pause(); r.resume(); finder.ask()
        until { r.controller.link.value == Link.Connected }
        r.pause()
    }

    @Test fun aWrongCodeAsksForAFreshOneAndTheScreenStays() = runBlocking {
        tv = FakeAppleTv(pin = "2468")
        val r = remote()
        r.resume()
        finder.services = listOf(livingRoom.copy(port = tv.port))
        until { r.tvs.value.size == 1 }
        r.use(r.tvs.value[0])
        until { r.pairing.value != null }
        r.finishPairing("1111")
        until { r.status.value.startsWith("Wrong code") }
        assertTrue(r.status.value.contains("Type the code the TV shows now"))
        assertEquals("still on the code screen", "Living Room", r.pairing.value?.name)
        r.finishPairing("2468")
        until { r.controller.link.value == Link.Connected }
        r.pause()
    }

    @Test fun aTypedAddressIsAskedDirectlyAndRemembered() = runBlocking {
        val r = remote()
        r.resume()
        var answered: Boolean? = null
        r.addAddress("192.168.1.77") { answered = it }
        assertTrue("192.168.1.77" in finder.direct)
        // The address answers on the next ask.
        finder.services = listOf(livingRoom.copy(host = "192.168.1.77"))
        until(6000) { answered != null }
        assertEquals(true, answered)
        assertEquals(listOf("192.168.1.77"), r.addresses.value)
        assertEquals("192.168.1.77", kv.m["addresses"])
        r.removeAddress("192.168.1.77")
        assertTrue(finder.direct.isEmpty())
        assertEquals("", kv.m["addresses"])
        r.pause()
    }

    @Test fun anAddressThatNeverAnswersSaysSo() = runBlocking {
        val r = remote()
        r.resume()
        var answered: Boolean? = null
        r.addAddress("10.0.0.9") { answered = it }
        until(8000) { answered != null }
        assertEquals(false, answered)
        r.pause()
    }

    @Test fun settingsAreKept() {
        val r = remote()
        r.setHints(false)
        r.setTouchpad(com.slowpixels.tvremote.model.Touchpad.Both)
        val again = remote()
        assertEquals(false, again.hints.value)
        assertEquals(com.slowpixels.tvremote.model.Touchpad.Both, again.touchpad.value)
    }
}
