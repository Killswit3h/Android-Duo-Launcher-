package com.jake.duolauncher.postures

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A fake window-layout source; the project has no mocking library. */
private class FakeFoldSignalSource(
    private val emissions: List<List<FoldSignal>>,
    private val failure: Throwable? = null,
) : FoldSignalSource {
    override fun signals(): Flow<List<FoldSignal>> = flow {
        emissions.forEach { emit(it) }
        failure?.let { throw it }
    }
}

class PostureMappingTest {
    private val hinge = PostureRect(500, 0, 500, 800)
    private val flat = FoldSignal(hinge, FoldState.FLAT, FoldOrientation.VERTICAL)
    private val halfOpened = FoldSignal(hinge, FoldState.HALF_OPENED, FoldOrientation.VERTICAL)

    private fun postures(
        emissions: List<List<FoldSignal>>,
        failure: Throwable? = null,
    ): List<DuoPosture> = runBlocking {
        postureFlow(FakeFoldSignalSource(emissions, failure)).toList()
    }

    @Test fun `no fold features means unknown, not flat`() {
        assertEquals(DuoPosture.Unknown, postureOf(emptyList()))
    }

    @Test fun `a flat feature maps to flat`() {
        assertEquals(DuoPosture.Flat, postureOf(listOf(flat)))
    }

    @Test fun `a half-opened feature carries the hinge bounds and orientation`() {
        assertEquals(
            DuoPosture.HalfOpened(hinge, FoldOrientation.VERTICAL),
            postureOf(listOf(halfOpened)),
        )
        val horizontal = FoldSignal(
            PostureRect(0, 400, 1000, 400), FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL,
        )
        assertEquals(
            DuoPosture.HalfOpened(PostureRect(0, 400, 1000, 400), FoldOrientation.HORIZONTAL),
            postureOf(listOf(horizontal)),
        )
    }

    @Test fun `a half-opened feature wins over a flat one and the first one wins`() {
        assertEquals(
            DuoPosture.HalfOpened(hinge, FoldOrientation.VERTICAL),
            postureOf(listOf(flat, halfOpened)),
        )
        val second = FoldSignal(
            PostureRect(700, 0, 700, 800), FoldState.HALF_OPENED, FoldOrientation.VERTICAL,
        )
        assertEquals(
            DuoPosture.HalfOpened(hinge, FoldOrientation.VERTICAL),
            postureOf(listOf(halfOpened, second)),
        )
    }

    @Test fun `a zero-width hinge line is a real hinge, not a missing one`() {
        val posture = postureOf(listOf(halfOpened))
        assertEquals(0, (posture as DuoPosture.HalfOpened).hingeBoundsPx.width)
        val regions = HingeAvoidance.regionsFor(PostureRect(0, 0, 1000, 800), posture, 16)
        assertEquals(PostureRect(484, 0, 516, 800), regions.exclusion)
    }

    @Test fun `folding and unfolding walks the posture stream without repeats`() {
        assertEquals(
            listOf(
                DuoPosture.Unknown,
                DuoPosture.Flat,
                DuoPosture.HalfOpened(hinge, FoldOrientation.VERTICAL),
                DuoPosture.Flat,
            ),
            postures(
                listOf(
                    emptyList(),
                    listOf(flat),
                    listOf(flat),
                    listOf(halfOpened),
                    listOf(halfOpened),
                    listOf(flat),
                ),
            ),
        )
    }

    @Test fun `a failing posture API degrades to unknown instead of propagating`() {
        assertEquals(
            listOf(DuoPosture.HalfOpened(hinge, FoldOrientation.VERTICAL), DuoPosture.Unknown),
            postures(listOf(listOf(halfOpened)), failure = IllegalStateException("no extensions")),
        )
    }

    @Test fun `a posture API that is unavailable from the start reports unknown`() {
        assertEquals(
            listOf(DuoPosture.Unknown),
            postures(emptyList(), failure = UnsupportedOperationException("no window extensions")),
        )
        assertEquals(listOf(DuoPosture.Unknown), postures(listOf(emptyList())))
    }
}
