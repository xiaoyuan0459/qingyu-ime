package com.qingyu.inputmethod.debug

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.imemodule.utils.HanziPinyin
import com.qingyu.inputmethod.AssociatePredictor
import com.qingyu.inputmethod.RimeEngine
import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.core.RimeSession
import com.qingyu.inputmethod.util.LX17PinYinUtils
import com.qingyu.inputmethod.util.T9PinYinUtils
import java.io.File
import java.io.FileInputStream
import java.text.Normalizer
import kotlin.system.measureTimeMillis

/**
 * 自研 native 层的冒烟自检（**仅 debug 变体**，不进发布包）。
 *
 * 为什么需要它：输入法的真实交互要先弹出软键盘再点键，用 adb 盲点既不稳定也很难定位问题。
 * 这里直接调用 [RimeSession] 的公开接口，把「启动 → 切方案 → 打字 → 取候选 → 上屏」整条链路
 * 走一遍并把结果同时写进 logcat 与界面，便于脚本化回归。
 *
 * 用法：
 * ```
 * adb shell am start -n com.qingyu.inputmethod.offline.debug/com.qingyu.inputmethod.debug.EngineSelfCheckActivity
 * adb logcat -s EngineSelfCheck:*
 * ```
 *
 * 顶部还有一个输入框，用于人工验证软键盘（点它即可拉起输入法）。
 */
class EngineSelfCheckActivity : Activity() {

    private lateinit var report: TextView
    private val lines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            // targetSdk 37 强制 edge-to-edge，不加这一行整块内容会从 y=0 开始铺，
            // 顶部输入框被 ActionBar 完全盖住、点不到 —— 而它正是用来拉起键盘验证的。
            fitsSystemWindows = true
        }

        // 人工验证软键盘用的输入框
        val input = EditText(this).apply {
            hint = "点这里拉起键盘，手动验证输入"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(input)

        // 自检报告
        report = TextView(this).apply {
            textSize = 11f
            setPadding(0, 24, 0, 0)
        }
        root.addView(
            ScrollView(this).apply {
                addView(report)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
        )

        val rerun = Button(this).apply {
            text = "重跑自检"
            setOnClickListener { run() }
        }
        root.addView(rerun)

        setContentView(root)
        run()
    }

    // ------------------------------------------------------------------ 自检主体

    private fun run() {
        lines.clear()
        section("引擎自检（debug only）")

        try {
            awaitActivePack()
            checkStart()
            checkSchema()
            checkTyping()
            checkCommit()
            checkSetInputBypass()
            checkOption()
            checkPaging()
            checkEnglishSchema()
            checkT9Schema()
            checkStrokeSchema()
            checkLs17Schema()
            checkMixedChineseEnglish()
            // 联想内部会临时切方案，放在最后以免影响前面的项
            checkAssociation()
            checkAssociationAssembly()
            checkPackSwap()
        } catch (t: Throwable) {
            // 让失败也出现在报告里 —— 尤其 UnsatisfiedLinkError 这类加载期问题
            log("!! 异常终止: ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(6).forEach { log("   at $it") }
        }

        section("结论")
        log(if (lines.any { it.startsWith("!!") }) "存在失败项，见上方 !!" else "全部通过")
        flush()
    }

    /** 词库包在子线程里首装（解包 + 哈希校验），主线程不等它就会空跑一遍自检。 */
    private fun awaitActivePack() {
        val deadline = SystemClock.elapsedRealtime() + PACK_WAIT_TIMEOUT_MS
        while (RimePackManager.activePackDir(this) == null) {
            if (SystemClock.elapsedRealtime() > deadline) {
                fail("${PACK_WAIT_TIMEOUT_MS / 1000}s 内没有可用的 Rime pack —— 内置词库安装失败")
                return
            }
            log("等待内置词库安装…")
            Thread.sleep(500)
        }
    }

    private fun checkStart() {
        section("① 启动引擎（含 schema 配置装载）")
        RimeSession.start()
        log("isComposing = ${RimeSession.isComposing}")
        log("currentSchemaId = ${RimeSession.currentSchemaId()}")
        logStatus("启动后")
    }

    private fun checkSchema() {
        section("② 切换方案")
        val ok = RimeSession.selectSchema(PINYIN)
        log("selectSchema(\"$PINYIN\") = $ok")
        log("currentSchemaId = ${RimeSession.currentSchemaId()}")
        logStatus("切方案后")
        if (!ok) fail("切换方案失败 —— 多半是 build/ 下的 *.schema.yaml 没装载成功")
    }

    private fun checkTyping() {
        section("③ 打字：输入 \"nihao\"")
        type("nihao")
        val context = RimeSession.currentContext()
        log("composition = \"${RimeSession.compositionText}\"")
        log("候选数 = ${context?.menu?.numCandidates ?: 0}, 页内 ${context?.candidates?.size ?: 0}")
        candidates().forEachIndexed { i, c -> log("  [$i] ${c.text}   (${c.comment})") }
        if (candidates().isEmpty()) fail("输入 nihao 没有产生候选")
    }

    private fun checkCommit() {
        section("④ 上屏：选第 0 个候选")
        val before = candidates().firstOrNull()?.text
        log("即将上屏的候选 = $before")
        val selected = RimeSession.selectCandidate(0)
        log("selectCandidate(0) = $selected")
        val commit = RimeSession.commit()
        log("commit = ${commit?.commitText}")
        log("上屏后 composition = \"${RimeSession.compositionText}\"")
        if (commit == null || commit.commitText.isEmpty()) fail("选候选后没有拿到上屏文本")
    }

    private fun checkOption() {
        section("⑤ 选项：简繁切换")
        RimeSession.setScriptMode(1)
        type("hanzi")
        log("native traditional ├ 候选 = ${candidates().take(5).joinToString { it.text }}")
        RimeSession.clearInput()
        RimeSession.setScriptMode(0)
        type("hanzi")
        log("native simplified ├ 候选 = ${candidates().take(5).joinToString { it.text }}")
        RimeSession.clearInput()
    }

    /**
     * ⑥ 联想 —— 以已上屏文本的末字为前缀取后续词（规格见
     * `imesdk/docs/联想功能规格与实现方案.md`）。
     */
    private fun checkAssociation() {
        section("⑬ 联想取词：以「原」为前缀")
        // 逐步展开，便于定位是「取码」「切方案」「检索」哪一环出的问题
        val toned = HanziPinyin.of(PREDICT_KEY[0])
        log("「${PREDICT_KEY}」的第一读音 = \"$toned\"（联想取码即取其声调字母）")
        if (toned.isEmpty()) fail("HanziPinyin 取不到「$PREDICT_KEY」的拼音")
        val switched = RimeSession.selectSchema(ASSOCIATE)
        log("selectSchema(\"$ASSOCIATE\") = $switched，current=${RimeSession.currentSchemaId()}")
        logStatus("联想检索方案")
        RimeSession.clearInput()
        // 对照：只送整音节 vs 补半个后续音节。前者实测 828 项全是单字，
        // 后者才会带出「原来 / 原理」这类多字词 —— 联想取码走的是后者。
        for (probe in listOf(ASSOCIATE_PROBE_CODE, "yuanl")) {
            RimeSession.clearInput()
            type(probe)
            val all = LinkedHashMap<String, String>()
            var pages = 0
            while (pages++ < 12) {
                for (c in candidates()) if (!all.containsKey(c.text)) all[c.text] = c.comment
                if (!RimeSession.hasRight()) break
                RimeSession.processKey(RimeSession.keycodeOf("Page_Down"), 0)
            }
            val prefixed = all.keys.filter { it.length > 1 && it.startsWith(PREDICT_KEY) }
            log("探测 \"$probe\"：$pages 页、${all.size} 项、以「原」开头 ${prefixed.size} → ${prefixed.take(10).joinToString()}")
        }
        RimeSession.clearInput()
        RimeSession.selectSchema(PINYIN)

        val coldStart = measureTimeMillis { AssociatePredictor.words("原") }
        val words = AssociatePredictor.words("原")
        log("words(\"原\") = ${words.size} 项、${words.take(10).joinToString()}")
        log("耗时：首次检索 ${coldStart}ms（26 个声母各一次），命中缓存 ${measureTimeMillis { AssociatePredictor.words("原") }}ms")
        if (words.isEmpty()) {
            fail("联想为空 —— 末字转拼音后在 associate 方案里没查到前缀词")
            return
        }
        val offPrefix = words.filter { !it.startsWith(PREDICT_KEY) || it.length < 2 }
        if (offPrefix.isNotEmpty()) fail("联想项不以末字开头：$offPrefix")
        // 习惯学习：选过一次之后，该词下次应排到最前
        val picked = words.last()
        AssociatePredictor.rememberSelection(PREDICT_KEY[0], picked)
        val afterLearning = AssociatePredictor.words("原").firstOrNull()
        if (afterLearning != picked) fail("习惯学习未生效：首位是 $afterLearning，应为 $picked")
        else log("习惯学习生效：选过「$picked」后排到首位")
    }

    /**
     * ⑦ 候选翻页 —— MIGRATION.md 长期挂着「翻页未逐项验证」，这里补上。
     * 走的是 `hasRight()` + `Page_Down` 这条 UI 实际使用的链路。
     */
    private fun checkPaging() {
        section("⑦ 翻页：输入 \"shi\" 后连续 Page_Down")
        RimeSession.clearInput()
        type("shi")
        val seen = linkedSetOf<String>()
        seen.addAll(candidates().map { it.text })
        log("第 1 页：${seen.take(5)}")
        var pages = 1
        while (pages < MAX_PAGES_PROBED && RimeSession.hasRight()) {
            RimeSession.processKey(RimeSession.keycodeOf("Page_Down"), 0)
            val page = candidates().map { it.text }
            if (page.isEmpty()) break
            seen.addAll(page)
            pages++
            log("第 $pages 页：${page.take(5)}")
        }
        log("共 $pages 页、${seen.size} 个不同候选")
        if (pages < 2) fail("「shi」这类高频音节应当有多页候选，实际只到 $pages 页")
        RimeSession.clearInput()
    }

    /** ⑧ 英文方案 —— 「英文输入」这条功能的运行时证据。 */
    private fun checkEnglishSchema() {
        section("⑧ 英文方案：切到 english 后输入 \"hello\"")
        val switched = RimeSession.selectSchema(ENGLISH)
        log("selectSchema(\"$ENGLISH\") = $switched，current=${RimeSession.currentSchemaId()}")
        if (!switched) fail("切到英文方案失败")
        logStatus("英文方案")
        RimeSession.clearInput()
        val consumed = type("hello")
        val list = candidates().take(5).joinToString { it.text }
        log("候选 = $list，composition = \"${RimeSession.compositionText}\"")
        if (!consumed && list.isEmpty() && RimeSession.compositionText.isEmpty()) {
            fail("英文方案下 hello 既没被按键消费、也没有候选或输入串")
        }
        RimeSession.clearInput()
        RimeSession.selectSchema(PINYIN)
    }

    /** ⑨ 九宫格 —— 默认方案就是 t9_pinyin，键码序列要能出候选。 */
    private fun checkT9Schema() {
        section("⑨ 九宫格：切到 t9_pinyin 后按「hao」的键码")
        val switched = RimeSession.selectSchema(T9)
        log("selectSchema(\"$T9\") = $switched，current=${RimeSession.currentSchemaId()}")
        if (!switched) fail("切到九宫格方案失败")
        // 九宫格送进引擎的是**大写字母键码**（一个键代表多个声母/韵母），
        // 不是数字，故取应用自己那份映射。
        val keys = T9PinYinUtils.pinyin2Key("hao")
        log("pinyin2Key(\"hao\") = \"$keys\"")
        if (keys.isEmpty()) fail("pinyin2Key 查不到 hao 的键码")
        RimeSession.clearInput()
        type(keys)
        val list = candidates().take(5).joinToString { it.text }
        log("候选 = $list，composition = \"${RimeSession.compositionText}\"")
        if (list.isEmpty()) fail("九宫格按 $keys 没有候选")
        RimeSession.clearInput()
        RimeSession.selectSchema(PINYIN)
    }

    /**
     * ⑩ 五笔画 —— 词库来自 rime/rime-stroke，码是小写 hspnz，而笔画键盘送大写码
     * （InputView 给该键盘带 CAPS_LOCK），所以 schema 里用 xform 把码翻成大写。
     */
    private fun checkStrokeSchema() {
        section("⑩ 五笔画：H=横 S=竖 P=撇 N=点 Z=折")
        val switched = RimeSession.selectSchema(STROKE)
        log("selectSchema(\"$STROKE\") = $switched，current=${RimeSession.currentSchemaId()}")
        if (!switched) fail("切到笔画方案失败 —— pack 里没有 stroke 产物？")
        logStatus("笔画方案")
        RimeSession.clearInput()
        type("H")
        log("单键 H 候选 = ${candidates().take(5).joinToString { it.text }}")
        if (candidates().isEmpty()) fail("笔画方案下单键 H 没有候选")
        // 码表里的真实笔画码：一=h 二=hh 三=hhh 小=zpn
        for ((code, expected) in listOf("HH" to "二", "HHH" to "三", "ZPN" to "小")) {
            RimeSession.clearInput()
            type(code)
            val list = candidates().take(12).map { it.text }
            log("$code 候选 = ${list.take(8).joinToString()}")
            if (!list.contains(expected)) fail("$code 的前 12 项里没有「$expected」")
        }
        RimeSession.clearInput()
        RimeSession.selectSchema(PINYIN)
    }

    /**
     * ⑪ 乱序17 —— 键盘键表（`LX17PinYinUtils`）与棱镜必须说的是同一套码：
     * 用键表拼出的二码送进引擎，候选里要能找到读音就是那个音节的字。
     */
    private fun checkLs17Schema() {
        section("⑪ 乱序17：pinyin2Key 的键序 ↔ 棱镜码 闭环")
        val switched = RimeSession.selectSchema(LS17)
        log("selectSchema(\"$LS17\") = $switched，current=${RimeSession.currentSchemaId()}")
        if (!switched) fail("切到乱序17方案失败")
        var probed = 0
        for (syllable in LS17_PROBE_SYLLABLES) {
            val keys = LX17PinYinUtils.pinyin2Key(syllable)
            if (keys.isEmpty()) {
                fail("pinyin2Key(\"$syllable\") 查不到键码 —— 键表缺这一项")
                continue
            }
            RimeSession.clearInput()
            type(keys)
            val hit = candidates().firstOrNull { plainOf(it) == syllable }
            log("\"$syllable\" → 键码 $keys → ${if (hit == null) "无匹配候选" else "命中「${hit.text}」"}")
            if (hit == null) fail("乱序17 键码 $keys（$syllable）在引擎里查不到同读音的字")
            probed++
        }
        log("共验证 $probed 个音节")
        RimeSession.clearInput()
        RimeSession.selectSchema(PINYIN)
    }

    /** ⑫ 中英混输 —— 中文方案内的 cn_en（stabledb 词表）与 english 直出。 */
    private fun checkMixedChineseEnglish() {
        section("⑫ 中英混输：pinyin 方案内输入 Xguang / python")
        RimeSession.clearInput()
        type("Xguang")
        val mixed = candidates().take(10).map { it.text }
        log("\"Xguang\" 候选 = ${mixed.joinToString()}")
        if (!mixed.any { it.contains("光") }) {
            fail("cn_en 词表没读到 —— 检查 pack 的 en_dicts/cn_en.txt 是否在位")
        }
        RimeSession.clearInput()
        type("python")
        val englishInside = candidates().take(10).map { it.text }
        log("\"python\" 候选 = ${englishInside.joinToString()}")
        if (!englishInside.any { it.lowercase().contains("python") }) {
            fail("中文方案内没有英文直出 —— 检查 table_translator@english")
        }
        RimeSession.clearInput()
    }

    /**
     * ⑮ 词库包切换 —— 传 `--es install-pack <设备路径>` 才跑。
     *
     * 走的是设置页「导入 Rime 词库包」同一条链路（校验 → 原子切换 → 重启引擎），
     * 只是把 SAF 选择器换成固定路径，好让整件事可脚本化。它验的是别处验不到的东西：
     * 换包之后引擎能不能用**另一份词库**正常起、以及失败时回滚是否真回得去。
     */
    private fun checkPackSwap() {
        val path = intent?.getStringExtra(EXTRA_INSTALL_PACK) ?: return
        section("⑮ 词库包切换：$path")
        val file = File(path)
        if (!file.isFile) {
            fail("文件不存在或应用不可读：$path")
            return
        }
        val before = RimePackManager.activeManifest(this)?.id ?: "(无)"
        log("当前 pack = $before")
        val install = FileInputStream(file).use { RimePackManager.install(this, it) }
        if (install.isFailure) {
            fail("导入失败：${install.exceptionOrNull()?.message}")
            return
        }
        val result = install.getOrThrow()
        log("已启用 ${result.manifest.displayName} ${result.manifest.version}（许可 ${result.manifest.license.spdx}）")
        val active = RimePackManager.activeManifest(this)
        if (active?.id == before) fail("切换后 active pack 没变")
        if (!RimeSession.start()) fail("新 pack 下引擎没起来")
        if (!RimeSession.selectSchema(PINYIN)) fail("新 pack 下选不到 pinyin 方案")
        RimeSession.clearInput()
        type("nihao")
        val list = candidates().take(5).joinToString { it.text }
        log("新 pack 打 nihao → $list")
        if (list.isEmpty()) fail("新 pack 下没有候选")
        RimeSession.clearInput()
        if (!RimePackManager.rollback(this)) {
            fail("回滚失败 —— 停在 ${active?.id}，需要手工清理 files/rime-data")
            return
        }
        val restored = RimePackManager.activeManifest(this)?.id
        log("回滚后 pack = $restored，打 nihao → ${run { RimeSession.clearInput(); type("nihao"); candidates().firstOrNull()?.text }}")
        if (restored != before) fail("回滚没回到原来的 pack：$restored != $before")
    }
    /** 取候选词的无声调拼音，用于「键码 → 音节 → 字 → 音节」闭环比对。 */
    private fun plainOf(candidate: Candidate): String = candidate.text.firstOrNull()
        ?.let { HanziPinyin.of(it) }
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(TONE_MARKS, "").lowercase() }
        ?.filter { it in 'a'..'z' }
        ?: ""

    /**
     * ④b 区间替换 —— 直接向引擎设置输入串。
     *
     * 上游没有「区间替换」API，native 层是用「读当前串 → 拼接 → RimeSetInput 整体设回」
     * 模拟的（旧实现靠私有补丁 `replaceRimeKey`），故列入需在设备上逐项对照的差异清单。
     * 排在 ④ 之后：它会清空输入串，放在 ④ 前面会把 ④ 依赖的编辑态抹掉。
     */
    private fun checkSetInputBypass() {
        section("④b 区间替换：直接设置输入串 \"nihao\"")
        RimeSession.clearInput()
        RimeSession.replaceInput(0, 0, "nihao")
        log("composition = \"${RimeSession.compositionText}\"")
        log("候选数 = ${RimeSession.currentContext()?.menu?.numCandidates ?: 0}")
        candidates().take(8).forEachIndexed { i, c -> log("  [$i] ${c.text}  (${c.comment})") }
        RimeSession.clearInput()
    }

    /**
     * ⑭ 联想装配链路 —— 走 [com.qingyu.inputmethod.RimeEngine] 而不是直接调
     * `AssociatePredictor`：候选组装、标点联动插入位、「上屏时去掉已存在的前缀字」
     * 这三件事只存在于 RimeEngine 这一层，⑥ 覆盖不到。
     */
    private fun checkAssociationAssembly() {
        section("⑭ 联想装配：predictAssociationWords / selectAssociation")
        RimeEngine.predictAssociationWords(PREDICT_KEY, AssociatePredictor.words(PREDICT_KEY))
        val shown = RimeEngine.showCandidates
        log("showCandidates = ${shown.size} 项，前 8 = ${shown.take(8).joinToString { it.text }}")
        if (shown.isEmpty()) {
            fail("装配后候选为空 —— ⑥ 有词但 RimeEngine 没接上")
            return
        }
        val offPrefix = shown.filter { it.comment != "📋" && !it.text.startsWith(PREDICT_KEY) }
        if (offPrefix.isNotEmpty()) fail("装配出非前缀项：${offPrefix.map { it.text }}")
        val first = shown[0].text
        RimeEngine.selectAssociation(0)
        val expected = first.removePrefix(PREDICT_KEY)
        log("选第 0 项「$first」→ preCommitText = \"${RimeEngine.preCommitText}\"（期望 \"$expected\"）")
        if (RimeEngine.preCommitText != expected) {
            fail("上屏文本没去掉已上屏的前缀字 —— 会重复输入「$PREDICT_KEY」")
        }
        RimeEngine.reset()
    }

    // ------------------------------------------------------------------ 工具

    /** 逐键送入引擎，返回是否至少有一个键被消费。 */
    private fun type(text: String): Boolean {
        val results = StringBuilder()
        var anyConsumed = false
        for (ch in text) {
            val consumed = RimeSession.processKey(ch.code, 0)
            anyConsumed = anyConsumed || consumed
            results.append(ch).append('=').append(if (consumed) '1' else '0').append(' ')
        }
        log("按键消费: $results")
        return anyConsumed
    }

    /**
     * 引擎状态全量输出。
     *
     * `schemaName` 带 `?` 是 librime 的既定信号：`Schema::FetchUsefulConfigItems`
     * 在方案配置加载失败时把名字设成 `"<schema_id>?"`。一旦如此，
     * `ConcreteEngine::InitializeComponents` 会在 `if (!config) return;` 处提前返回，
     * 处理器一个都不建 —— 症状正是「引擎能启动、能切方案，但按键全都不被消费」。
     */
    private fun logStatus(prefix: String) {
        val s = RimeSession.currentStatus()
        if (s == null) {
            fail("$prefix：nativeStatus() 返回 null")
            return
        }
        log(
            "$prefix status: id=${s.schemaId} name=${s.schemaName} disabled=${s.isDisable} " +
                "composing=${s.isComposing} ascii=${s.isAsciiMode} fullShape=${s.isFullShape} " +
                "simple=${s.isSimplified} trad=${s.isTraditional} asciiPunct=${s.isAsciiPunch}"
        )
        if (s.schemaName.endsWith("?")) {
            fail("$prefix：schemaName 带问号 → 方案配置未加载成功，引擎不会建任何处理器")
        }
    }

    private fun candidates(): List<Candidate> =
        RimeSession.currentContext()?.candidates?.toList() ?: emptyList()

    private fun section(title: String) {
        lines.add("")
        lines.add("===== $title =====")
    }

    private fun log(message: String) {
        lines.add(message)
        Log.i(TAG, message)
    }

    private fun fail(message: String) = log("!! $message")

    private fun flush() {
        report.text = lines.joinToString("\n")
    }

    companion object {
        private const val TAG = "EngineSelfCheck"
        private const val PINYIN = "pinyin"
        private const val ENGLISH = "english"
        private const val T9 = "t9_pinyin"
        private const val STROKE = "stroke"
        private const val LS17 = "double_pinyin_ls17"
        private const val ASSOCIATE = "associate"
        private const val PREDICT_KEY = "原"
        /** 「原」的无调拼音，直接喂给 associate 方案做前缀检索。 */
        private const val ASSOCIATE_PROBE_CODE = "yuan"
        private const val MAX_PAGES_PROBED = 5
        /** 避开 ü 类音节：去声调后 ü 会塌成 u，比不出读音。 */
        private val LS17_PROBE_SYLLABLES = listOf("hao", "shi", "zhong", "ming", "an", "qing", "ren")
        private val TONE_MARKS = "\\p{Mn}+".toRegex()
        private const val PACK_WAIT_TIMEOUT_MS = 60_000L
        /** 传了这个 extra 才跑词库包切换实测（仅 debug 变体）。 */
        private const val EXTRA_INSTALL_PACK = "install-pack"
    }
}
