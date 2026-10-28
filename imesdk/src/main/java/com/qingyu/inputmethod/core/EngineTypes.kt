package com.qingyu.inputmethod.core

import androidx.annotation.Keep

/**
 * 引擎数据类 —— native 层回传数据的 Kotlin 表示。
 *
 * 这些类型由 JNI 侧按**字段名**逐个填充（`GetFieldID`），因此字段名即接口契约，
 * 改名必须与 `jni.cc` 同步。类名已按语义重命名（原 `Rime*` 前缀取自旧绑定层的命名习惯）。
 */

/** 候选词。[comment] 用于拼音等注释，[text] 为候选正文。 */
@Keep
data class Candidate(
    var comment: String,
    var text: String,
) {
    /**
     * 该候选在引擎里的全局下标，由 [com.qingyu.inputmethod.RimeEngine] 装配展示列表时填写。
     * 展示位与它不再同序（模糊音过滤、累积翻页），选词必须按它回传引擎；
     * 非引擎候选（自定义短语、联想、手写）保持 -1。
     */
    var engineIndex: Int = -1
}

/** 输入方案。 */
@Keep
data class Schema(
    val id: String,
    val name: String = "",
)

/** 上屏结果。 */
@Keep
data class Commit(
    val commitText: String = "",
)

/** 当前输入串（尚未上屏的编辑内容）。 */
@Keep
data class Composition(
    val length: Int = 0,
    val cursorPos: Int = 0,
    val selStart: Int = 0,
    val selEnd: Int = 0,
    val preedit: String? = "",
)

/** 候选菜单的当前页。 */
@Keep
data class CandidateMenu(
    val pageSize: Int = 0,
    val pageNo: Int = 0,
    val isLastPage: Boolean = false,
    val highlightedCandidateIndex: Int = 0,
    val numCandidates: Int = 0,
    val candidates: Array<Candidate> = arrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CandidateMenu

        if (pageSize != other.pageSize) return false
        if (pageNo != other.pageNo) return false
        if (isLastPage != other.isLastPage) return false
        if (highlightedCandidateIndex != other.highlightedCandidateIndex) return false
        if (numCandidates != other.numCandidates) return false
        if (!candidates.contentEquals(other.candidates)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pageSize
        result = 31 * result + pageNo
        result = 31 * result + isLastPage.hashCode()
        result = 31 * result + highlightedCandidateIndex
        result = 31 * result + numCandidates
        result = 31 * result + candidates.contentHashCode()
        return result
    }
}

/** 引擎状态。 */
@Keep
data class Status(
    val schemaId: String = "",
    val schemaName: String = "",
    val isDisable: Boolean = true,
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = true,
    val isFullShape: Boolean = false,
    val isSimplified: Boolean = false,
    val isTraditional: Boolean = false,
    val isAsciiPunch: Boolean = true,
)

/** 一次完整的输入上下文：输入串 + 候选菜单 + 状态标签。 */
@Keep
data class InputContext(
    val composition: Composition? = null,
    val menu: CandidateMenu? = null,
    val commitTextPreview: String? = "",
    val selectLabels: Array<String> = arrayOf(),
) {
    /** 当前页候选；无候选时为空数组。 */
    val candidates: Array<Candidate>
        get() {
            val numCandidates = menu?.numCandidates ?: 0
            return if (numCandidates != 0) menu!!.candidates else arrayOf()
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InputContext

        if (composition != other.composition) return false
        if (menu != other.menu) return false
        if (commitTextPreview != other.commitTextPreview) return false
        if (!selectLabels.contentEquals(other.selectLabels)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = composition?.hashCode() ?: 0
        result = 31 * result + (menu?.hashCode() ?: 0)
        result = 31 * result + commitTextPreview.hashCode()
        result = 31 * result + selectLabels.contentHashCode()
        return result
    }
}
