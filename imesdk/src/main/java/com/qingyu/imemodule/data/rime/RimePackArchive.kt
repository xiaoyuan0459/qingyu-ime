package com.qingyu.imemodule.data.rime

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** 有资源上限、拒绝 Zip Slip 与重复路径的 pack 解包器。 */
object RimePackArchive {
    const val MAX_ENTRIES = 4096
    const val MAX_FILE_BYTES = 128L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 384L * 1024 * 1024

    /**
     * 只读出归档里的 `manifest.json`，不落任何文件 —— 启动时判断内置 pack 是否需要重装用，
     * 全量解压一份 36 MB 词库只为比对身份太浪费。
     */
    fun readManifest(input: InputStream): RimePackManifest {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name != RimePackVerifier.MANIFEST_NAME) {
                    zip.closeEntry()
                    continue
                }
                val bytes = zip.readBytes()
                zip.closeEntry()
                require(bytes.size <= 1024 * 1024) { "manifest.json 超过 1 MiB" }
                return RimePackVerifier.parseManifest(bytes.decodeToString())
            }
        }
        error("词库包缺少 ${RimePackVerifier.MANIFEST_NAME}")
    }

    fun extract(input: InputStream, destination: File) {
        require(destination.mkdirs() || destination.isDirectory) { "无法创建 staging 目录" }
        val seen = HashSet<String>()
        var entries = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "词库包文件数超过 $MAX_ENTRIES" }
                // 常规 zip 的目录项以 `/` 结尾；清单路径不允许空片段，因此先只对目录项
                // 去掉结尾分隔符。普通文件仍严格拒绝尾随 `/` 和其他畸形路径。
                val rawPath = if (entry.isDirectory) entry.name.trimEnd('/', '\\') else entry.name
                val path = RimePackVerifier.normalizeRelativePath(rawPath)
                require(seen.add(path.lowercase(Locale.ROOT))) { "词库包存在重复路径: $path" }
                val target = RimePackVerifier.resolveInside(destination, path)
                if (entry.isDirectory) {
                    require(target.mkdirs() || target.isDirectory) { "无法创建目录: $path" }
                    zip.closeEntry()
                    continue
                }
                val parent = requireNotNull(target.parentFile)
                require(parent.mkdirs() || parent.isDirectory) { "无法创建父目录: $path" }
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var fileBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        fileBytes += read
                        total += read
                        require(fileBytes <= MAX_FILE_BYTES) { "单个文件过大: $path" }
                        require(total <= MAX_TOTAL_BYTES) { "词库包解压后超过 384 MiB" }
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        require(entries > 0) { "空词库包" }
    }
}
