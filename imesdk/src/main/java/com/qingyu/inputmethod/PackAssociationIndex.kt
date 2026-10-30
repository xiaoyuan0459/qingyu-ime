package com.qingyu.inputmethod

import java.io.File
import java.io.RandomAccessFile

/** Read-only pack index. Every lookup opens its own handle, so replacing a pack cannot leak a reader. */
internal object PackAssociationIndex {
    /** null means this pack has no usable index; callers can fall back to the engine. */
    fun words(root: File, codePoint: Int, indexName: String = "associate.bin"): List<String>? = runCatching<List<String>?> {
        require(indexName == "associate.bin" || indexName == "associate-traditional.bin")
        val file = File(root, indexName)
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { reader ->
            val magic = ByteArray(8)
            reader.readFully(magic)
            require(magic.contentEquals("QYAS0001".toByteArray(Charsets.US_ASCII)))
            val count = reader.readInt()
            require(count in 0..100_000)
            val headerEnd = 12L + count * 16L
            require(headerEnd <= reader.length())
            var low = 0
            var high = count - 1
            while (low <= high) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val middle = (low + high).ushr(1)
                reader.seek(12L + middle * 16L)
                val key = reader.readInt()
                when {
                    key < codePoint -> low = middle + 1
                    key > codePoint -> high = middle - 1
                    else -> {
                        val offset = reader.readLong()
                        val size = reader.readInt()
                        require(offset >= headerEnd && size in 1..100_000 &&
                            offset <= reader.length() - size)
                        reader.seek(offset)
                        val bytes = ByteArray(size)
                        reader.readFully(bytes)
                        val words = bytes.toString(Charsets.UTF_8).split('\n')
                        require(words.size <= 240 && words.all {
                            it.codePointCount(0, it.length) > 1 && it.codePointAt(0) == codePoint
                        })
                        return words
                    }
                }
            }
            emptyList<String>()
        }
    }.getOrElse { error ->
        if (error is InterruptedException) throw error
        null
    }
}
