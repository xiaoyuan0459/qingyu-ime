package com.qingyu.inputmethod

/**
 * 从本地 JSONL 中切出下一次上传的批次。坏行和超长行会跳过并推进游标，避免队列卡死。
 */
object HandwritingUploadPlanner {

    data class Batch(val lines: List<String>, val nextCursor: Int, val bytes: Int)

    fun plan(
        lines: List<String>,
        cursor: Int,
        maxLines: Int,
        maxBytes: Int,
    ): Batch {
        if (maxLines <= 0 || maxBytes <= 0) {
            return Batch(emptyList(), cursor.coerceIn(0, lines.size), 0)
        }
        val start = cursor.coerceIn(0, lines.size)
        val selected = ArrayList<String>()
        var bytes = 0
        var index = start
        while (index < lines.size && selected.size < maxLines) {
            val line = lines[index].trim()
            if (!isValidLine(line)) {
                index++
                continue
            }
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (lineBytes > maxBytes) {
                index++
                continue
            }
            if (selected.isNotEmpty() && bytes + lineBytes > maxBytes) break
            selected.add(line)
            bytes += lineBytes
            index++
        }
        return Batch(selected, index, bytes)
    }

    private fun isValidLine(line: String): Boolean =
        line.startsWith(HandwritingSampleFormat.LINE_PREFIX) && line.endsWith("}")
}
