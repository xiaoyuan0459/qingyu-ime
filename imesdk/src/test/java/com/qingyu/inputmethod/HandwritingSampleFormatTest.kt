package com.qingyu.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingSampleFormatTest {

    private fun point(x: Int, y: Int, time: Long) = HandwritingPoint(x, y, time)

    private fun draft(vararg strokes: List<HandwritingPoint>) =
        HandwritingSampleDraft(1000, 500, strokes.toList())

    private fun validDraft() = draft(
        listOf(point(100, 50, 1000), point(500, 250, 1100))
    )

    @Test
    fun encodesNormalizedTrajectory() {
        val json = HandwritingSampleFormat.encode(
            label = "永",
            draft = draft(
                listOf(point(100, 50, 1000), point(500, 250, 1100)),
                listOf(
                    point(500, 250, 1200),
                    point(900, 450, 1300),
                    point(950, 480, 1300),
                ),
            ),
            id = "6f1c2a9e-7b1d-4c55-9a2e-1d4b6c8e9f01",
            createdAt = "2026-10-20",
        )

        assertEquals(
            """{"format":"qingyu-hw-trajectory","version":1,"id":"6f1c2a9e-7b1d-4c55-9a2e-1d4b6c8e9f01","label":"永","created_at":"2026-10-20","canvas":{"aspect":2.0000,"unit":"normalized"},"strokes":[[[0.1000,0.1000,0],[0.5000,0.5000,100]],[[0.5000,0.5000,200],[0.9000,0.9000,300],[0.9500,0.9600,300]]]}""",
            json,
        )
    }

    @Test
    fun rejectsLabelsThatAreNotSingleHan() {
        assertNull(HandwritingSampleFormat.encode("a", validDraft(), "id", "2026-10-20"))
        assertNull(HandwritingSampleFormat.encode("永a", validDraft(), "id", "2026-10-20"))
        assertNull(HandwritingSampleFormat.encode("", validDraft(), "id", "2026-10-20"))
    }

    @Test
    fun acceptsSupplementaryHanLabel() {
        val json = HandwritingSampleFormat.encode("𠀀", validDraft(), "id", "2026-10-20")
        assertTrue(json!!.contains("\"label\":\"𠀀\""))
    }

    @Test
    fun rejectsStrokeWithSinglePoint() {
        val broken = draft(listOf(point(100, 50, 1000)))
        assertNull(HandwritingSampleFormat.encode("永", broken, "id", "2026-10-20"))
    }

    @Test
    fun clampsCoordinatesAndKeepsTimeMonotonic() {
        val json = HandwritingSampleFormat.encode(
            "永",
            HandwritingSampleDraft(
                100,
                100,
                listOf(listOf(point(-10, 120, 500), point(100, 100, 400))),
            ),
            "id",
            "2026-10-20",
        )
        assertTrue(json!!.contains("[[0.0000,1.0000,0],[1.0000,1.0000,0]]"))
    }

    @Test
    fun rejectsMoreThanTotalPointLimit() {
        val longStroke = (0 until 1000).map { point(0, 0, it.toLong()) }
        val tooMany = HandwritingSampleDraft(1000, 500, List(9) { longStroke })
        assertNull(HandwritingSampleFormat.encode("永", tooMany, "id", "2026-10-20"))
    }

    @Test
    fun rejectsMoreThanStrokeLimit() {
        val tooManyStrokes = HandwritingSampleDraft(
            1000,
            500,
            List(HandwritingSampleFormat.MAX_STROKES + 1) {
                listOf(point(0, 0, 0), point(1, 1, 1))
            },
        )
        assertNull(HandwritingSampleFormat.encode("永", tooManyStrokes, "id", "2026-10-20"))
    }

    @Test
    fun rejectsZeroSizedCanvas() {
        val invalidCanvas = HandwritingSampleDraft(
            0,
            500,
            listOf(listOf(point(0, 0, 0), point(1, 1, 1))),
        )
        assertNull(HandwritingSampleFormat.encode("永", invalidCanvas, "id", "2026-10-20"))
    }
}
