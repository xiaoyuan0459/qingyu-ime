package com.qingyu.imemodule.data.rime

import kotlinx.serialization.Serializable

/**
 * 轻语词库包的公开清单格式。
 *
 * `manifest.json` 自身不进入 [files]（否则会形成自引用哈希）；除此之外，包内原始文件都必须
 * 出现在 [files] 中。运行时由 librime 生成、位于 `build/` 下且未随包分发的文件可以不列出，
 * 但最终必须满足 [requiredArtifacts]。
 */
@Serializable
data class RimePackManifest(
    val formatVersion: Int,
    val id: String,
    val version: String,
    val displayName: String,
    val license: LicenseInfo,
    val source: SourceInfo,
    val schemas: List<String>,
    val files: List<PackFile>,
    val requiredArtifacts: List<String>,
) {
    @Serializable
    data class LicenseInfo(
        val spdx: String,
        val file: String = "LICENSE",
    )

    @Serializable
    data class SourceInfo(
        val url: String,
        val revision: String,
    )

    @Serializable
    data class PackFile(
        val path: String,
        val sha256: String,
        val size: Long,
    )
}

data class VerifiedRimePack(
    val manifest: RimePackManifest,
    val root: java.io.File,
)

