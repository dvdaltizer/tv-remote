package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.*

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PairingBookTest {
    private class MapKv(val m: MutableMap<String, String> = mutableMapOf()) : KeyValues {
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun keys() = m.keys.toSet()
    }

    private val keyA = ByteArray(32) { 1 }
    private val keyB = ByteArray(32) { 2 }
    private fun b64(k: ByteArray) = java.util.Base64.getEncoder().encodeToString(k)

    private val homeTv = Tv("Living Room", "192.168.1.77", 49153, "AppleTV11,1", networkId = "HOME-NET-ID")
    private val friendsTv = Tv("Living Room", "10.0.0.9", 49153, "AppleTV14,1", networkId = "FRIEND-NET-ID")

    @Before fun quiet() { Logs.sink = {} }

    // region Two Apple TVs called "Living Room"

    private fun twoLivingRooms(): Pair<PairingBook, MapKv> {
        val kv = MapKv()
        val book = PairingBook(kv)
        book.add(homeTv, Pairing("HOME-ID", keyA))
        book.add(friendsTv, Pairing("FRIEND-ID", keyB))
        return book to kv
    }

    @Test fun bothStayPairedAndTheNetworkIdTellsThemApart() {
        val (book, _) = twoLivingRooms()
        assertEquals(2, book.all().size)
        assertEquals(listOf("HOME-ID"), book.candidates(homeTv).map { it.record })
        assertEquals(listOf("FRIEND-ID"), book.candidates(friendsTv).map { it.record })
        assertEquals(listOf("Living Room"), book.all().map { it.name }.distinct())
    }

    @Test fun withoutANetworkIdBothAreTriedLastUsedFirst() {
        val (book, _) = twoLivingRooms() // the friend's was paired last
        val noId = homeTv.copy(networkId = null)
        assertEquals(listOf("FRIEND-ID", "HOME-ID"), book.candidates(noId).map { it.record })
        book.connected(book.candidates(noId)[1], noId, "HOME-ID")
        assertEquals(listOf("HOME-ID", "FRIEND-ID"), book.candidates(noId).map { it.record })
    }

    @Test fun aDifferentNetworkIdIsADifferentAppleTv() {
        val (book, _) = twoLivingRooms()
        assertTrue(book.candidates(homeTv.copy(networkId = "SOMEONE-ELSE")).isEmpty())
    }

    @Test fun aRenamedAppleTvKeepsItsPairing() {
        val (book, _) = twoLivingRooms()
        val renamed = homeTv.copy(name = "Den")
        val found = book.candidates(renamed)
        assertEquals(listOf("HOME-ID"), found.map { it.record })
        book.connected(found[0], renamed, "HOME-ID")
        assertEquals(setOf("Den", "Living Room"), book.all().map { it.name }.toSet())
    }

    @Test fun forgettingOneKeepsTheOther() {
        val (book, _) = twoLivingRooms()
        book.remove(book.candidates(friendsTv).single())
        assertEquals(listOf("HOME-ID"), book.all().map { it.record })
    }

    // endregion

    // region Connecting: the secure check decides

    private lateinit var tv: FakeAppleTv
    @After fun done() { if (::tv.isInitialized) tv.close() }
    private val me = Identity("11111111-2222-3333-4444-555555555555", ByteArray(32) { 3 })

    @Test fun theRightPairingIsFoundAmongSameNamedOnes() = runBlocking {
        tv = FakeAppleTv() // "my" Apple TV
        val other = SavedPairing("OTHER", "11:22:33:44:55:66", Crypto.publicKey(ByteArray(32) { 9 }), "Living Room", null)
        val mine = SavedPairing("MINE", tv.tvId, tv.publicKey, "Living Room", null)
        val (used, c) = connectFirst(homeTv, listOf(other, mine)) { s ->
            Companion("127.0.0.1", tv.port, me).also { it.connect(StoredPairing(s.tvId, s.publicKey)) }
        }
        assertEquals("MINE", used.record)
        assertEquals(tv.tvId, c.verifiedTvId)
        c.close()
    }

    @Test fun anAppleTvMatchingNoneIsADifferentOne() = runBlocking {
        tv = FakeAppleTv()
        val other = SavedPairing("OTHER", "11:22:33:44:55:66", Crypto.publicKey(ByteArray(32) { 9 }), "Living Room", null)
        try {
            connectFirst(homeTv, listOf(other)) { s -> Companion("127.0.0.1", tv.port, me).also { it.connect(StoredPairing(s.tvId, s.publicKey)) } }
            fail("connected to someone else's Apple TV")
        } catch (e: DifferentAppleTv) {
            assertEquals("This is a different Apple TV called Living Room. Tap it to pair.", e.message)
        }
        Unit
    }

    @Test fun noAnswerIsntMistakenForADifferentAppleTv() = runBlocking {
        val mine = SavedPairing("MINE", "AA", ByteArray(32), "Living Room", null)
        try {
            connectFirst(homeTv, listOf(mine, mine)) { throw java.net.SocketTimeoutException("no answer") }
            fail()
        } catch (e: java.net.SocketTimeoutException) { }
        Unit
    }

    // endregion
}
