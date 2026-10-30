package com.qingyu.inputmethod

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.imemodule.data.rime.RimePackManifest
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.ScriptMode
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Test

/** Actual candidates and commits in the SDK test APK's private storage. */
class NativeScriptIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun start() {
        if (!RimeSession.isRunning) {
            RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
            instrumentation.runOnMainSync { Launcher.instance.initData(context) }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (!RimeSession.isRunning && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        }
        assertTrue(Kernel.initImeSchema("pinyin"))
        instrumentation.runOnMainSync { DecodingInfo.cacheCandidates(emptyArray()) }
        setMode(ScriptMode.Simplified)
        RimeEngine.reset()
    }

    @After fun clear() {
        RimeEngine.reset()
        setMode(ScriptMode.Simplified)
    }

    @Test fun nativeFormsCommitExactlyAsDisplayedInAllModes() {
        setMode(ScriptMode.Traditional)
        RimeSession.setOption("disable_user_dict", true)
        try {
            type("taiwan")
            assertEquals("Canonical native form must precede its source-annotated variant", "臺灣", RimeEngine.showCandidates.first().text)
            assertFalse(find("台灣").isOriginal)
        } finally { RimeSession.setOption("disable_user_dict", false) }
        for (mode in ScriptMode.entries) {
            setMode(mode)
            for ((code, hans, hant) in listOf(
                Triple("women", "我们", "我們"), Triple("qiankun", "乾坤", "乾坤"),
                Triple("qianlong", "乾隆", "乾隆"), Triple("ganjing", "干净", "乾淨"),
                Triple("liaowang", "瞭望", "瞭望"), Triple("shenqi", "神祇", "神祇"),
                Triple("qianxian", "乾县", "乾縣"), Triple("toufa", "头发", "頭髮"),
                Triple("fazhan", "发展", "發展"), Triple("huanghou", "皇后", "皇后"),
                Triple("houmian", "后面", "後面"),
                Triple("taiwan", "台湾", "臺灣"),
            )) {
                val expected = if (mode == ScriptMode.Traditional) hant else hans
                type(code)
                val candidate = find(expected)
                if (mode == ScriptMode.Traditional) assertFalse("Expected native form", candidate.isOriginal)
                assertEquals(candidate.text, RimeEngine.selectCandidate(candidate.engineIndex))
            }
        }
        setMode(ScriptMode.Traditional)
        type("fa"); assertFalse(find("發").isOriginal)
        type("fa"); assertFalse(find("髮").isOriginal)
        type("huaweishengteng")
        val original = find("华为昇腾")
        assertTrue(original.isOriginal)
        assertTrue(original.displayText.endsWith("·原"))
        assertEquals(original.text, RimeEngine.selectCandidate(original.engineIndex))
    }

    @Test fun modeChangesPreserveCompositionAndEveryKeyboardLayout() {
        type("women")
        val composition = RimeSession.compositionText
        setMode(ScriptMode.Traditional)
        assertEquals(composition, RimeSession.compositionText)
        assertFalse(find("我們").isOriginal)
        setMode(ScriptMode.Original)
        assertEquals(composition, RimeSession.compositionText)
        assertNotNull(find("我们")); assertNotNull(find("我們"))
        setMode(ScriptMode.Traditional)
        type("nihaoma")
        RimeEngine.selectCandidate(find("你好").engineIndex)
        setMode(ScriptMode.Original)
        assertTrue("Confirmed prefix must survive", RimeSession.context!!.commitTextPreview.orEmpty().startsWith("你好"))
        RimeEngine.onDeleteKey()
        assertTrue(RimeSession.isComposing)
        for ((schema, code) in listOf("pinyin" to "ni", "t9_pinyin" to "MG",
            "double_pinyin_flypy" to "ni", "double_pinyin_natural" to "ni",
            "double_pinyin_mspy" to "ni", "double_pinyin_sogou" to "ni",
            "double_pinyin_abc" to "ni", "double_pinyin_ziguang" to "ni",
            "double_pinyin_ls17" to "NJ")) {
            assertTrue(Kernel.initImeSchema(schema))
            setMode(ScriptMode.Traditional); type(code)
            val candidate = find("你")
            assertFalse("Dictionary/prism must match layout", candidate.isOriginal)
            assertEquals("你", RimeEngine.selectCandidate(candidate.engineIndex))
        }
        assertTrue(Kernel.initImeSchema("pinyin"))
        for (mode in ScriptMode.entries) {
            setMode(mode)
            val words = AssociatePredictor.words("原")
            assertTrue(words.isNotEmpty())
            assertTrue(words.all { it.startsWith("原") })
            if (mode == ScriptMode.Traditional) {
                assertTrue(words.contains("原來"))
                assertFalse(words.contains("原来"))
            } else assertTrue(words.contains("原来"))
        }
    }

    @Test fun legacyPackFallsBackExplicitlyAndPreservesUnicodeOriginals() {
        setMode(ScriptMode.Traditional)
        var installed = false
        try {
            RimePackManager.install(context, ByteArrayInputStream(legacyPack())).getOrThrow()
            installed = true
            assertTrue(Kernel.initImeSchema("pinyin"))
            instrumentation.waitForIdleSync()
            assertEquals(ScriptMode.Original, AppPrefs.getInstance().input.scriptMode.getValue())
            assertEquals(2, RimeSession.scriptMode)
            RimeSession.setOption("legacy_conversion", true)
            for ((code, expected) in listOf("he" to "𠀀", "ni" to "禰\uDB40\uDD00", "shen" to "神", "brand" to "iPhone")) {
                type(code)
                val candidate = find(expected)
                assertEquals(expected, RimeEngine.selectCandidate(candidate.engineIndex))
            }
        } finally {
            if (installed) assertTrue("Pack recovery failed", RimePackManager.rollback(context))
            else RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
            assertTrue(Kernel.initImeSchema("pinyin"))
        }
    }

    private fun setMode(mode: ScriptMode) {
        instrumentation.runOnMainSync { AppPrefs.getInstance().input.scriptMode.setValue(mode) }
        assertTrue(RimeSession.setScriptMode(mode.nativeValue))
        RimeEngine.refreshScriptCandidates()
    }

    private fun type(code: String) {
        RimeEngine.reset()
        code.forEach { RimeSession.processKey(it.code, 0) }
        RimeEngine.refreshScriptCandidates()
    }

    private fun find(text: String): Candidate {
        RimeEngine.showCandidates.firstOrNull { it.text == text }?.let { return it }
        repeat(30) {
            val page = RimeEngine.getNextPageCandidates()
            page.firstOrNull { it.text == text }?.let { return it }
            if (page.isEmpty()) fail("Expected candidate not found for public fixture")
        }
        throw AssertionError("Expected candidate beyond bounded search")
    }

    private fun legacyPack(): ByteArray {
        val files = linkedMapOf(
            "LICENSE" to "Synthetic regression data, GPL-3.0-only\n",
            "default.yaml" to "schema_list:\n  - schema: pinyin\n",
            "opencc/legacy.txt" to "神\t神\n",
            "opencc/legacy.json" to "{\"name\":\"Legacy\",\"segmentation\":{\"type\":\"mmseg\",\"dict\":{\"type\":\"text\",\"file\":\"legacy.txt\"}},\"conversion_chain\":[{\"dict\":{\"type\":\"text\",\"file\":\"legacy.txt\"}}]}",
            "pinyin.dict.yaml" to "---\nname: pinyin\nversion: fixture\nsort: by_weight\nuse_preset_vocabulary: false\n...\n𠀀\the\t100\n禰\uDB40\uDD00\tni\t100\n神\tshen\t100\niPhone\tbrand\t100\n",
            "pinyin.schema.yaml" to "schema:\n  schema_id: pinyin\n  name: Original fixture\nengine:\n  processors: [speller, selector, express_editor]\n  segmentors: [abc_segmentor, fallback_segmentor]\n  translators: [script_translator]\n  filters: [simplifier@legacy_converter]\nspeller:\n  alphabet: abcdefghijklmnopqrstuvwxyz\ntranslator:\n  dictionary: pinyin\n  enable_user_dict: false\nlegacy_converter:\n  opencc_config: legacy.json\n  option_name: legacy_conversion\nmenu:\n  page_size: 30\n",
        ).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        val manifest = RimePackManifest(1, "legacy-original-fixture", "1", "Original fixture",
            RimePackManifest.LicenseInfo("GPL-3.0-only"),
            RimePackManifest.SourceInfo("https://example.invalid/fixture", "fixture-1"), listOf("pinyin"),
            files.map { (path, bytes) -> RimePackManifest.PackFile(path,
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong()) },
            listOf("build/default.yaml", "build/pinyin.schema.yaml", "build/pinyin.table.bin", "build/pinyin.prism.bin"))
        return ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                for ((path, bytes) in files + ("manifest.json" to Json.encodeToString(manifest).toByteArray())) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    companion object { @JvmStatic @AfterClass fun stop() { RimeSession.stop() } }
}
