package com.qingyu.imemodule.voice

import com.qingyu.imemodule.prefs.behavior.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只测纯数据部分：模型 URL/体积映射与语言编解码，不碰 Android 的文件与网络。 */
class OfflineVoiceModelSpecTest {

    @Test
    fun chineseAndEnglishModelsPointAtVoskSmallPacks() {
        val zh = OfflineVoiceModel.spec(VoiceLanguage.ZH)
        val en = OfflineVoiceModel.spec(VoiceLanguage.EN)
        assertTrue(zh.url, zh.url.endsWith("vosk-model-small-cn-0.22.zip"))
        assertTrue(en.url, en.url.endsWith("vosk-model-small-en-us-0.15.zip"))
        assertTrue("中文包应小于 60 MB，实际 ${zh.downloadBytes}", zh.downloadBytes in 30L * 1024 * 1024..60L * 1024 * 1024)
    }

    @Test
    fun installedSizeIsLargerThanDownloadedZip() {
        VoiceLanguage.entries.forEach { language ->
            val spec = OfflineVoiceModel.spec(language)
            assertTrue("$language 解压后应比压缩包大", spec.installedBytes > spec.downloadBytes)
        }
    }

    @Test
    fun languageRoundTripsThroughPreferenceCodec() {
        VoiceLanguage.entries.forEach { language ->
            assertEquals(language, VoiceLanguage.decode(VoiceLanguage.encode(language)))
        }
        assertEquals(null, VoiceLanguage.decode("fr"))
    }

    @Test
    fun languageTagsAreImeUsable() {
        assertEquals("zh-CN", VoiceLanguage.ZH.tag)
        assertEquals("en-US", VoiceLanguage.EN.tag)
    }
}
