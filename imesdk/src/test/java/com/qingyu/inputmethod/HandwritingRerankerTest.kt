package com.qingyu.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingRerankerTest {

    private fun scored(vararg items: Pair<String, Float>) =
        items.map { HandwritingReranker.Scored(it.first, it.second) }

    private fun order(
        results: List<HandwritingReranker.Scored>,
        counts: Map<String, Int>,
    ) = HandwritingReranker.rerank(results, counts).map { it.text }

    @Test
    fun noHistoryKeepsModelOrder() {
        val results = scored("甲" to 0.6f, "乙" to 0.3f, "丙" to 0.1f)
        assertEquals(listOf("甲", "乙", "丙"), order(results, emptyMap()))
    }

    @Test
    fun oneExplicitCorrectionPromotesNearTie() {
        val results = scored("甲" to 0.4f, "乙" to 0.3f)
        assertEquals(listOf("乙", "甲"), order(results, mapOf("乙" to 1)))
    }

    @Test
    fun highConfidenceTopChoiceSurvivesSingleCorrection() {
        val results = scored("甲" to 0.9f, "乙" to 0.05f)
        assertEquals(listOf("甲", "乙"), order(results, mapOf("乙" to 1)))
    }

    @Test
    fun repeatedCorrectionsOvertakeStableBias() {
        val results = scored("甲" to 0.9f, "乙" to 0.05f)
        assertEquals(listOf("乙", "甲"), order(results, mapOf("乙" to 500)))
    }

    @Test
    fun historyForOtherCharactersDoesNotReorder() {
        val results = scored("甲" to 0.5f, "乙" to 0.3f)
        assertEquals(listOf("甲", "乙"), order(results, mapOf("丙" to 999)))
    }

    @Test
    fun rerankOnlyReordersTheModelTopK() {
        val results = scored("甲" to 0.5f, "乙" to 0.3f, "丙" to 0.2f)
        val reranked = HandwritingReranker.rerank(results, mapOf("丙" to 10))
        assertEquals(listOf("丙", "甲", "乙"), reranked.map { it.text })
        assertEquals(results.map { it.text }.toSet(), reranked.map { it.text }.toSet())
    }
}
