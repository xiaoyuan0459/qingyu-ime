package com.qingyu.imemodule.data.backup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** File format and recovery contracts; fixtures contain synthetic data only. */
class UserDataArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val roots = listOf("shared_prefs", "databases", "external", "rime-user")
    private val metadata = UserDataArchive.Metadata("com.qingyu.inputmethod", 1, "test", 123)
    private fun targets(base: File) = roots.associateWith { File(base, it) }
    private fun archive(base: File) = UserDataArchive(targets(base), File(base, "journal"))
    private fun put(base: File, path: String, value: String) = File(base, path).apply { parentFile!!.mkdirs(); writeText(value) }
    private fun export(base: File): ByteArray = ByteArrayOutputStream().also { archive(base).export(it, metadata) }.toByteArray()
    private fun zip(entries: Map<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
    }.toByteArray()
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            put(entry.name, zip.readBytes())
        } }
    }
    private fun expectRejected(action: () -> Unit) {
        try { action(); fail("Invalid backup must be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun versionTwoRestoresUserDictionaryAndReplacesStaleFiles() {
        val source = temp.newFolder("source")
        put(source, "rime-user/qingyu.userdb/synthetic", "learned-word")
        put(source, "databases/phrases", "synthetic-phrase")
        val backup = export(source)
        val saved = entries(backup)
        assertTrue(saved.containsKey("rime-user/qingyu.userdb/synthetic"))
        assertTrue(saved.containsKey("external/")) // Empty roots also have a defined replacement meaning.
        val destination = temp.newFolder("destination")
        put(destination, "rime-user/stale", "obsolete")
        val restored = archive(destination).restore(ByteArrayInputStream(backup)) { }
        assertEquals(2, restored.formatVersion)
        assertEquals("learned-word", File(destination, "rime-user/qingyu.userdb/synthetic").readText())
        assertEquals("synthetic-phrase", File(destination, "databases/phrases").readText())
        assertFalse(File(destination, "rime-user/stale").exists())
        assertTrue(File(destination, "external").isDirectory)
    }

    @Test fun legacyBackupKeepsCurrentUserDictionary() {
        val destination = temp.newFolder()
        put(destination, "rime-user/learned", "keep")
        val backup = zip(linkedMapOf(
            "metadata.json" to """{"packageName":"com.qingyu.inputmethod","versionCode":1,"versionName":"old","exportTime":123}""".toByteArray(),
            "shared_prefs/settings.xml" to "legacy-settings".toByteArray(),
        ))
        assertEquals(1, archive(destination).restore(ByteArrayInputStream(backup)) { }.formatVersion)
        assertEquals("keep", File(destination, "rime-user/learned").readText())
        assertEquals("legacy-settings", File(destination, "shared_prefs/settings.xml").readText())
    }

    @Test fun tamperingAndIncompleteManifestLeaveAllExistingDataUntouched() {
        val source = temp.newFolder("source")
        put(source, "rime-user/learned", "new")
        val backup = export(source)
        val variants = listOf(
            entries(backup).apply { this["rime-user/learned"] = "tampered".toByteArray() },
            entries(backup).apply { remove("rime-user/learned") },
            entries(backup).apply { remove("external/") },
        )
        val destination = temp.newFolder("destination")
        roots.forEach { put(destination, "$it/retained", "original-$it") }
        variants.forEach { variant ->
            expectRejected { archive(destination).restore(ByteArrayInputStream(zip(variant))) { } }
            roots.forEach { assertEquals("original-$it", File(destination, "$it/retained").readText()) }
        }
    }

    @Test fun unsafePathsAndUnknownRootsCannotEscapeOrModifyTargets() {
        val destination = temp.newFolder()
        put(destination, "shared_prefs/retained", "original")
        listOf("../escaped", "rime-user/../../escaped", "rime-user\\escaped", "/escaped", "unknown/data").forEach { path ->
            expectRejected { archive(destination).restore(ByteArrayInputStream(zip(mapOf(path to byteArrayOf(1))))) { } }
            assertEquals("original", File(destination, "shared_prefs/retained").readText())
            assertFalse(File(destination.parentFile, "escaped").exists())
        }
    }

    @Test fun rejectedMetadataDoesNotReplaceAnyRoot() {
        val source = temp.newFolder("source")
        put(source, "databases/data", "new")
        val destination = temp.newFolder("destination")
        put(destination, "databases/data", "old")
        expectRejected { archive(destination).restore(ByteArrayInputStream(export(source))) { throw IllegalArgumentException("wrong app") } }
        assertEquals("old", File(destination, "databases/data").readText())
    }

    @Test fun interruptedRestoreRollsBackMixedDirectoriesAndIsIdempotent() {
        val destination = temp.newFolder()
        // Durable on-disk journal: first root installed, second only saved, third was previously absent.
        put(destination, ".shared_prefs.qingyu-old/settings", "original-settings")
        put(destination, "shared_prefs/settings", "replacement")
        put(destination, ".databases.qingyu-old/data", "original-db")
        put(destination, ".databases.qingyu-new/data", "replacement")
        put(destination, "external/data", "replacement")
        put(destination, "rime-user/learned", "original-user")
        put(destination, "journal/state.json", """{"roots":["shared_prefs","databases","external"],"absent":["external"]}""")
        archive(destination).recover()
        archive(destination).recover()
        assertEquals("original-settings", File(destination, "shared_prefs/settings").readText())
        assertEquals("original-db", File(destination, "databases/data").readText())
        assertEquals("original-user", File(destination, "rime-user/learned").readText())
        assertFalse(File(destination, "external").exists())
        assertFalse(File(destination, "journal").exists())
    }

    @Test fun invalidJournalDoesNotBlockStartupOrDeleteDataCopies() {
        val source = temp.newFolder("valid-source")
        put(source, "rime-user/current", "fresh-user")
        val backup = export(source)
        listOf("{", """{"roots":["unknown"],"absent":[]}""").forEachIndexed { index, invalid ->
            val destination = temp.newFolder("invalid-$index")
            put(destination, "rime-user/current", "current-user")
            put(destination, ".rime-user.qingyu-old/saved", "previous-user")
            put(destination, ".rime-user.qingyu-new/pending", "pending-user")
            put(destination, "journal/state.json", invalid)
            archive(destination).recover(cleanup = false)
            archive(destination).recover()
            assertEquals("current-user", File(destination, "rime-user/current").readText())
            assertFalse(File(destination, "journal").exists())
            val retained = destination.walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
            assertTrue(retained.containsAll(listOf("previous-user", "pending-user", invalid)))
            archive(destination).restore(ByteArrayInputStream(backup)) { }
            assertEquals("fresh-user", File(destination, "rime-user/current").readText())
            val afterImport = destination.walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
            assertTrue(afterImport.containsAll(listOf("previous-user", "pending-user", invalid)))
        }
    }

    @Test fun committedRestoreKeepsInstalledDataEvenIfJournalIsCorrupted() {
        val destination = temp.newFolder()
        put(destination, "rime-user/current", "installed-user")
        put(destination, ".rime-user.qingyu-old/saved", "previous-user")
        put(destination, "journal/state.json", "{")
        put(destination, "journal/complete", "")
        archive(destination).recover()
        archive(destination).recover()
        assertEquals("installed-user", File(destination, "rime-user/current").readText())
        assertFalse(File(destination, ".rime-user.qingyu-old").exists())
        assertFalse(File(destination, "journal").exists())
    }
}
