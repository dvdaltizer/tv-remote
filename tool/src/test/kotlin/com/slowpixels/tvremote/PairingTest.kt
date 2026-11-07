package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Pairing with the code the Apple TV shows, end to end against the fake's SRP. */
class PairingTest {
    private val me = Identity("11111111-2222-3333-4444-555555555555", ByteArray(32) { 3 })
    private lateinit var tv: FakeAppleTv

    @Before fun quiet() { Logs.sink = {} }
    @After fun done() { if (::tv.isInitialized) tv.close() }

    @Test fun theRightCodePairsAndTheNewPairingConnects() = runBlocking {
        tv = FakeAppleTv(pin = "4821")
        val c = Companion("127.0.0.1", tv.port, me)
        c.startPairing()
        val pairing = c.finishPairing("4821", "Light Phone")
        assertEquals(tv.tvId, pairing.id)
        assertTrue(pairing.publicKey.contentEquals(tv.publicKey))
        assertEquals(listOf(me.pairingId to me.publicKey.toList()), tv.paired.map { it.first to it.second.toList() })
        assertEquals(listOf("Light Phone"), tv.pairedNames)
        // What pairing left is enough to connect from now on.
        val again = Companion("127.0.0.1", tv.port, me)
        again.connect(StoredPairing(pairing.id, pairing.publicKey))
        assertEquals(tv.tvId, again.verifiedTvId)
        again.close()
    }

    @Test fun aWrongCodeIsSaidPlainlyAndNothingIsPaired() = runBlocking {
        tv = FakeAppleTv(pin = "4821")
        val c = Companion("127.0.0.1", tv.port, me)
        c.startPairing()
        try { c.finishPairing("0000", "Light Phone"); fail("paired with the wrong code") } catch (e: java.io.IOException) {
            assertEquals("Wrong code. Try again.", e.message)
        }
        assertTrue(tv.paired.isEmpty())
    }
}
