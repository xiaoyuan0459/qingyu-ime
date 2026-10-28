package com.qingyu.inputmethod.core

import android.content.Context
import androidx.annotation.WorkerThread
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import java.io.File

/**
 * librime 会话绑定 —— 上层与 native 引擎之间的唯一通道。
 *
 * 设计要点：
 * - 一个进程共享一个引擎，输入与联想使用独立会话；[start] / [stop] 成对管理生命周期，
 *   [start] 幂等，重复调用无副作用。
 * - `private external fun` 是 native 边界，名称统一以 `native` 开头；
 *   native 侧对应导出符号为 `Java_com_qingyu_inputmethod_core_RimeSession_<方法名>`。
 * - 方法名与数据类名均按语义命名（旧实现沿用了 `xxxRimeXxx` 式命名，见 LICENSING.md）。
 *
 * 候选下标的约定：本类对外一律使用**全局下标**（不是页内下标），
 * 与 `RimeSelectCandidate` 的语义一致。上层 `DecodingInfo.nextPageCandidates`
 * 是累积式翻页，因此累积后的列表下标天然等于全局下标。
 *
 * 数据类见 [EngineTypes]。
 */
object RimeSession {

    /** librime 的 `XK_VoidSymbol`，表示无按键，需在送入引擎前过滤。 */
    private const val KEYCODE_VOID_SYMBOL = 0xffffff

    @Volatile
    private var running = false

    @Volatile var generation = 0L
        private set

    val isRunning: Boolean
        get() = running

    private var inputContext: InputContext? = null
    private var status: Status? = null

    /**
     * 当前方案 id 的本地缓存。
     *
     * 只有 [selectSchema] / [start] / [stop] 会改变方案；此前每次装配候选都要向 native
     * 问三到四次，每键都是纯重复调用。方案切换时更新一次即可，调用方仍用
     * [currentSchemaId] 读取。
     */
    @Volatile
    private var schemaId: String = ""

    init {
        System.loadLibrary("qingyu_rime")
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 启动引擎。已在运行则直接返回。
     *
     * @param recheckData 是否让引擎重新校验词库数据
     */
    @Synchronized
    fun start(recheckData: Boolean = false): Boolean {
        if (running) return true
        val context: Context = Launcher.instance.context
        val sharedDataDir = RimePackManager.sharedDataDir(context) ?: return false
        val userDataDir = RimePackManager.userDataDir(context)
        // 引擎日志目录放在**应用内部**存储：外部存储上的文件属主权限与
        // scoped storage 会让 `adb run-as` 也读不到，排查问题时取不出来。
        // 目录须先建好 —— glog 不会自己创建它（只会往里写文件）。
        val engineLogDir = File(context.filesDir, "rime-log").apply { mkdirs() }
        val started = nativeStart(
            context,
            sharedDataDir.absolutePath,
            userDataDir.absolutePath,
            engineLogDir.absolutePath,
            recheckData,
        )
        if (!started) return false
        refreshStatus()
        schemaId = nativeCurrentSchema()
        generation += 1
        running = true
        return true
    }

    /** 关闭引擎并释放 native 资源。未运行则直接返回。 */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        generation += 1
        nativeStop()
        inputContext = null
        status = null
        schemaId = ""
        running = false
    }

    /** 使用上游 librime 的公开 deployer API，把 pack 源编译进独立 staging 目录。 */
    @Synchronized
    fun buildWorkspace(
        sharedDataDir: File,
        userDataDir: File,
        stagingDir: File,
        logDir: File,
    ): Boolean {
        if (running) return false
        return nativeBuildWorkspace(
            sharedDataDir.absolutePath,
            userDataDir.absolutePath,
            stagingDir.absolutePath,
            logDir.absolutePath,
        )
    }

    // ---------------------------------------------------------------- 状态查询

    val isComposing: Boolean
        get() = status?.isComposing == true

    val composition: Composition?
        get() = inputContext?.composition

    val compositionText: String
        get() = composition?.preedit ?: ""

    /**
     * 最近一次按键 / 选择后从引擎取回的上下文。
     *
     * [processKey] / [replaceInput] / [selectCandidate] / [clearInput] 都会刷新它，按键后的
     * 候选装配直接读这里即可。此前 [currentContext] 又被调用一次，而 native 每次都会把整页
     * 候选重新构造成 Kotlin 对象 —— 每键两整页，翻倍了 JNI 与分配开销。
     */
    val context: InputContext?
        get() = inputContext

    /**
     * 当前输入上下文（直接向引擎读取，并更新本地缓存）。
     *
     * 与 [isComposing] / [compositionText] 等缓存属性不同，本方法每次都取实时值，
     * 供需要「绕过最近一次刷新」的排查或测试场景使用；输入热路径请读 [context]。
     */
    fun currentContext(): InputContext? = nativeContext().also { inputContext = it }

    /**
     * 当前引擎状态（实时读取，不读缓存）。
     *
     * 排查时特别关注 [Status.schemaName]：`librime` 的 `Schema::FetchUsefulConfigItems`
     * 在方案配置**未能加载**时会把它设成 `"<schema_id>?"`，因此带问号即等于
     * 「schema yaml 没读进来」——这会让 `ConcreteEngine::InitializeComponents` 提前 return，
     * 结果是处理器一个都不建、所有按键都无人消费。
     */
    fun currentStatus(): Status? = nativeStatus().also { status = it }

    /** 是否有候选菜单（且候选数不为 0）。 */
    fun hasMenu(): Boolean = isComposing && (inputContext?.menu?.numCandidates ?: 0) != 0

    /** 是否还有下一页候选。 */
    fun hasRight(): Boolean = hasMenu() && inputContext?.menu?.isLastPage == false

    // ---------------------------------------------------------------- 输入处理

    /**
     * 送入一个按键。返回引擎是否消费了该按键。
     *
     * 候选页大小不在此处设置 —— 它由输入方案的 `menu/page_size` 决定（见 assets 中的
     * schema 配置）。旧实现在这里调用私有补丁 `setRimePageSize(100)`，而该能力上游并不存在，
     * 已按「迁移即重构」原则删除。
     *
     * @param modifiers 修饰键掩码（如 Shift / CapsLock）
     */
    fun processKey(keycode: Int, modifiers: Int): Boolean {
        if (keycode <= 0 || keycode == KEYCODE_VOID_SYMBOL) return false
        return nativeProcessKey(keycode, modifiers).also { refreshContext() }
    }

    /**
     * 替换输入串的指定区间（用于九键转拼音等场景）。
     *
     * @param caretPosition 起始位置
     * @param length 被替换的长度
     * @param text 替换成的内容
     */
    fun replaceInput(caretPosition: Int, length: Int, text: String): Boolean =
        nativeReplaceInput(caretPosition, length, text).also { refreshContext() }

    /** 清空输入串。 */
    fun clearInput() {
        nativeClearInput()
        refreshContext()
    }

    /** 选中第 [index] 个候选。 */
    fun selectCandidate(index: Int): Boolean =
        nativeSelectCandidate(index).also { refreshContext() }

    /** 取上屏结果；无内容待上屏时返回 null。 */
    fun commit(): Commit? = nativeCommit()

    // ---------------------------------------------------------------- 方案与选项

    /** 切换输入方案。 */
    fun selectSchema(schemaId: String): Boolean {
        val selected = nativeSelectSchema(schemaId)
        if (selected) this.schemaId = nativeCurrentSchema().ifEmpty { schemaId }
        refreshContext()
        return selected
    }

    /** 当前方案 id；由 [selectSchema] / [start] 维护，不再每键查询 native。 */
    fun currentSchemaId(): String = schemaId

    /** 设置引擎选项（如 `traditionalization`、`emoji`）。 */
    fun setOption(option: String, value: Boolean) = nativeSetOption(option, value)

    /** 按键名取键码（如 `Page_Down`、`BackSpace`）。 */
    fun keycodeOf(name: String): Int = nativeKeycodeOf(name)

    /** 独立会话中的一次有界联想探测，不改变输入会话或其 Kotlin 缓存。 */
    @WorkerThread
    fun associationProbe(input: String, prefix: String, maxPages: Int): Array<String> =
        if (running) nativeAssociationProbe(input, prefix, maxPages) else emptyArray()

    /**
     * 五笔画通配查询：`*` 代表任意一笔（最多两处），返回去重后的候选。
     * 只读独立会话，不改变当前输入组合或 Kotlin 缓存。
     */
    @WorkerThread
    fun strokeWildcard(pattern: String, maxCandidates: Int): Array<String> =
        if (running) nativeStrokeWildcard(pattern, maxCandidates) else emptyArray()
    // ---------------------------------------------------------------- 内部状态刷新
    /** Read-only lookup in the current pack. No candidate, option or learning session is changed. */
    @WorkerThread
    fun strokeCodes(characters: List<String>): Map<String, String> {
        if (characters.isEmpty()) return emptyMap()
        require(characters.size <= 128 && characters.all(HanText::isSingle))
        check(running) { "输入引擎尚未就绪" }
        val root = requireNotNull(RimePackManager.sharedDataDir(Launcher.instance.context))
        val file = File(root, "build/stroke.reverse.bin")
        check(file.isFile) { "当前词库未提供笔画反查资料" }
        val result = nativeStrokeCodes(file.absolutePath, characters.joinToString("\n").toByteArray(Charsets.UTF_8))
        check(result != null && result.size == characters.size) { "无法读取笔画反查资料" }
        return characters.zip(result.asList()).toMap()
    }

    private fun refreshStatus() {
        status = nativeStatus() ?: Status()
    }

    /**
     * 按键后的上下文刷新。
     *
     * 这里只取输入上下文。引擎状态（schema / composing / ascii…）在按键热路径上没有
     * 读取方，此前每键多一次 `nativeStatus`；需要状态的排查走 [currentStatus]。
     */
    private fun refreshContext() {
        inputContext = nativeContext() ?: InputContext()
    }

    // ---------------------------------------------------------------- native 边界

    private external fun nativeStart(
        context: Context,
        sharedDataDir: String,
        userDataDir: String,
        logDir: String,
        recheckData: Boolean,
    ): Boolean

    private external fun nativeBuildWorkspace(
        sharedDataDir: String,
        userDataDir: String,
        stagingDir: String,
        logDir: String,
    ): Boolean

    private external fun nativeStop()

    private external fun nativeProcessKey(keycode: Int, modifiers: Int): Boolean

    private external fun nativeReplaceInput(caretPosition: Int, length: Int, text: String?): Boolean

    private external fun nativeClearInput()

    private external fun nativeCommit(): Commit?

    private external fun nativeContext(): InputContext?

    private external fun nativeStatus(): Status?

    private external fun nativeSetOption(option: String, value: Boolean)

    private external fun nativeCurrentSchema(): String

    private external fun nativeSelectSchema(schemaId: String): Boolean

    private external fun nativeSelectCandidate(index: Int): Boolean

    private external fun nativeKeycodeOf(name: String): Int

    private external fun nativeAssociationProbe(input: String, prefix: String, maxPages: Int): Array<String>
    private external fun nativeStrokeWildcard(pattern: String, maxCandidates: Int): Array<String>
    private external fun nativeStrokeCodes(path: String, characters: ByteArray): Array<String>?
}
