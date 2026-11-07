package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.*

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The race behind the index error seen once in aHoldStaysDownUntilReleasedAndLastsLongEnough
 * (the original trace was overwritten by later runs, so this reproduces the mechanism rather than
 * replaying that trace). Controller.stop() copied the held buttons with Kotlin's toList(), which
 * reads the size and then, for one item, reads item [0] separately. A hold finishing on another
 * thread removes itself in between. This list does that removal at exactly that moment, every time.
 */
class SnapshotRaceTest {
    /** Says it holds one item, and the item is gone by the time it's read: a removal between the two steps. */
    private fun shrinksAfterSize() = object : CopyOnWriteArrayList<String>(listOf("hold")) {
        override val size: Int get() = super.size.also { if (it == 1) super.remove("hold") }
    }

    @Test fun kotlinToListFailsWhenTheListShrinksInBetween() {
        try {
            shrinksAfterSize().toList()
            fail("expected the index error")
        } catch (e: ArrayIndexOutOfBoundsException) {
            assertEquals("Index 0 out of bounds for length 0", e.message)
        }
    }

    @Test fun snapshotOfTakesOneConsistentCopy() {
        // One atomic copy of the backing array: no separate size read, so the same interleaving can't break it.
        assertEquals(listOf("hold"), snapshotOf(shrinksAfterSize()))
        assertEquals(emptyList<String>(), snapshotOf(CopyOnWriteArrayList<String>()))
    }
}
