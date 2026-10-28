package com.qingyu.inputmethod

/**
 * 模糊音开关的「关」侧实现。
 *
 * librime 1.11.2 的 `speller/algebra` 没有「按开关生效」的能力：`Calculus::Parse` 只取
 * 前两段（`algo/calculus.cc`），`fuzz/^([zcs])h/$1/zh_z` 末尾那个选项名会被静默丢掉，
 * 规则一旦编进棱镜就常开。所以词库侧用 `fuzz` 而不是 `derive`：派生拼写被标成
 * `kFuzzySpelling` 并扣 `log(0.5)` 可信度，`compare_chunk_by_head_element` 据此把模糊码
 * 排到本码之后；「关掉某一组」则由这里按候选注释里的**真实读音**过滤完成 ——
 * `ScriptTranslator::Spell` 的注释是拿词库音节拼出来的，与用户输入不一致就说明走了派生拼写。
 *
 * 只挡能归因到模糊音的候选：注释为空、或差异解释不了（按键纠错、补全、英文词表）时一律
 * 保留，开关不该顺手吃掉别的候选。
 *
 * 归因不只认整段相等：`abbrev/^([a-z]).+$/$1/` 的超级简拼让一个字母就代表整条读音，`n` 既
 * 可能是 `na` 也可能是 `nai`。只认相等的话 `n` 与派生读音 `nai` 对不上，关掉 `n_l` 后 `来 lai`
 * 照样留在候选里；反过来，`nian` 命中 `niang` 这类补全/鼻音派生又必须挡住，所以只放开
 * schema 里那两条 `abbrev` 真正会生成的缩写。
 *
 * 还有一种「差异」根本不是模糊音：输入是读音前缀时用户还没打完（`zhon` → `zhong`）。
 * 这类一律保留，见 [startsTyping]；否则整页会被挡空，组合串跟着被丢掉。
 */
object FuzzySpellingGate {

    /**
     * 组名 → 该组的读音改写。组名与 `AppPrefs` 的开关一一对应，规则内容必须与
     * `assets/rime/build/…schema.yaml` 里的 `fuzz` 逐条一致，
     * 由 `FuzzySpellingGateTest` 解析 schema 钉住，防止只改一边。
     */
    internal val rewrites: Map<String, List<Pair<Regex, String>>> = linkedMapOf(
        "zh_z" to listOf(
            Regex("^([zcs])h") to "$1",
            Regex("^([zcs])([^h])") to "$1h$2",
        ),
        "n_l" to listOf(
            Regex("^n") to "l",
            Regex("^l") to "n",
        ),
        "f_h" to listOf(
            Regex("^f") to "h",
            Regex("^h") to "f",
        ),
        "nasal" to listOf(
            Regex("([aeiou])ng$") to "$1n",
            Regex("([aeiou])n$") to "$1ng",
        ),
    )

    internal val allGroups: Set<String> = rewrites.keys.toSet()
    private val juUToV = Regex("^([jqxy])u$")
    private val nveToNue = Regex("^([nl])ve$")

    /**
     * 完整的汉语拼音音节表：`assets/pinyin_table.txt` 里每条首读音去声调、ü 写 v 后的
     * 全部 408 个音节，由 `FuzzySpellingGateTest` 对着该文件逐条钉住。
     *
     * 只用在一处判断上：输入本身是不是一条**打完了的**音节。`zhon` 不在表里，说明用户还在
     * 打 `zhong`；`lan` 在表里，`lang` 就只能靠前后鼻音模糊才拼得出来。
     */
    internal val completeSyllables: Set<String> = """
        a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
        ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong
        chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo
        da dai dan dang dao de den deng di dian diao die ding diu dong dou du duan dui dun
        duo e ei en eng er fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge
        gei gen geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei
        hen heng hm hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie
        jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke ken keng kong kou ku kua
        kuai kuan kuang kui kun kuo la lai lan lang lao le lei leng li lia lian liang liao
        lie lin ling liu lo long lou lu luan lue lun luo lv lve m ma mai man mang mao me mei men
        meng mi mian miao mie min ming miu mo mou mu n na nai nan nang nao ne nei nen neng
        ni nian niang niao nie nin ning niu nong nou nu nuan nue nun nuo nv nve o ou pa pai pan
        pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie
        qin qing qiong qiu qu quan que qun ran rang rao re ren reng ri rong rou ru rua ruan
        rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shen sheng
        shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo
        ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa
        wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu
        xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan
        zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhen zheng zhi zhong zhou
        zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** 读音音节 → 用户实际敲出的形式。全拼是恒等；九键要先把拼音映射成键码再比。 */
    fun interface Encoder {
        fun encode(syllable: String): String
    }

    /**
     * 预解析后的用户输入。
     *
     * 候选过滤对整页每条候选都要用到同一个组合串；此前每条候选都重新 split 一遍，
     * 一页 100 条就是 100 次重复分配。装配候选前 [parseTyped] 一次，两个闸门共用。
     *
     * [compact] 去掉音节分隔符，供候选来源排序做「整串能否精确拼出」的匹配。
     */
    internal class TypedInput(val syllables: List<String>, val compact: String)

    internal fun parseTyped(typed: String): TypedInput =
        TypedInput(syllables(typed), typed.filter { it != '\'' && it != ' ' })

    /** schema 允许的读音改写里，不依赖模糊/纠错开关的基础写法。 */
    internal fun baseForms(sound: String): List<String> {
        val forms = LinkedHashSet<String>()
        forms += sound
        (juUToV.matchEntire(sound))?.let { forms += "${it.groupValues[1]}v" }
        (nveToNue.matchEntire(sound))?.let { forms += "${it.groupValues[1]}ue" }
        return forms.toList()
    }

    internal fun readingSyllables(comment: String): List<String> = syllables(comment)

    /**
     * [comment] 候选注释里的真实读音，[typed] 引擎原始组合串，[enabled] 打开的组名，
     * [encode] 把读音写成输入形式的换算（九键传键码表，其余恒等）。
     * 段数一致时逐音节比；输入不带 `'` 而注释分了多个音节，切分本身就是引擎的猜测，
     * 按整串比才判断得出「这个切法是不是靠模糊音拼出来的」。
     */
    fun keeps(
        comment: String,
        typed: String,
        enabled: Set<String>,
        encode: Encoder = Encoder { it },
    ): Boolean = keeps(comment, parseTyped(typed), enabled, encode)

    internal fun keeps(
        comment: String,
        typed: TypedInput,
        enabled: Set<String>,
        encode: Encoder,
    ): Boolean {
        if (enabled.containsAll(allGroups)) return true
        val reading = syllables(comment)
        val input = typed.syllables
        if (reading.isEmpty() || input.isEmpty()) return true
        val pairs = if (reading.size == input.size) {
            reading.zip(input)
        } else {
            listOf(reading.joinToString("") to input.joinToString(""))
        }
        if (pairs.any { (sound, keys) -> !explainedBy(sound, keys, allGroups, encode) }) {
            // 音节边界可能错位：`zangan` 的引擎切分是 zan'gan，而候选讀音是 zhang'an。
            // 逐段看 an/gan 解释不了，但整串 zhang'an → zang'an 只差一次 zh_z，
            // 仍应算模糊候选，不能因为切分不同就漏挡。
            if (matchesCompact(reading, typed.compact, allGroups, encode) &&
                !matchesCompact(reading, typed.compact, enabled, encode)) {
                return false
            }
            return true
        }
        return pairs.all { (sound, keys) -> explainedBy(sound, keys, enabled, encode) }
    }

    /** 忽略输入的分词边界，按读音逐音节生成写法后整串匹配。 */
    private fun matchesCompact(
        reading: List<String>,
        compact: String,
        groups: Set<String>,
        encode: Encoder,
    ): Boolean {
        if (reading.isEmpty() || compact.isEmpty()) return false
        var reachable = BooleanArray(compact.length + 1).also { it[0] = true }
        for (sound in reading) {
            val forms = LinkedHashSet(baseForms(sound))
            forms += forms(sound, groups)
            val encodedForms = forms.flatMap { form ->
                listOf(form) + abbreviationPrefixes(form)
            }.map { encode.encode(it) }.filter { it.isNotEmpty() }
            val next = BooleanArray(compact.length + 1)
            var any = false
            for (start in 0..compact.length) {
                if (!reachable[start]) continue
                for (form in encodedForms) {
                    val end = start + form.length
                    if (end <= compact.length &&
                        compact.regionMatches(start, form, 0, form.length, ignoreCase = true)) {
                        next[end] = true
                        any = true
                    }
                }
            }
            if (!any) return false
            reachable = next
        }
        return reachable[compact.length]
    }

    /**
     * 这段输入能否由该读音拼出来：读音在 [groups] 下能变成的写法里，有一条正好是它，或是
     * 它按简拼规则能缩成的写法。空输入段（多打了一个 `'`）一律算能拼，别把用户的手滑当成模糊音。
     */
    private fun explainedBy(
        sound: String,
        keys: String,
        groups: Set<String>,
        encode: Encoder,
    ): Boolean = admitted(sound, keys, encode) ||
        startsTyping(sound, keys, encode) ||
        forms(sound, groups).any { form -> admitted(form, keys, encode) || startsTyping(form, keys, encode) }

    /**
     * [keys] 是 [form] 还没敲完的前缀：用户正在打这条读音，不是「靠模糊音才拼得出」。
     *
     * 26 键全拼里 `zhon` 是 `zhong` 打了一半，librime 用 `fuzz` 把 `zhong` 派生成 `zhon` 后
     * 命中，闸门若按「读音 ≠ 输入」归因就会把整页候选挡空，组合串跟着被丢掉（D-01）。
     *
     * 两个前提缺一不可：
     * - [keys] 是纯小写字母（一个字母一敲）。九键、乱序 17 的组合串是整音节键码，一按就是
     *   一个完整音节的字母组，不存在「敲到一半」；
     * - [keys] 自己不是完整音节。`lan` 已经打完了，`lang` 只能靠前后鼻音模糊拼出来，
     *   nasal 关着时仍要挡；`zhon` 不是音节，才按没打完处理。
     */
    private fun startsTyping(form: String, keys: String, encode: Encoder): Boolean {
        if (keys.isEmpty() || keys.any { it !in 'a'..'z' }) return false
        if (keys in completeSyllables) return false
        val encoded = encode.encode(form)
        if (keys.length >= encoded.length) return false
        return encoded.startsWith(keys)
    }

    /** [keys] 就是 [form]（九键为键码），或是它的一条简拼写法。 */
    private fun admitted(form: String, keys: String, encode: Encoder): Boolean =
        encode.encode(form) == keys || abbreviationPrefixes(form).any { encode.encode(it) == keys }

    /**
     * `abbrev/^([a-z]).+$/$1/` 与 `abbrev/^([zcs]h).+$/$1/`：超极简拼只敲首字母，zh/ch/sh 还能
     * 敲两个字母。这两种缩写与模糊音无关，`z` 命中 `zhong` 时不该按 `zh_z` 挡掉。
     */
    internal fun abbreviationPrefixes(form: String): List<String> = when {
        form.length < 2 -> emptyList()
        form[1] == 'h' && form[0] in "zcs" -> listOf(form.substring(0, 1), form.substring(0, 2))
        else -> listOf(form.substring(0, 1))
    }

    /** 一个读音在给定规则组下能被拼成哪些写法（与 `Projection::Apply` 逐轮展开同构）。 */
    internal fun forms(sound: String, groups: Set<String>): Set<String> {
        var forms = setOf(sound)
        rewrites.forEach { (group, rules) ->
            if (group in groups) {
                rules.forEach { (pattern, replacement) ->
                    forms += forms.map { pattern.replace(it, replacement) }
                }
            }
        }
        return forms
    }

    /** 纯字母音节才可能是拼音读音，其余（`📋`、`~` 补全、数字键码）一律不当模糊音处理。 */
    private fun syllables(text: String): List<String> =
        text.split('\'', ' ').filter { part -> part.isNotEmpty() }.takeIf { parts ->
            parts.isNotEmpty() && parts.all { part -> part.all { it in 'a'..'z' || it in 'A'..'Z' } }
        }.orEmpty()
}
