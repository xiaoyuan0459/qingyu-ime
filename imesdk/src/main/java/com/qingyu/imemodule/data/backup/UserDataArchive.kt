package com.qingyu.imemodule.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Versioned user backup. Targets are supplied by the app, never by archive paths. */
class UserDataArchive(private val targets: Map<String, File>, private val journal: File) {
    enum class RecoveryResult { Ready, Quarantined }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long,
        val formatVersion: Int = 1,
        val roots: List<String> = emptyList(),
        val fileHashes: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class RestoreState(val roots: List<String>, val absent: List<String>)

    private val json = Json { prettyPrint = true }
    private val stateFile get() = File(journal, "state.json")
    private val completeFile get() = File(journal, "complete")
    private fun newDir(key: String) = File(targets.getValue(key).parentFile, ".${targets.getValue(key).name}.qingyu-new")
    private fun oldDir(key: String) = File(targets.getValue(key).parentFile, ".${targets.getValue(key).name}.qingyu-old")

    fun export(destination: OutputStream, metadata: Metadata) {
        val hashes = linkedMapOf<String, String>()
        var bytes = 0L
        ZipOutputStream(destination.buffered()).use { zip ->
            for ((key, directory) in targets) {
                zip.putNextEntry(ZipEntry("$key/")); zip.closeEntry()
                if (!directory.exists()) continue
                for (file in directory.walkTopDown().filter { it.isFile }) {
                    require(hashes.size + targets.size + 1 < 10_000) { "用户数据文件过多，无法导出" }
                    require(bytes + file.length() <= 512L * 1024 * 1024) { "用户数据过大，无法导出" }
                    require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "备份目录包含不安全路径" }
                    val name = "$key/${file.relativeTo(directory).invariantSeparatorsPath}"
                    require(name.length <= 1024) { "用户数据路径过长" }
                    zip.putNextEntry(ZipEntry(name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            bytes += size
                            require(bytes <= 512L * 1024 * 1024) { "用户数据过大，无法导出" }
                            digest.update(buffer, 0, size); zip.write(buffer, 0, size)
                        }
                    }
                    hashes[name] = digest.digest().joinToString("") { "%02x".format(it) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("metadata.json"))
            val encoded = json.encodeToString(metadata.copy(formatVersion = 2, roots = targets.keys.toList(), fileHashes = hashes)).toByteArray()
            require(encoded.size <= 2 * 1024 * 1024) { "备份文件清单过大" }
            require(bytes + encoded.size <= 512L * 1024 * 1024) { "用户数据过大，无法导出" }
            zip.write(encoded)
            zip.closeEntry()
        }
    }

    /** Validate everything and prepare all replacement trees before changing any target. */
    fun restore(source: InputStream, acceptMetadata: (Metadata) -> Unit): Metadata {
        recover()
        check(journal.mkdirs() || journal.isDirectory) { "无法准备备份恢复" }
        val unpacked = File(journal, "unpacked").apply { mkdirs() }
        try {
            val names = mutableSetOf<String>()
            var bytes = 0L
            ZipInputStream(source.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.trimEnd('/')
                    require(name.isNotEmpty() && name.length <= 1024 && !name.startsWith('/') && '\\' !in name &&
                        name.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "备份路径无效" }
                    require(names.add(name) && names.size <= 10_000) { "备份条目重复或过多" }
                    require(name == "metadata.json" || name.substringBefore('/') in targets) { "备份包含未知数据目录" }
                    val output = File(unpacked, name)
                    require(output.canonicalPath.startsWith(unpacked.canonicalPath + File.separator)) { "备份路径越界" }
                    if (entry.isDirectory) {
                        check(output.mkdirs() || output.isDirectory) { "无法准备备份目录" }
                    } else {
                        check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory) { "无法准备备份目录" }
                        output.outputStream().use { dest ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val size = zip.read(buffer)
                                if (size < 0) break
                                bytes += size
                                require(bytes <= 512L * 1024 * 1024) { "备份解压后过大" }
                                dest.write(buffer, 0, size)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val metadataFile = File(unpacked, "metadata.json")
            require(metadataFile.isFile && metadataFile.length() <= 2 * 1024 * 1024) { "找不到有效的用户数据元数据" }
            val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
            require(metadata.formatVersion in 1..2) { "不支持此备份格式版本" }
            acceptMetadata(metadata)
            val present = targets.keys.filter { File(unpacked, it).isDirectory }
            require(present.isNotEmpty()) { "备份中没有用户数据" }
            if (metadata.formatVersion == 2) {
                require(metadata.roots.toSet() == targets.keys && present.toSet() == targets.keys) { "备份目录不完整" }
                val files = unpacked.walkTopDown().filter { it.isFile && it != metadataFile }
                    .associateBy { it.relativeTo(unpacked).invariantSeparatorsPath }
                require(files.keys == metadata.fileHashes.keys) { "备份文件清单不完整" }
                for ((name, file) in files) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    require(hash == metadata.fileHashes.getValue(name)) { "备份文件校验失败" }
                }
            }
            for (key in present) {
                check(!newDir(key).exists() && !oldDir(key).exists()) { "上次恢复尚未清理" }
                check(File(unpacked, key).copyRecursively(newDir(key), overwrite = false)) { "无法准备恢复数据" }
            }
            writeState(RestoreState(present, present.filter { !targets.getValue(it).exists() }))
            for (key in present) {
                val target = targets.getValue(key)
                if (target.exists()) check(target.renameTo(oldDir(key))) { "无法保留原用户数据" }
                check(newDir(key).renameTo(target)) { "无法替换用户数据" }
            }
            markCompleted()
            // A committed restore remains successful even when obsolete files cannot be cleaned yet.
            runCatching { cleanup() }
            return metadata
        } catch (error: Exception) {
            // Recovery failure is surfaced; retain its journal for the next startup.
            recover()
            throw error
        }
    }

    /** Startup recovery only renames trees; large cleanup can run on a worker thread. */
    fun recover(cleanup: Boolean = true): RecoveryResult {
        // The durable completion marker is sufficient: no rollback metadata is needed anymore.
        if (!completeFile.isFile && stateFile.exists()) {
            val state = try {
                require(stateFile.isFile && stateFile.length() <= 4096) { "恢复记录无效" }
                json.decodeFromString<RestoreState>(stateFile.readText()).also {
                    require(it.roots.isNotEmpty() && it.roots.distinct().size == it.roots.size &&
                        it.roots.all { root -> root in targets } && it.absent.all { root -> root in it.roots }) { "恢复记录无效" }
                }
            } catch (_: Exception) {
                return quarantine()
            }
            run {
                for (key in state.roots.asReversed()) {
                    val target = targets.getValue(key)
                    val old = oldDir(key)
                    if (old.exists()) {
                        if (target.exists()) check(target.renameTo(newDir(key))) { "无法回退新用户数据" }
                        check(old.renameTo(target)) { "无法恢复原用户数据" }
                    } else if (key in state.absent && target.exists() && !newDir(key).exists()) {
                        check(target.renameTo(newDir(key))) { "无法回退用户数据" }
                    }
                }
                markCompleted()
            }
        }
        if (cleanup) cleanup()
        return RecoveryResult.Ready
    }

    /** Untrusted state cannot tell us which trees to roll back; retain every copy for recovery. */
    private fun quarantine(): RecoveryResult {
        val suffix = "-quarantine-${System.currentTimeMillis()}-${System.nanoTime()}"
        // Each rename stays beside its source, including the external private-storage volume.
        for (key in targets.keys) {
            for (source in listOf(oldDir(key), newDir(key))) {
                if (!source.exists()) continue
                val preserved = File(source.parentFile, source.name + suffix)
                check(!preserved.exists() && source.renameTo(preserved)) { "无法保留恢复数据副本" }
            }
        }
        val preservedJournal = File(journal.parentFile, journal.name + suffix)
        check(!preservedJournal.exists() && journal.renameTo(preservedJournal)) { "无法隔离损坏的恢复记录" }
        return RecoveryResult.Quarantined
    }

    fun cleanup() {
        for (key in targets.keys) {
            check(!oldDir(key).exists() || oldDir(key).deleteRecursively()) { "无法清理恢复副本" }
            check(!newDir(key).exists() || newDir(key).deleteRecursively()) { "无法清理恢复临时数据" }
        }
        check(!journal.exists() || journal.deleteRecursively()) { "无法清理恢复记录" }
    }

    private fun writeState(state: RestoreState) {
        val temp = File(journal, "state.tmp")
        temp.outputStream().use { output ->
            output.write(json.encodeToString(state).toByteArray())
            output.flush()
            (output as java.io.FileOutputStream).fd.sync()
        }
        check(temp.renameTo(stateFile)) { "无法保存恢复记录" }
    }

    private fun markCompleted() {
        completeFile.outputStream().use { it.fd.sync() }
    }
}
