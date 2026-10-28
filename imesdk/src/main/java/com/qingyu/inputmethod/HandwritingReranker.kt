package com.qingyu.inputmethod

import kotlin.math.ln

/**
 * 手写候选端上重排：在模型概率上叠加本机选字次数（ln p + λ·ln(1+次数)）。
 * 只调整模型已返回的 top-K 顺序，不扩大召回，也不更新模型权重。
 */
object HandwritingReranker {

    /**
     * 频次项权重。一次明确纠选约等于 1.4 倍概率优势，能翻转接近的候选，
     * 但压不过模型的高置信首选；反复纠选后才会覆盖模型的稳定偏差。
     */
    private const val FREQUENCY_WEIGHT = 0.5

    data class Scored(val text: String, val probability: Float)

    fun rerank(results: List<Scored>, counts: Map<String, Int>): List<Scored> {
        if (counts.isEmpty()) return results
        return results.sortedWith(
            compareByDescending<Scored> { score(it, counts) }
                .thenByDescending { it.probability }
        )
    }

    private fun score(result: Scored, counts: Map<String, Int>): Double {
        val probability = result.probability
            .takeIf { it.isFinite() && it > 0f }
            ?.toDouble() ?: 1e-12
        val selected = counts[result.text] ?: 0
        return ln(probability) + FREQUENCY_WEIGHT * ln(1.0 + selected)
    }
}
