package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogTest {
    @Before fun quiet() { Logs.sink = {}; DebugLog.file = null; DebugLog.clear() }

    @Test fun summariesHideBulkAndStayShort() {
        assertEquals("{a=<5000 bytes>, b=\"x\"}", DebugLog.summary(mapOf("a" to ByteArray(5000), "b" to "x")))
        assertTrue(DebugLog.summary("y".repeat(100)).length < 50)
        assertTrue(DebugLog.summary(List(500) { it }).length <= 301)
    }

    @Test fun keepsTheLatestLines() {
        repeat(3100) { DebugLog.add("line $it") }
        val r = DebugLog.recent(5000)
        assertEquals(3000, r.size)
        assertTrue(r.last().endsWith("line 3099"))
    }

    @Test fun writesTheFileAndStartsAnewPastTheLimit() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        DebugLog.file = java.io.File(dir, "log.txt")
        DebugLog.add("hello")
        assertTrue(DebugLog.file!!.readText().contains("hello"))
        DebugLog.clear()
        assertEquals("", DebugLog.file!!.readText())
        DebugLog.file = null
    }
}
