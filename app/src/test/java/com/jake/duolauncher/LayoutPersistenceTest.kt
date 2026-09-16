package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the coalescing and flush rules behind NFR-P5 without SharedPreferences or a real clock:
 * the scheduler stands in for the IO dispatcher, and the writer is a fake that records payloads.
 */
class LayoutPersistenceTest {
    /** Records the armed task so a test can fire or drop the debounce deterministically. */
    private class FakeScheduler : PersistenceScheduler {
        var armed: (() -> Unit)? = null
        var arming = 0
        var cancellations = 0
        var lastDelay = -1L

        override fun schedule(delayMillis: Long, task: () -> Unit) {
            arming++
            lastDelay = delayMillis
            armed = task
        }

        override fun cancel() {
            cancellations++
            armed = null
        }

        /** Fires the armed task, as the real debounce would once it elapses. */
        fun elapse() {
            val task = armed ?: return
            armed = null
            task()
        }
    }

    private class Fixture {
        val scheduler = FakeScheduler()
        var state = "layout-0"
        var serializations = 0
        var clock = 0L
        val writes = mutableListOf<String>()
        val persistence = DebouncedPersistence(
            scheduler = scheduler,
            serialize = { serializations++; state },
            write = { writes += it },
            now = { clock },
        )

        fun edit(next: String) {
            state = next
            persistence.request()
        }

        /** Edits after advancing the fake clock, as a continuous stream of edits would. */
        fun editAfter(millis: Long, next: String) {
            clock += millis
            edit(next)
        }
    }

    @Test fun editsUseTheOneHundredFiftyMillisecondDebounce() {
        val fixture = Fixture()
        fixture.edit("layout-1")
        assertEquals(PERSIST_DEBOUNCE_MILLIS, fixture.scheduler.lastDelay)
        assertEquals(150L, PERSIST_DEBOUNCE_MILLIS)
    }

    @Test fun anEditNeitherSerializesNorWritesOnTheCallingThread() {
        val fixture = Fixture()
        fixture.edit("layout-1")
        assertEquals(0, fixture.serializations)
        assertEquals(emptyList<String>(), fixture.writes)
        assertTrue(fixture.persistence.hasPendingWrite)
    }

    @Test fun aBurstOfEditsProducesOneSerializationAndOneWrite() {
        val fixture = Fixture()
        repeat(10) { fixture.edit("layout-${it + 1}") }
        // Each edit re-arms the same timer rather than queueing another write.
        assertEquals(10, fixture.scheduler.arming)
        fixture.scheduler.elapse()
        assertEquals(1, fixture.serializations)
        assertEquals(listOf("layout-10"), fixture.writes)
        assertFalse(fixture.persistence.hasPendingWrite)
    }

    @Test fun aSettledTimerDoesNotWriteAgainWithoutANewEdit() {
        val fixture = Fixture()
        fixture.edit("layout-1")
        fixture.scheduler.elapse()
        fixture.scheduler.elapse()
        assertEquals(listOf("layout-1"), fixture.writes)
        assertEquals(1, fixture.serializations)
    }

    @Test fun flushWritesAPendingEditImmediatelyAndDisarmsTheTimer() {
        val fixture = Fixture()
        repeat(3) { fixture.edit("layout-${it + 1}") }
        fixture.persistence.flush()
        assertEquals(listOf("layout-3"), fixture.writes)
        assertEquals(1, fixture.serializations)
        assertEquals(1, fixture.scheduler.cancellations)
        assertFalse(fixture.persistence.hasPendingWrite)
    }

    @Test fun aTimerRacingAFlushDoesNotWriteTwice() {
        val fixture = Fixture()
        fixture.edit("layout-1")
        val racing = fixture.scheduler.armed
        fixture.persistence.flush()
        racing?.invoke()
        assertEquals(listOf("layout-1"), fixture.writes)
        assertEquals(1, fixture.serializations)
    }

    @Test fun flushingWithNothingPendingWritesNothing() {
        val fixture = Fixture()
        fixture.persistence.flush()
        fixture.persistence.flush()
        assertEquals(emptyList<String>(), fixture.writes)
        assertEquals(0, fixture.serializations)
    }

    @Test fun anEditAfterAFlushIsStillPersisted() {
        val fixture = Fixture()
        fixture.edit("layout-1")
        fixture.persistence.flush()
        fixture.edit("layout-2")
        assertTrue(fixture.persistence.hasPendingWrite)
        fixture.scheduler.elapse()
        assertEquals(listOf("layout-1", "layout-2"), fixture.writes)
    }

    @Test fun continuousEditingStillReachesDiskAtTheMaxWait() {
        val fixture = Fixture()
        fixture.edit("layout-1")
        // Edits keep arriving inside the debounce window, so a pure trailing debounce would never fire.
        var label = 1
        while (fixture.clock < PERSIST_MAX_WAIT_MILLIS) {
            fixture.editAfter(100L, "layout-${++label}")
            assertTrue(fixture.scheduler.lastDelay <= PERSIST_DEBOUNCE_MILLIS)
        }
        // At the deadline the wait has collapsed to zero rather than sliding another 150 ms.
        assertEquals(0L, fixture.scheduler.lastDelay)
        fixture.scheduler.elapse()
        assertEquals(listOf("layout-$label"), fixture.writes)
    }

    @Test fun theMaxWaitDeadlineRestartsWithTheNextEdit() {
        val fixture = Fixture()
        fixture.edit("layout-1")
        fixture.clock += PERSIST_MAX_WAIT_MILLIS
        fixture.scheduler.elapse()
        fixture.edit("layout-2")
        // A fresh edit gets the full debounce again; the old deadline does not force an instant write.
        assertEquals(PERSIST_DEBOUNCE_MILLIS, fixture.scheduler.lastDelay)
    }

    @Test fun flushAlwaysWritesTheNewestStateNotTheOneTheEditWasMadeWith() {
        val fixture = Fixture()
        fixture.edit("layout-1")
        fixture.state = "layout-2"
        fixture.persistence.flush()
        assertEquals(listOf("layout-2"), fixture.writes)
    }
}
