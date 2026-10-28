package com.qingyu.imemodule.data.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RimePackStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifierRejectsTamperedFile() {
        val pack = createPack(temporaryFolder.newFolder("pack"), "1.0.0")
        RimePackVerifier.verify(pack)

        File(pack, "pinyin.schema.yaml").appendText("tampered")
        val error = runCatching { RimePackVerifier.verify(pack) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("大小不符"))
    }

    @Test
    fun archiveAllowsDirectoryEntriesAndRejectsZipSlip() {
        val valid = zipOf(
            "nested/" to null,
            "nested/value.txt" to "ok".toByteArray(),
        )
        val destination = temporaryFolder.newFolder("valid")
        RimePackArchive.extract(ByteArrayInputStream(valid), destination)
        assertEquals("ok", File(destination, "nested/value.txt").readText())

        val escaped = zipOf("../outside.txt" to "bad".toByteArray())
        val outside = File(destination.parentFile, "outside.txt")
        assertTrue(
            runCatching {
                RimePackArchive.extract(
                    ByteArrayInputStream(escaped),
                    temporaryFolder.newFolder("escaped"),
                )
            }.isFailure
        )
        assertFalse(outside.exists())
    }

    @Test
    fun installIsTransactionalAndRollbackRestoresPreviousPack() {
        val root = temporaryFolder.newFolder("store")
        val store = RimePackStore(root) { packRoot ->
            File(packRoot, "build").mkdirs()
            File(packRoot, "build/pinyin.table.bin").writeText("compiled")
            true
        }

        val first = store.install(ByteArrayInputStream(packZip("1.0.0")))
        assertEquals("1.0.0", first.manifest.version)
        assertEquals(first.directory.canonicalFile, store.activePack())

        val second = store.install(ByteArrayInputStream(packZip("2.0.0")))
        assertEquals("2.0.0", second.manifest.version)
        assertEquals(second.directory.canonicalFile, store.activePack())

        assertEquals(first.directory.canonicalFile, store.rollback())
        assertEquals(first.directory.canonicalFile, store.activePack())

        val activeBeforeFailure = store.activePack()
        val broken = packZip("3.0.0").clone().also { bytes ->
            bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x7f).toByte()
        }
        assertTrue(runCatching { store.install(ByteArrayInputStream(broken)) }.isFailure)
        assertEquals(activeBeforeFailure, store.activePack())
    }

    @Test
    fun supersededPackVersionsArePruned() {
        val root = temporaryFolder.newFolder("prune")
        val store = RimePackStore(root) { packRoot ->
            File(packRoot, "build").mkdirs()
            File(packRoot, "build/pinyin.table.bin").writeText("compiled")
            true
        }

        store.install(ByteArrayInputStream(packZip("1.0.0")))
        store.install(ByteArrayInputStream(packZip("2.0.0")))
        store.install(ByteArrayInputStream(packZip("3.0.0")))

        // 只保留 active（3.0.0）与可回滚的 previous（2.0.0）；一份词库 80 MB 上下
        val remaining = File(root, "packs/test-pack").listFiles().orEmpty()
            .map { it.name }
            .sorted()
        assertEquals(listOf("2.0.0", "3.0.0"), remaining)
        assertTrue(store.rollback().let { it != null && it.name == "2.0.0" })
    }

    private fun packZip(version: String): ByteArray {
        val directory = temporaryFolder.newFolder("pack-${version.replace('.', '-')}")
        createPack(directory, version)
        return zipOf(*directory.walkTopDown().filter { it.isFile }.map { file ->
            file.relativeTo(directory).invariantSeparatorsPath to file.readBytes()
        }.toList().toTypedArray())
    }

    private fun createPack(directory: File, version: String): File {
        val license = File(directory, "LICENSE").apply { writeText("GPL-3.0-only\n") }
        val schema = File(directory, "pinyin.schema.yaml").apply {
            writeText("schema:\n  schema_id: pinyin\n")
        }
        val files = listOf(license, schema).joinToString(",\n") { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            """    {"path":"$path","sha256":"${sha256(file)}","size":${file.length()}}"""
        }
        File(directory, RimePackVerifier.MANIFEST_NAME).writeText(
            """
            {
              "formatVersion": 1,
              "id": "test-pack",
              "version": "$version",
              "displayName": "Test Pack",
              "license": {"spdx": "GPL-3.0-only", "file": "LICENSE"},
              "source": {"url": "https://example.com/test-pack", "revision": "$version"},
              "schemas": ["pinyin"],
              "files": [
            $files
              ],
              "requiredArtifacts": ["build/pinyin.table.bin"]
            }
            """.trimIndent()
        )
        return directory
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (content != null) zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
