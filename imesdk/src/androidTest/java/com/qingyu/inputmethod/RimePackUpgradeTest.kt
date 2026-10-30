package com.qingyu.inputmethod

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.data.rime.RimePackArchive
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.imemodule.data.rime.RimePackStore
import com.qingyu.imemodule.data.rime.RimePackVerifier
import com.qingyu.inputmethod.core.RimeSession
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RimePackUpgradeTest {
    @Test fun upgradesLegacyBuiltinRetainsUserDataAndKeepsRollback() = withPack("rime-ice-qingyu") { context, store ->
        val user = File(RimePackManager.userDataDir(context), "upgrade-sentinel").apply { writeText("synthetic") }
        RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
        assertEquals("rime-frost-qingyu", RimePackManager.activeManifest(context)!!.id)
        assertEquals("synthetic", user.readText())
        assertEquals("rime-ice-qingyu", RimePackVerifier.readManifest(store.rollback()!!).id)
    }

    @Test fun preservesAnImportedPack() = withPack("custom-dictionary") { context, _ ->
        RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
        assertEquals("custom-dictionary", RimePackManager.activeManifest(context)!!.id)
    }

    private fun withPack(id: String, block: (ContextWrapper, RimePackStore) -> Unit) {
        RimeSession.stop()
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = File(base.cacheDir, "pack-upgrade-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(temporary, "files").apply { mkdirs() }
        }
        try {
            val builtin = base.assets.open("rime-base.zip").use(RimePackArchive::readManifest)
            assertEquals("rime-frost-qingyu", builtin.id)
            val old = builtin.copy(id = id, source = builtin.source.copy(
                url = "https://github.com/iDvel/rime-ice/tree/9e66b0729083b37d217312294f6d516c8d7234be",
                revision = "9e66b0729083b37d217312294f6d516c8d7234be",
            ))
            val zip = File(temporary, "previous.zip")
            ZipOutputStream(zip.outputStream()).use { output ->
                ZipInputStream(base.assets.open("rime-base.zip")).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        output.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "manifest.json") output.write(Json.encodeToString(old).toByteArray())
                        else input.copyTo(output)
                        output.closeEntry()
                    }
                }
            }
            val store = RimePackStore(File(context.filesDir, "rime-data")) { false }
            zip.inputStream().use(store::install)
            assertTrue(store.activePack() != null)
            block(context, store)
        } finally {
            RimeSession.stop()
            temporary.deleteRecursively()
        }
    }
}
