package com.qingyu.imemodule.data.rime

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/** 校验 pack 身份、许可声明、文件清单和内容哈希。 */
object RimePackVerifier {
    const val FORMAT_VERSION = 1
    const val MANIFEST_NAME = "manifest.json"

    private val idPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val versionPattern = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")
    private val schemaPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /**
     * @param allowGeneratedBuildFiles 已安装 pack 的 `build/` 里会存在本机编译产物；它们不必
     * 出现在原始清单中。首次解包时必须传 false，防止归档偷偷夹带未声明文件。
     */
    fun verify(root: File, allowGeneratedBuildFiles: Boolean = false): VerifiedRimePack {
        require(root.isDirectory) { "词库包目录不存在" }
        val manifest = readManifest(root)

        val declared = LinkedHashMap<String, RimePackManifest.PackFile>()
        for (entry in manifest.files) {
            val path = normalizeRelativePath(entry.path)
            require(path != MANIFEST_NAME) { "manifest.json 不能声明自身哈希" }
            require(declared.put(path, entry.copy(path = path)) == null) { "重复文件: $path" }
        }

        val actual = root.walkTopDown()
            .filter { it.isFile }
            .map { normalizeRelativePath(it.relativeTo(root).invariantSeparatorsPath) }
            .filter { it != MANIFEST_NAME }
            .toSet()

        val unexpected = actual.filterNot { path ->
            path in declared || (allowGeneratedBuildFiles && path.startsWith("build/"))
        }
        require(unexpected.isEmpty()) { "词库包含未声明文件: ${unexpected.sorted().joinToString()}" }

        val missing = declared.keys - actual
        require(missing.isEmpty()) { "词库缺少清单文件: ${missing.sorted().joinToString()}" }

        for ((path, entry) in declared) {
            val file = resolveInside(root, path)
            require(file.length() == entry.size) { "文件大小不符: $path" }
            require(sha256(file) == entry.sha256) { "文件哈希不符: $path" }
        }

        val licensePath = normalizeRelativePath(manifest.license.file)
        require(resolveInside(root, licensePath).isFile) { "缺少许可证文件: ${manifest.license.file}" }
        for (schema in manifest.schemas) {
            val source = File(root, "$schema.schema.yaml")
            val compiled = File(root, "build/$schema.schema.yaml")
            require(source.isFile || compiled.isFile) { "缺少方案配置: $schema" }
        }
        return VerifiedRimePack(manifest, root)
    }

    /** 读取已验证安装目录的元数据，不重复计算大型词典哈希。 */
    fun readManifest(root: File): RimePackManifest {
        require(root.isDirectory) { "词库包目录不存在" }
        val manifestFile = File(root, MANIFEST_NAME)
        require(manifestFile.isFile) { "词库包缺少 $MANIFEST_NAME" }
        require(manifestFile.length() <= 1L * 1024 * 1024) { "manifest.json 超过 1 MiB" }
        return parseManifest(manifestFile.readText(Charsets.UTF_8))
    }

    /** 解析并校验清单文本；归档侧只读清单时走这里，不落全量文件。 */
    fun parseManifest(text: String): RimePackManifest =
        json.decodeFromString<RimePackManifest>(text).also { validateManifest(it) }

    fun verifyRequiredArtifacts(pack: VerifiedRimePack) {
        for (path in pack.manifest.requiredArtifacts) {
            val artifact = resolveInside(pack.root, normalizeRelativePath(path))
            require(artifact.isFile && artifact.length() > 0) { "词库编译产物缺失: $path" }
        }
    }

    fun validateManifest(manifest: RimePackManifest) {
        require(manifest.formatVersion == FORMAT_VERSION) {
            "不支持的词库包格式: ${manifest.formatVersion}"
        }
        require(idPattern.matches(manifest.id)) { "非法 pack id: ${manifest.id}" }
        require(versionPattern.matches(manifest.version)) { "非法 pack version: ${manifest.version}" }
        require(manifest.displayName.isNotBlank() && manifest.displayName.length <= 80) { "非法显示名称" }
        require(manifest.license.spdx.isNotBlank() && manifest.license.spdx.length <= 80) { "缺少 SPDX" }
        normalizeRelativePath(manifest.license.file)
        require(manifest.source.url.startsWith("https://")) { "source.url 必须使用 https" }
        require(manifest.source.revision.isNotBlank() && manifest.source.revision.length <= 128) {
            "缺少 source revision"
        }
        require(manifest.schemas.isNotEmpty() && manifest.schemas.distinct().size == manifest.schemas.size) {
            "schema 列表为空或重复"
        }
        manifest.schemas.forEach { require(schemaPattern.matches(it)) { "非法 schema id: $it" } }
        require(manifest.files.isNotEmpty()) { "文件清单为空" }
        manifest.files.forEach {
            normalizeRelativePath(it.path)
            require(sha256Pattern.matches(it.sha256.lowercase(Locale.ROOT))) { "非法 SHA-256: ${it.path}" }
            require(it.sha256 == it.sha256.lowercase(Locale.ROOT)) { "SHA-256 必须使用小写: ${it.path}" }
            require(it.size >= 0) { "非法文件大小: ${it.path}" }
        }
        require(manifest.requiredArtifacts.isNotEmpty()) { "requiredArtifacts 不能为空" }
        manifest.requiredArtifacts.forEach {
            val path = normalizeRelativePath(it)
            require(path.startsWith("build/")) { "编译产物必须位于 build/: $path" }
        }
    }

    fun normalizeRelativePath(raw: String): String {
        require(raw.isNotBlank() && '\u0000' !in raw) { "空路径或 NUL 路径" }
        val normalized = raw.replace('\\', '/').trimStart('/')
        require(normalized == raw.replace('\\', '/')) { "不允许绝对路径: $raw" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "不允许盘符路径: $raw" }
        val parts = normalized.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "非法相对路径: $raw" }
        return parts.joinToString("/")
    }

    fun resolveInside(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        val prefix = canonicalRoot.path + File.separator
        require(target.path.startsWith(prefix)) { "路径越界: $relativePath" }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
