package com.qingyu.imemodule.data.rime

import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * pack 的事务存储：所有不可信输入先进入 staging，完整校验/编译成功后才移动到版本目录，
 * 最后一步才原子更新 active 指针。旧版本不会在安装过程中被覆盖。
 */
class RimePackStore(
    private val root: File,
    private val compiler: Compiler,
) {
    fun interface Compiler {
        /** 把 [packRoot] 的源配置编译到其 `build/` 目录。 */
        fun compile(packRoot: File): Boolean
    }

    data class InstallResult(
        val manifest: RimePackManifest,
        val directory: File,
        val previous: File?,
    )

    private val packsDir get() = File(root, "packs")
    private val stagingDir get() = File(root, "staging")
    private val activePointer get() = File(root, "active-pack")
    private val previousPointer get() = File(root, "previous-pack")

    @Synchronized
    fun install(archive: InputStream): InstallResult {
        ensureDirectories()
        val stage = File(stagingDir, "import-${UUID.randomUUID()}")
        try {
            RimePackArchive.extract(archive, stage)
            return finishInstall(stage)
        } catch (error: Throwable) {
            stage.deleteRecursivelyWithin(stagingDir)
            throw error
        }
    }

    /** 安装已经复制到 staging 下的目录，供内置 pack 初始化使用。 */
    @Synchronized
    fun installStaged(stage: File): InstallResult {
        ensureDirectories()
        require(stage.canonicalFile.parentFile == stagingDir.canonicalFile) { "内置 pack 不在 staging 目录" }
        try {
            return finishInstall(stage)
        } catch (error: Throwable) {
            stage.deleteRecursivelyWithin(stagingDir)
            throw error
        }
    }

    @Synchronized
    fun activePack(): File? = readPointer(activePointer)

    @Synchronized
    fun rollback(): File? {
        val previous = readPointer(previousPointer) ?: return null
        val current = readPointer(activePointer)
        writePointer(activePointer, relativePackPath(previous))
        if (current != null) writePointer(previousPointer, relativePackPath(current))
        return previous
    }

    fun stagingRoot(): File = stagingDir.apply { mkdirs() }

    private fun finishInstall(stage: File): InstallResult {
        val initial = RimePackVerifier.verify(stage, allowGeneratedBuildFiles = false)
        val artifactsPresent = runCatching {
            RimePackVerifier.verifyRequiredArtifacts(initial)
        }.isSuccess
        if (!artifactsPresent) {
            require(compiler.compile(stage)) { "librime 编译词库失败" }
        }
        val completed = RimePackVerifier.verify(stage, allowGeneratedBuildFiles = true)
        RimePackVerifier.verifyRequiredArtifacts(completed)

        val target = File(File(packsDir, completed.manifest.id), completed.manifest.version)
        val previous = activePack()
        if (target.exists()) {
            val installed = RimePackVerifier.verify(target, allowGeneratedBuildFiles = true)
            require(installed.manifest == completed.manifest) { "同版本 pack 内容不同，请提升版本号" }
            RimePackVerifier.verifyRequiredArtifacts(installed)
            stage.deleteRecursivelyWithin(stagingDir)
        } else {
            val parent = requireNotNull(target.parentFile)
            require(parent.mkdirs() || parent.isDirectory) { "无法创建 pack 版本目录" }
            require(stage.renameTo(target)) { "无法原子安装词库包" }
        }

        if (previous != null && previous != target) {
            writePointer(previousPointer, relativePackPath(previous))
        }
        writePointer(activePointer, relativePackPath(target))
        pruneUnreferenced(kept = setOf(target, readPointer(previousPointer)))
        return InstallResult(completed.manifest, target, previous)
    }

    /**
     * 删除既不是 active 也不是 previous 的版本目录。一份词库 80 MB 上下，
     * 回滚链只有一层，留着历史版本等于每次升级白占一份磁盘。
     */
    private fun pruneUnreferenced(kept: Set<File?>) {
        val keep = kept.filterNotNull().map { it.canonicalFile }.toSet()
        packsDir.listFiles()?.filter { it.isDirectory }?.forEach { idDir ->
            idDir.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                if (versionDir.canonicalFile !in keep) {
                    versionDir.deleteRecursivelyWithin(idDir)
                }
            }
            if (idDir.listFiles()?.isEmpty() == true) idDir.delete()
        }
    }

    private fun ensureDirectories() {
        require(root.mkdirs() || root.isDirectory) { "无法创建 Rime 数据目录" }
        require(packsDir.mkdirs() || packsDir.isDirectory) { "无法创建 pack 目录" }
        require(stagingDir.mkdirs() || stagingDir.isDirectory) { "无法创建 staging 目录" }
    }

    private fun readPointer(file: File): File? {
        val readable = when {
            file.isFile -> file
            File(file.parentFile, "${file.name}.bak").isFile -> File(file.parentFile, "${file.name}.bak")
            else -> return null
        }
        val raw = readable.readText(Charsets.UTF_8).trim()
        if (raw.isEmpty()) return null
        val parts = raw.split('/')
        if (parts.size != 2) return null
        val candidate = File(File(packsDir, parts[0]), parts[1])
        val canonicalPacks = packsDir.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        if (!canonicalCandidate.path.startsWith(canonicalPacks.path + File.separator)) return null
        return canonicalCandidate.takeIf { it.isDirectory }
    }

    private fun relativePackPath(directory: File): String {
        val relative = directory.canonicalFile.relativeTo(packsDir.canonicalFile).invariantSeparatorsPath
        val parts = relative.split('/')
        require(parts.size == 2) { "非法 pack 目录: $relative" }
        return relative
    }

    private fun writePointer(pointer: File, value: String) {
        val temp = File(pointer.parentFile, "${pointer.name}.tmp")
        val backup = File(pointer.parentFile, "${pointer.name}.bak")
        temp.writeText("$value\n", Charsets.UTF_8)
        // 新指针已经落盘，残留备份不应把一次成功安装误报为失败；下次写入还会先清理它。
        if (backup.exists()) backup.delete()
        if (pointer.exists()) require(pointer.renameTo(backup)) { "无法备份 ${pointer.name}" }
        if (!temp.renameTo(pointer)) {
            if (backup.exists()) backup.renameTo(pointer)
            error("无法更新 ${pointer.name}")
        }
        if (backup.exists()) backup.delete()
    }
}

private fun File.deleteRecursivelyWithin(parent: File) {
    val safeParent = parent.canonicalFile
    val safeTarget = canonicalFile
    require(safeTarget.path.startsWith(safeParent.path + File.separator)) { "拒绝越界删除: $safeTarget" }
    deleteRecursively()
}
