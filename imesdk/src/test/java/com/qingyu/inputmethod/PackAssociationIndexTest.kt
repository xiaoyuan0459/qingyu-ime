package com.qingyu.inputmethod

import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackAssociationIndexTest {
    @Test fun readsThePackFormatIncludingSupplementaryCharacters() = inDirectory { root ->
        val payloads = listOf("原来\n原神", "𠀀字").map { it.toByteArray(Charsets.UTF_8) }
        DataOutputStream(File(root, "associate.bin").outputStream()).use { out ->
            out.writeBytes("QYAS0001")
            out.writeInt(2)
            var offset = 44L
            listOf('原'.code, 0x20000).zip(payloads).forEach { (key, bytes) ->
                out.writeInt(key)
                out.writeLong(offset)
                out.writeInt(bytes.size)
                offset += bytes.size
            }
            payloads.forEach(out::write)
        }
        assertEquals(listOf("原来", "原神"), PackAssociationIndex.words(root, '原'.code))
        assertEquals(listOf("𠀀字"), PackAssociationIndex.words(root, 0x20000))
        assertEquals(emptyList<String>(), PackAssociationIndex.words(root, '无'.code))
    }

    @Test fun missingOrDamagedIndexFallsBackWithoutExposingPartialWords() = inDirectory { root ->
        assertNull(PackAssociationIndex.words(root, '原'.code))
        val file = File(root, "associate.bin")
        for (bytes in listOf(byteArrayOf(), "QYAS0001".toByteArray(),
            "QYAS0001\u007f\u007f\u007f\u007f".toByteArray())) {
            file.writeBytes(bytes)
            assertNull(PackAssociationIndex.words(root, '原'.code))
        }
        DataOutputStream(file.outputStream()).use { out ->
            out.writeBytes("QYAS0001")
            out.writeInt(1)
            out.writeInt('原'.code)
            out.writeLong(Long.MAX_VALUE)
            out.writeInt(12)
        }
        assertNull(PackAssociationIndex.words(root, '原'.code))
    }

    private fun inDirectory(block: (File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("pack-associations").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
