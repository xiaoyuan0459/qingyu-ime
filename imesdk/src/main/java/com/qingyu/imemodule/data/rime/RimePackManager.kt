package com.qingyu.imemodule.data.rime

import android.content.Context
import android.util.Log
import com.qingyu.inputmethod.core.RimeSession
import java.io.File
import java.io.InputStream

/** Android 侧的 Rime 数据入口。引擎只能从这里取得 shared/user 目录。 */
object RimePackManager {
    private const val TAG = "RimePackManager"
    private const val BUILTIN_ASSET = "rime-base.zip"

    @Volatile
    private var initializedRoot: String? = null

    @Volatile
    private var storeInstance: RimePackStore? = null

    @Synchronized
    fun ensureBuiltinInstalled(context: Context): Result<RimePackStore.InstallResult?> = runCatching {
        migrateLegacyUserData(context)
        val store = store(context)
        // 指针损坏时先回滚到上一次可用 pack，别急着把用户的词库重装一遍。
        val active = store.activePack() ?: store.rollback()
        val builtin = context.assets.open(BUILTIN_ASSET).use(RimePackArchive::readManifest)
        if (isBuiltinCurrent(active, builtin)) return@runCatching null
        context.assets.open(BUILTIN_ASSET).use(store::install)
    }.onFailure { Log.e(TAG, "内置词库初始化失败", it) }

    /**
     * 已装 pack 是否就是 APK 里那份内置包。清单带全量文件哈希，所以改一条 algebra 也会
     * 被认出来；只看 id 相同就跳过，会让设备上永远留着旧词库（模糊音就是这么白测一轮的）。
     * 用户自行导入的其他词库（id 不同）不能被内置包覆盖回去。
     */
    private fun isBuiltinCurrent(active: File?, builtin: RimePackManifest): Boolean {
        if (active == null) return false
        val installed = runCatching { RimePackVerifier.readManifest(active) }.getOrNull() ?: return false
        val legacyBuiltin = builtin.id == "rime-frost-qingyu" &&
            installed.id == "rime-ice-qingyu" &&
            installed.source.url == "https://github.com/iDvel/rime-ice/tree/9e66b0729083b37d217312294f6d516c8d7234be" &&
            installed.source.revision == "9e66b0729083b37d217312294f6d516c8d7234be"
        return (!legacyBuiltin && installed.id != builtin.id) || installed == builtin
    }

    fun install(context: Context, archive: InputStream): Result<RimePackStore.InstallResult> {
        val wasRunning = RimeSession.isRunning
        if (wasRunning) RimeSession.stop()
        val result = runCatching { store(context).install(archive) }
        if (!wasRunning) return result
        if (result.isFailure) {
            if (!RimeSession.start()) Log.e(TAG, "pack 安装失败后旧引擎恢复失败")
            else com.qingyu.inputmethod.core.Kernel.nativeUpdateImeOption()
            return result
        }
        if (RimeSession.start()) {
            com.qingyu.inputmethod.core.Kernel.nativeUpdateImeOption()
            return result
        }

        Log.e(TAG, "pack 安装后引擎重启失败")
        val restored = rollback(context)
        if (restored) {
            Log.w(TAG, "已自动回滚到安装前的 pack")
        } else {
            Log.e(TAG, "自动回滚失败")
        }
        return Result.failure(
            IllegalStateException(
                if (restored) "新词库无法启动，已回滚" else "新词库无法启动，且自动回滚失败"
            )
        )
    }

    fun activePackDir(context: Context): File? = store(context).activePack()

    fun activeManifest(context: Context): RimePackManifest? =
        activePackDir(context)?.let(RimePackVerifier::readManifest)

    fun sharedDataDir(context: Context): File? = activePackDir(context)

    fun userDataDir(context: Context): File = File(dataRoot(context), "user").apply {
        require(mkdirs() || isDirectory) { "无法创建 Rime 用户目录" }
    }

    fun rollback(context: Context): Boolean {
        RimeSession.stop()
        val restored = store(context).rollback() ?: return false
        Log.w(TAG, "已回滚 Rime pack: ${restored.name}")
        return RimeSession.start().also { started ->
            if (started) com.qingyu.inputmethod.core.Kernel.nativeUpdateImeOption()
        }
    }

    private fun store(context: Context): RimePackStore {
        val root = dataRoot(context).canonicalPath
        val current = storeInstance
        if (current != null && initializedRoot == root) return current
        return synchronized(this) {
            if (storeInstance == null || initializedRoot != root) {
                storeInstance = RimePackStore(File(root)) { packRoot ->
                    RimeSession.stop()
                    val buildDir = File(packRoot, "build")
                    if (buildDir.exists()) {
                        require(buildDir.canonicalPath.startsWith(packRoot.canonicalPath + File.separator))
                        require(buildDir.deleteRecursively()) { "无法清理旧 build 目录" }
                    }
                    require(buildDir.mkdirs()) { "无法创建 build 目录" }
                    val temporary = File(context.cacheDir, "rime-deploy-${java.util.UUID.randomUUID()}")
                    require(temporary.mkdir()) { "无法创建词库编译临时目录" }
                    try {
                        RimeSession.buildWorkspace(
                            sharedDataDir = packRoot,
                            userDataDir = temporary,
                            stagingDir = buildDir,
                            logDir = File(context.filesDir, "rime-log").apply { mkdirs() },
                        )
                    } finally {
                        check(temporary.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                        temporary.deleteRecursively()
                    }
                }
                initializedRoot = root
            }
            requireNotNull(storeInstance)
        }
    }

    private fun dataRoot(context: Context) = File(context.filesDir, "rime-data")

    /**
     * 从旧版 shared/user 混用目录中只迁移用户数据。编译词库、schema 和 opencc 资产不迁移，
     * 避免新 pack 又依赖回来源不明的旧二进制。
     */
    private fun migrateLegacyUserData(context: Context) {
        val destination = userDataDir(context)
        val marker = File(destination, ".legacy-migrated")
        if (marker.exists()) return
        val legacy = context.getExternalFilesDir("rime")
        if (legacy != null && legacy.isDirectory) {
            legacy.listFiles().orEmpty().filter { file ->
                file.name == "user.yaml" ||
                    file.name == "installation.yaml" ||
                    file.name.endsWith(".userdb") ||
                    file.name.endsWith(".userdb.kct") ||
                    file.name.endsWith(".userdb.txt")
            }.forEach { source ->
                val target = File(destination, source.name)
                if (!target.exists()) source.copyRecursively(target, overwrite = false)
            }
        }
        marker.writeText("1\n")
    }

}
