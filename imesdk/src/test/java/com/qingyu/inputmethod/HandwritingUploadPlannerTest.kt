package com.qingyu.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingUploadPlannerTest {

    private fun line(id: String): String {
        val draft = HandwritingSampleDraft(
            100,
            100,
            listOf(listOf(HandwritingPoint(10, 10, 0), HandwritingPoint(20, 20, 10))),
        )
        return requireNotNull(HandwritingSampleFormat.encode("永", draft, id, "2026-10-20"))
    }

    @Test
    fun plansFromCursor() {
        val lines = listOf("bad", line("a"), line("b"), line("c"))
        val batch = HandwritingUploadPlanner.plan(lines, cursor = 1, maxLines = 2, maxBytes = 100_000)
        assertEquals(2, batch.lines.size)
        assertEquals(3, batch.nextCursor)
    }

    @Test
    fun skipsBadLinesAndAdvancesCursor() {
        val lines = listOf("bad", line("a"), "", line("b"))
        val batch = HandwritingUploadPlanner.plan(lines, cursor = 0, maxLines = 5, maxBytes = 100_000)
        assertEquals(2, batch.lines.size)
        assertEquals(4, batch.nextCursor)
    }

    @Test
    fun stopsBeforeByteLimitWithoutConsumingLine() {
        val first = line("a")
        val batch = HandwritingUploadPlanner.plan(
            listOf(first, line("b")),
            cursor = 0,
            maxLines = 5,
            maxBytes = first.toByteArray(Charsets.UTF_8).size + 1,
        )
        assertEquals(1, batch.lines.size)
        assertEquals(1, batch.nextCursor)
    }

    @Test
    fun skipsOversizedLine() {
        val batch = HandwritingUploadPlanner.plan(
            listOf(line("a")),
            cursor = 0,
            maxLines = 5,
            maxBytes = 10,
        )
        assertEquals(0, batch.lines.size)
        assertEquals(1, batch.nextCursor)
    }

    @Test
    fun clampsCursorPastEnd() {
        val batch = HandwritingUploadPlanner.plan(
            listOf(line("a")),
            cursor = 9,
            maxLines = 5,
            maxBytes = 100_000,
        )
        assertEquals(0, batch.lines.size)
        assertEquals(1, batch.nextCursor)
    }
}
