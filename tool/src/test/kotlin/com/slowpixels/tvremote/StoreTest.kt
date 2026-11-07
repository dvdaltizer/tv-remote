package com.slowpixels.tvremote

import com.slowpixels.tvremote.model.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class StoreTest {
    @Test fun keepsWhatItsToldAcrossReopening() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = File(dir, "remote.properties")
        val a = Store(f)
        assertNull(a.get("pairingId"))
        a.put("pairingId", "ABC")
        a.put("tv.X.key", "a2V5")
        a.put("addresses", "192.168.1.5,10.0.0.9")
        a.remove("addresses")
        val b = Store(f)
        assertEquals("ABC", b.get("pairingId"))
        assertEquals("a2V5", b.get("tv.X.key"))
        assertNull(b.get("addresses"))
        assertEquals(setOf("pairingId", "tv.X.key"), b.keys())
        // No part file left behind.
        assertEquals(listOf("remote.properties"), dir.list()!!.toList())
    }

    @Test fun aMissingOrBrokenFileIsAnEmptyStore() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        assertEquals(emptySet<String>(), Store(File(dir, "none")).keys())
        val bad = File(dir, "bad").apply { writeBytes(byteArrayOf(0, 1, 2, 0xFF.toByte())) }
        Store(bad).keys() // whatever it holds, it doesn't throw
    }
}
