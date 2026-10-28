# 技术债与验收清单

更新日期：2026-10-09。只记录有源码或构建证据的工作，状态依据实际验证；本文件的编号可在 issue / PR 中引用。发布阻断项关闭前不将编译通过等同于可公开发行。

## 本轮已处理

| 编号 | 问题与结果 | 验证 |
| --- | --- | --- |
| BUILD-01 | 移除旧 kotlin-android 插件、旧 DSL 开关与 kotlinOptions；采用 AGP 内置 Kotlin、新 DSL、兼容的 Parcelize / serialization 2.2.20 与 KSP 2.3.12 | 两个 ABI 的应用构建通过，原六项 AGP 弃用提示消失 |
| BUILD-03 | debug 不再依赖 release 密钥；删去不存在的 makeaar 产物任务、ARouter 注解参数与多余 JNI 目录映射 | 应用构建使用标准 debug 签名；无新增业务依赖 |
| BUILD-04 | 原生依赖获取依赖本机目录；新增固定提交 / SHA-256 锁定文件与准备脚本 | 现有目录检查及隔离空目录获取验证通过 |
| UI-01 | Kotlin 构造参数注解目标含糊、弹窗回调参数名不一致 | 明确保持原来的 param 目标；与父接口参数名一致，编译验证 |
| DIAG-01 | 崩溃元数据读取弃用的 32 位 versionCode | 使用 PackageInfoCompat 的兼容版本读取 |
| LINT-01 | 键盘图标兼容加载与无基线内容的横向布局 | 改用 AppCompatResources 并关闭无用途的基线对齐 |
| DOC-01 | 文档含机器路径、重复规划、过期功能说明；草稿和本机记录未隔离 | 重写公共入口和开发文档；忽略规则与正式素材依赖检查 |
| LINT-02 | 清掉 7 条 lint 提示：4 个纯代码构造的自定义 View 局部说明 `ViewConstructor`，2 处周期任务说明 `scheduleAtFixedRate` 的固定速率语义；状态栏高度在 API 30+ 改走窗口 insets，不再反射框架资源 | `:imesdk:lintOfflineDebug` 90 → 83 条，无 Error/Fatal；真机冷启动键盘位置不变 |
| CRASH-01 | 冷启动竞态：`onStartInputView` 在输入连接建立前调用 `finishComposingText`，`currentInputConnection` 为 null 直接 NPE 崩溃 | 真机重设输入法后立刻点输入框复现一次；加空安全后同一路径连跑 3 次 `FATAL EXCEPTION` 计数为 0 |

本轮迁移依据：[AGP 内置 Kotlin 指南](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Parcelize 新 Variant API 修复](https://youtrack.jetbrains.com/issue/KT-74420)、[KSP 发行记录](https://github.com/google/ksp/releases/tag/2.3.12)。

## DEBT-LICENSE-01

**P0 · 发行阻断 · 归属与再分发材料。** RichTap 已用Android 公共 API 替换并移除构建依赖；原生许可与版权全文已随 APK 归档且可查看。OpenCC 固定 revision 无 NOTICE，原先“缺失 NOTICE”的记录已纠正。手写识别已切换为自训练模型（27,664 类，int8 8.05 MB，见 [ADR 0008](adr/0008-手写识别切换为自训练模型.md)）：HCCR（CASIA 派生权重）与 PP-OCRv6 分支连同资产一并移除，训练语料仅 OFL 字体离线合成，字表哈希与产物 SHA-256 随 `assets/handwriting/qingyu-hw-image-v1/model-manifest.json` 归档。**剩余**：真人手写基准集、真机准确率与耗时验证尚未完成。自研推理层归属登记已完成；Vosk Android 0.3.47 与中英文小模型（Apache-2.0）已登记，模型按需下载。剩余的是移植 UI 库、图标来源、JVM 全文及最终发行 SBOM。CI 运行时依赖清单只提供审计输入，不能代替许可核验。

验收：每项分发内容记录精确来源、版本、哈希、许可文本和必要修改；确认再分发条件，应用内声明与发行归档一致。未知授权的组件应完成核验或由有明确授权的实现替换。参见 [LICENSING.md](../LICENSING.md)。

## DEBT-DEVICE-01

**P0 · 发行阻断 · 真机覆盖。** 现有输入与窗口结论主要来自 x86_64 模拟器。

验收：arm64 真机覆盖最低支持版本、厂商 ROM、横竖屏、导航、输入状态、重启和升级；触感及系统离线语音单独验证，记录不支持设备上的退路。

## DEBT-BUILD-02

**P1 · 版本与产物可重复性。** `version.properties` 已明确控制两个模块版本，versionCode 保持高于此前日期编码。词库二进制仍可能包含时间戳；逐文件哈希与码空间一致不等同于字节级可重复构建。

验收：发布版本由明确的版本配置控制；固定输入在隔离环境重复构建并比较，记录无法消除的差异；APK 和词库对应源码可从发行 revision 重建。

本机同一 revision / 工具链的连续重新打包，APK SHA-256 一致；这项检查没有清空编译缓存或重建词库，不能代替隔离环境完整重建验收。

2026-10-27 补（词库链路的行尾口径）：上游 blob 是 LF，而 Windows 上 `core.autocrlf=true` 的检出会把工作树换成 CRLF。原脚本 `ESSAY_SHA256` 按 LF 定、`STROKE_DICT_SHA256` 按 CRLF 定，于是同一份内容在两个平台上算出两个哈希，`verify_inputs` 只能对一半机器成立。已改为：校验走 `sha256_text()`（先折 CRLF→LF）、`STROKE_DICT_SHA256` 取 blob 值 `b3e93dce…`、组装与封装时文本统一折成 LF 落盘（`copy_normalized`）、克隆提示带 `-c core.autocrlf=false`。

同日把三份上游按 pin 的 revision 重新克隆，`verify_inputs` **未打桩**通过（不再依赖手工补齐的 `en_dicts`、占位笔画词库与打桩脚本），并在真机跑完组装 → 编译 → 封装。复现口径：`pinyin.table.bin` / `english.table.bin` 与上一版逐字节相同；`*.prism.bin`、`*.schema.yaml` 仅构建时间戳与行尾不同；同一输入连续编译两次 `stroke.table.bin` 字节一致，而 CRLF 方案文件会改变该表字节（16 个笔画码的候选页仍逐条一致）。pack 归档写入时已固定 `ZipInfo` 时间戳（1980-01-01），连续两次 `--package` 的 SHA-256 相同（`4f9b0ef4…`），词库这一层已是字节级可复现。仍未做到的：隔离环境（Linux runner）整链重建 —— 现有证据都来自本机 + 真机。

## DEBT-CI-01

**P1 · 全新 runner 验证。** 已推送 [PR #1](https://github.com/xiaoyuan0459/qingyu-ime/pull/1)，配置两个 ABI、lint、schema / 许可检查、运行时依赖清单与开发产物上传。工作流显式初始化固定版本命令行工具，只安装 platform-tools；清理未使用的预装工具链以留出构建空间，下载步骤设置独立超时。单元和设备测试按当前要求改为手动执行。最终结果以 PR 的最新提交检查和保存的报告为准，整套成功前保持待验收。

验收：PR 在全新 Ubuntu runner 通过 SDK 安装、依赖校验、两个 ABI 编译与 lint，并保存开发产物；确认构建不需要发布 Secrets。当前工作流不自动发布正式 APK。

## DEBT-DATA-01

**P1 · 主线程数据访问与迁移证据。** 已移除 allowMainThreadQueries；数据库初始化与所有 DAO 访问进入有序后台事务，键盘只读隔离快照。导出真实 v6 schema；Android 15 模拟器通过 v1→v6 迁移、置顶裁剪与重复复制、清理历史及主线程拒绝回归。联想改用独立 Rime 会话，后台有界探测、超时 / 取消及引擎代次检查；回归证明保留原组合串、候选、方案与简繁选项。常用语编辑在事务成功前保留原记录和草稿。低存储、异常中断和更多升级来源仍需设备验证。

验收：围绕现有调用链迁移耗时查询，处理取消与过期结果；导出真实 schema 并验证既有迁移，不使用破坏性重建；在低存储、中断和升级路径保护剪贴板、常用语与用户词库。

## DEBT-PLATFORM-01

**P1 · 系统适配警告与向导状态。** 已在 API 33+ 使用 USAGE_TOUCH；旧版振动接口只留在对应兼容分支并说明原因。Android 14+ 无活动连接时用系统 DEFAULT_INPUT_METHOD 规范化比较补足 null；三档在无振幅控制设备上由短时长区分。“系统”档和关闭设置保留。系统关闭及跨 ROM 实际触感仍待真机验收。

没有增加全局警告屏蔽；兼容分支的局部说明不代表真机矩阵已完成。

验收：API 33+ 采用合适的 VibrationAttributes，旧 API 保留经验证的兼容分支；无振动能力、权限 / 系统设置关闭及不同厂商设备均正确降级。向导在无活动编辑连接时仍能准确识别系统默认 IME。触感决策见 [ADR](adr/0001-键盘触感按设备能力适配.md)。

## DEBT-LINT-01

**P2 · 既有 Android lint 提示。** 本轮恢复 lint 错误阻断，未通过全局 suppress 或基线清空提示；历史移植 UI 仍有 lint 警告，需要按影响逐项处理。

2026-10-18 复测：app 1 条 Warning（ChromeOsAbiSupport），imesdk 83 条、无 Error / Fatal。此前 90 条里清掉 7 条：4 个自定义 View 只在代码里按回调构造（`CandidateActions`、`ComposingBubble`、`ClipboardSplitPanel`、`VoiceContainer`），给 `ViewConstructor` 写局部原因而不是补一个用不上的 XML 构造；`ThreadPoolUtils` 两处周期任务保留 `scheduleAtFixedRate` 并说明固定速率语义；状态栏高度在 API 30+ 改用 `WindowMetrics` insets，旧系统才读框架资源。

剩余大头是 `UseKtx`（34）、`GradleDependency`（12）、`NotifyDataSetChanged`（10）、`IconDuplicates`（6）、`VectorPath`（5）。前三类是批量机械改动或依赖升级，按「不机械替换行为」的约定没有一起动；`NotifyDataSetChanged` 的十处需要先确认各列表的插入 / 更新语义再换事件 API。

验收：优先修复无障碍、旧 API、资源和布局中的实际问题；对必须保留的兼容分支给出局部原因，不机械替换行为或隐藏全部提示。

## DEBT-INPUT-01

**已修复并通过模拟器回归 · 原 P1 输入类型重启时残留组合与候选。** 原缺陷为同一 EditText 在中文组合未确认时改类型并调用 restartInput，仍保留旧组合与候选。现使用不可变 EditorContext 比较编辑框语义，变化时清理状态；密码与禁止个性化的输入关闭联想和剪贴板建议。

2026-10-08 验证：[InputView.onStartInputView](../imesdk/src/main/java/com/qingyu/imemodule/keyboard/InputView.kt) 的 10 类类型切换、同语义重启、禁止个性化、服务恢复、上屏与退格通过。全部内容为合成输入，详情见[测试与验收](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Testing)。

发行前补充：厂商 / 浏览器输入框、跨应用、物理键盘及密码 / 剪贴板全矩阵仍需真机验证。当前 `IME_FLAG_NO_PERSONALIZED_LEARNING` 采取直接字符输入以避免进入学习会话，没有提供“不学习的中文拼音会话”；后续需在引擎边界实现该会话并验证。

## DEBT-CORRECTION-01

**已解决（2026-10-17 棱镜侧预计算相邻键变体表；2026-10-21 增加切分权重策略）· 原 P1 按键纠错关闭。** `translator/enable_correction` 在 librime 1.11.2 里实测不可用：纠错候选最多 4 条（`ScriptTranslation::max_corrections_` 写死，无配置项）并按 `credibility + 词频` 与本码候选混排，`log(0.01)` 的惩罚压不住高频字的权重差。放弃该开关，改为把「错一个相邻键」的变体音节直接写入棱镜（2026-10-21 起规则类型由 `derive` 改为 `correct`）：[gen-adjacent-key-algebra.py](../tools/gen-adjacent-key-algebra.py) 以 QWERTY 八邻域对 411 个音节（含 18 个 base 派生异体，如 jv/qv/lue）生成 5799 条规则、4725 个变体，写入 `pinyin` 与 `associate` 两份 schema（共用 `pinyin` 棱镜，`assert_shared_prisms_agree` 钉住同步），`enable_correction` 保持 `false`。可由两个读音产生的歧义变体（`xhong` ← zhong/chong）保留且各出一条规则，中 / 冲按词频竞争，与参考输入法一致。

排序约束：纠错块必须放在 8 条 `fuzz` 之后。放前面时 `fuzz/^([zcs])h/$1/` 会把纠错变体（如 `shong`）再折叠成模糊码（`song`），污染精确输入的候选；放后面则代价只剩「zh/z 类模糊拼写互相纠错」这一种噪声，实测可接受。应用侧 `FuzzySpellingGate` 按注释读音归因，纠错候选的差异不被任何模糊组解释，开关全关时也不会被误杀。

2026-10-17 验证（`qingyu_probe --query` + 真机）：精确输入无污染（`song` 仅出 `song:normal` 的「送」族）；错码被纠正（`xhong`、`shong` 首选「中」，两种读音均可出）；多段组合可纠错（`wpman` → 「我忙」）；既有模糊音排序（`zong` 出「中」高于「总」）不变且仍受闸门控制；真机软键盘敲 `xhong` 候选首位为高亮「中」。

已知边界：只纠错一步（连错两个键不纠）；错码须落在合法音节上（`zhongg` 这类不纠）；纠错候选与本码候选按词频混排，无独立降权 —— 歧义变体处的竞争属预期。改动音节表或邻域定义时重跑生成器并按上述用例差分回归。

2026-10-21 修复（26 键全拼简拼被纠错切分抢占）：修复前 `pinyin yuanl` 的组合串被切成 `yua'nl`（`yua`←`hua`、`nl`←`ni`），首屏是「华丽」，「原来」被挤出。根因是相邻键纠错规则用 `derive`（normal 类型），切分权重与精确拼写相同，把两个变体拼成完整码后压过了 `yuan` + 简拼 `l`。已在 vendored librime 中增加 `kCorrectionSpelling` / `correct/x/X/`，音节图在存在「首段完整音节」的精确通路时剪掉纠错切分；没有该通路时仍把纠错按正常拼写排序，因此 `xhong → 中`、`shong → 中`、`wpman → 我忙` 保持有效。决策、尝试过的失败方案与升级约束见 [ADR 0007](adr/0007-纠错切分权重.md)。

验证（2026-10-21，x86_64 模拟器，`qingyu_probe` 使用打了同一补丁的引擎）：`yuanl` 预编辑 `yuan'l`，候选 原来/原理/原料…且无「华丽」；`xhong`、`shong` 首选「中」；`wpman` 首选「我忙」；`song` 首选「送」；`zong` 开启 zh_z 时「中」仍高于「总」；联想 `yuanl` 返回 原来/原理/原料…；`CorrectionSegmentationEngineTest` 新增上述差分用例。九键用分词键输入 `yuan'l` 时切分明确，行为不变；双拼/笔画不参与该策略。

已知边界：只纠错一步（连错两个键不纠）；错码须落在合法音节上（`zhongg` 这类不纠）；纠错候选与本码候选仍按词频混排，歧义变体处的竞争属预期。librime 已不是 pristine 上游，升级前必须重放 `tools/patch-librime-correction-type.py` 并重跑上述差分；补丁脚本由 CMake 在编译前调用。

## DEBT-FUZZY-T9-01

**部分完成 · 模糊音覆盖全拼与九键，双拼未做。** 九键的 fuzz 规则插在字母→键码映射之前（`t9_pinyin.schema.yaml`），闸门用 `T9PinYinUtils.pinyin2T9Key` 把读音写成键码后归因；`qingyu_probe --codes` 实测 `WMMG → yong:normal | zhong:fuzzy | zong:normal`，`FuzzySpellingGateEngineTest` 覆盖棱镜侧。

双拼的 7 套方案各自把「读音 → 两个键码」整表重编，应用里没有可用的完整反查表（`DoublePinYinUtils.doublePinyinMap` 只有显示用的零散映射，且存在重复键）。要么在打包阶段用 `qingyu_probe --codes` 从各方案棱镜导出「音节 → 精确键码集合」随包分发，闸门按该表归因；要么逐方案补全 Kotlin 码表并与棱镜差分互校。二者都要先解决表与 algebra 的同步，再按 [ADR 0006](adr/0006-模糊音开关与词库内容版本.md) 重编译并更新内容版本标识。

验收：逐方案开关差分（开该组能出派生候选、关掉即无），`qingyu_probe --codes` 记录码空间增减，展开面板与翻页选词按 `engineIndex` 正确上屏。

## DEBT-FUZZY-QWERTY-01

**已修复模糊音关闭时单字母简拼串味与组合串前导分词符 · 2026-10-17 真机复查。** 两个缺陷叠在一起，敲 `n` 时表现为「候选不是 n 开头」加「组合串里多一个分词符号」：

- [FuzzySpellingGate](../imesdk/src/main/java/com/qingyu/inputmethod/FuzzySpellingGate.kt) 原来只认整段相等。`abbrev/^([a-z]).+$/$1/` 的超级简拼让一个字母代表整条读音，`n` 与派生读音 `nai` 对不上，被判成「解释不了」后原样保留，于是 `n` 出「来 lai」。现按 schema 的两条 `abbrev` 规则做前缀归因（首字母；`zh/ch/sh` 再加首字母 + `h`），未穷举的前缀仍按「解释不了就保留」处理，`nian` 命中 `niang` 这类鼻音派生照旧被挡。输入不带 `'` 而注释分了多个音节时，按整串比 —— 这种切分是引擎猜的，`nian` 不再出 `li'an` 的「立案 / 离岸」。
- [QwertyPinYinUtils](../imesdk/src/main/java/com/qingyu/inputmethod/util/QwertyPinYinUtils.kt) 原来每个音节后都补 `'`，首个音节前也补；候选读音与输入对不上时（模糊音、简拼、兜底候选）组合串就成了 `'n`。现只在两段内容之间插分隔符，行尾的待定 `'` 仍按原语义保留（`RimeEngine` 裁尾逻辑不变）。

参考行为（同一台魅族 18 Pro、搜狗魅族版）：敲 `n` 显示裸 `n` 加「你 那 呢 能 拿 哪 弄 年」；多音节缩写才出现分隔符（`m'n` → 买牛奶）。即 `'` 只作音节分隔，从不打头。

验证：`FuzzySpellingGateTest` 新增简拼、整串比较、`z`→`zhong` 与 `nian`→`niang` 用例；新增 `QwertyPinYinUtilsTest` 覆盖前导符回归与待定 `'`；`FuzzySpellingGateEngineTest` 增加单字母用例（展示候选的真实读音必须以 `n` 开头、组合串等于 `n`、打开 `n_l` 后派生的 `l` 读音放行）并真机通过。引擎差分：`n` 100→31、`l` 100→69、`zh` 100→82、`nian` 100→21，`na` / `ni` / `m` / `zong` 不变。

已知边界：闸门按引擎页过滤，一页 100 条被挡后展示数变少，但「更多」仍按引擎页翻，下一页可能还有同码候选（选词不受影响，按 `engineIndex` 回传）；非法音节（`i`、`u`）走 echo / 兜底路径，注释是词库读音但归因解释不了它，仍会出「来 / 就 / 那」一类候选。

2026-10-27 修复（没打完的音节被当成模糊拼写，真机 P1）：26 键全拼敲到 `zhon`（`zhong` 的第 4 键）时组合串与候选整页消失，`中/总/冲/工/空` 打不出来、`zhongguo` 只剩 `g'guo`；`zhang/zheng` 与 `cong/song/tong/dong` 不受影响。根因不在 librime 的排序，而在闸门的归因：`fuzz/([aeiou])ng$/$1n/` 把 `zhong` 派生成 `zhon` 后命中，闸门看到「读音 ≠ 输入」就判成模糊拼写，而 nasal 组默认关闭 → 整页候选被 `RimeEngine.visibleCandidates()` 挡空 → `InputView.updateCandidate()` 走 `resetToIdleState()`，组合串陪着一起丢，第 5 键另起新组合。

修法：闸门新增 `completeSyllables`（`assets/pinyin_table.txt` 的 408 条首读音去调，补 `lv/nv/lve/nve` 四条 ü 拼法，由单测对着该资产逐条钉住）与 `startsTyping()` —— 输入是候选读音的**严格前缀**且自身不是完整音节时不算模糊归因。两个前提缺一不可：`lan` 已经是完整音节，`lang` 只能靠鼻音模糊拼出，nasal 关着仍要挡；九键、乱序 17 的组合串是大写整音节键码，不存在「敲到一半」，规则不参与。

验证（MEIZU 18 Pro / Android 13，`app-offline-debug.apk`）：`zhon` 保留组合并出 `种 中 重 终 众`；`zhong`、`zong`、`chong`、`gong`、`kong` 五个音节逐键输入均正常出字；`zhongguo` + 空格上屏「中国」；`lan` 仍只出 `蓝 烂 懒 拦 篮 岚`，没有 `浪 / 朗`（闸门未被放开）。单测 `FuzzySpellingGateTest` 14 条全通过（新增前缀放行、完整音节仍挡、九键不受影响、音节表与资产一致四条断言）。

## DEBT-HW-01

**P2 · 手写模型把单根横线与省略号混为一类。** 2026-10-27 真机复核：连续手势画一根横线（`input swipe` 与 5 点 `motionevent` 两种都试过）首选都是 `…`（U+2026），「一」排第二；点选首条后读 `EditText.text` 确认上屏字符确实是 `…`。`charset.json` 的 27,664 类里含标点，在 64×64、笔宽约 2.8px 的渲染口径下整条横线与横排省略号落在同一类，属训练分布问题；`HandwritingReranker` 只在本机有选字频次时才重排，改变不了首选。

同轮排除的两个误判（均不成立）：

- 「画布不回显墨迹」是设计行为。[HandwritingKeyboard](../imesdk/src/main/java/com/qingyu/imemodule/keyboard/HandwritingKeyboard.kt) 的 `updatePathDelayed()` 在停笔 `1200 - 识别速度`（默认 700ms）后 `clear()`；截图晚于该窗口就看不到墨迹，窗口内截图可见完整笔画。设置页的手写预览画布是另一个控件，不清空。
- 「两笔没有合成十」是注入节奏。两次 `input swipe` 间隔小于上述窗口时，键盘保留两笔并识别出「十」（首选，同页 `艹 土 千`）；间隔过大则前一笔已被清空，只剩最后一笔参与识别。

验收：用真人笔迹基准集量化单笔画首选，把「一 / … / 丨」这类易混对纳入回归；重训或补样本后复测 top-1 与 top-5，再决定是否需要端上规则兜底。该条不影响已经验证通过的横竖组字（`十`）与半屏面板结构。

## DEBT-BACKUP-01

**已实现并通过契约与冷启动回归 · 原 P1 用户数据导出遗漏当前 Rime 用户目录。** [UserDataManager](../imesdk/src/main/java/com/qingyu/imemodule/manager/UserDataManager.kt) 的版本 2 归档已覆盖实际 `files/rime-data/user`，暂停输入 / 语音并协调引擎、偏好与数据库快照；导入先完整校验，再替换目录，异常或进程中断可回退，成功后要求新进程加载。

2026-10-08 验证：6 项公开归档契约通过；清空隔离包后恢复置顶、常用语和设置标记通过，设备归档包含 17 个用户词库文件且哈希全部匹配。旧格式保留当前词库，损坏 / 缺项 / 越界 / metadata 拒绝及 journal 回退由契约测试覆盖。详见 [备份 ADR](adr/0002-用户备份版本与恢复事务.md)。

追加设置界面回归：正式设置导出 / 导入流程通过；清空隔离包后，数据库字节与 3 份不可变用户词库表匹配，学习首候选、置顶历史可用。重新打开引擎会更新运行日志、manifest 与 user.yaml，不将这些文件启动后的哈希变化计为恢复失败。

发行前补充：真实低存储 / 断电、OEM 文件系统、文件选择器权限以及学习词 / 词频语义的全新设备迁移仍待验证。哈希用于发现损坏，不是来源签名或加密。

## DEBT-UX-01

**P2 · 全功能设计一致性与验收。** [统一原则](https://github.com/xiaoyuan0459/qingyu-ime/wiki/UI-and-Interaction)已覆盖现有全部功能族；覆盖不等于实现和真机验收完成。设置工具箱的宽度／字号适配、可读状态及独立的词库／备份入口仍待验收。

工具栏草稿、真实保存／取消、重启保持和失败重试，以及剪贴板长按菜单、拆分选词、移入短语与右上角清空见[界面与交互](https://github.com/xiaoyuan0459/qingyu-ime/wiki/UI-and-Interaction)。

待完成：键盘调节退出策略、候选空结果／继续查找、完整自定义主题与图片取消分支、遗留工具图标线形、其余持续失败／重试、无编辑框强停后的启用状态、真实 TalkBack／触感及 OEM 宿主。内置离线 ASR 已接入 Vosk 小模型，但真实设备识别质量、模型下载失败路径与端到端验收仍待完成。

数据失败语义还需区分写入事务失败与提交后的快照读取失败；当前 `ImeDataRepository.mutate` 两者均报告失败，后一分支写入可能已经完成。本轮回滚注入只覆盖事务中止，后续应在仓库边界区分结果并验证重试及配置显示，不能承诺读取失败也回到旧数据。

验收：全功能应用表中的每个真实工作流遵守同一标题、返回、状态与编辑契约；窄屏／大字体、快速往返、关闭动画、失败和取消均有对应结果。逐项标注实现及设备证据后关闭，不能由一次构建或部分面板截图整体关闭。

## 既有回归范围

- 设置页保持不透明背景并统一平移动画时长，移除淡入淡出造成的背景闪烁；三档触感保存后即时预览。此前已在 arm64 Android 13 魅族设备覆盖安装，设备锁屏时不能确认主观体验。
- Android 15 x86_64 模拟器运行 5 项设备回归；许可、完整真机矩阵和词库二进制重建差异保留为发行前事项。
- 新工作流覆盖两个 ABI 构建、lint、schema 差异检查、许可哈希和运行时依赖清单；单元与设备用例保留供按需手动执行。GitHub 实际结果及报告见 [PR #1](https://github.com/xiaoyuan0459/qingyu-ime/pull/1) 的最新检查。

## DEBT-UI-DENSITY-01

**P2 · 键盘纵向密度与参考输入法的差额。** 同机型（MEIZU 18 Pro，1080×2400）同宿主实测：默认键区 645 px，搜狗魅族版 611 px，多出约 5.6%；差额来自键高与行距，不是候选栏。默认比例 keyboard_height_ratio 停在 0.3 —— 它同时决定 9 键、17 键、手写、数字四套布局，直接改默认值等于替用户做全局视觉取舍，也不解决横屏（keyboard_height_ratio_landscape 0.5）的同类差额。

本轮给出的折中是开关 键盘设置 → 布局调节 → 紧凑键高（compact_key_height_enable，默认关）：只对 EnvironmentSingleton.normalSkbHeight 乘 0.95，真机实测 645 → 613 px，与参考差 2 px。开关只影响纵向几何，字号、候选栏高度、键宽不变；离开设置页时 KeyboardSettingFragment.onStop 走既有刷新链路（initData + clearKeyboardMap + clearKeyboard），不必重启输入法。

移除条件：把默认比例与行距按四套布局逐一重调并真机验收（竖屏 + 横屏）后，删掉开关与这个 0.95 系数，不留兼容分支。

验收：同宿主下键区高度与参考差 ≤ 20 px；9 键/17 键/手写/数字布局都不溢出、末行不被手势条遮挡；开关切换后无需重启即生效。

## DEBT-CRASH-01

**已修复（2026-10-18 真机）· 冷启动竞态崩溃。** 重设输入法后立刻点输入框，ImeService.onStartInputView → InputView.onStartInputView 会在输入连接建立之前走到 service.finishComposingText()，此时 currentInputConnection 仍为 null，直接 NullPointerException 崩溃（栈顶 ImeService.kt:408，调用方 InputView.kt:866）。冷启动窗口期短，属于典型竞态而不是必现路径。

修复取最窄的一条：inishComposingText() 改为空安全 —— 没有连接就没有预选词要结束，直接返回。没有给整个上屏族加空判断，避免掩盖真正的调用时序错误；同族方法（setComposingText / commitText）在建立连接后才会被触发，保持原样。

验证：修复前同一路径复现 1 次 FATAL；修复后在真机上重设输入法 → 立刻点输入框，连跑 3 个周期，db logcat *:E | grep "FATAL EXCEPTION" 计数为 0，键盘正常弹出、版面与修复前一致。

待办：更根本的做法是在 onStartInputView 之前判断连接是否可用并在连接建立事件里补一次状态清理，这需要再确认 InputMethodService 的生命周期时序，暂不扩大改动。

## DEBT-PERF-01

**P2 · 输入热路径性能的可量化基线。** 2026-10-21 已消掉三处「每键执行且结果不变」的刷新开销：九宫格左侧栏复用 Adapter、候选栏只在形态切换时重新测量、候选列表差量刷新（连同 `setHasFixedSize`）；并去掉逐项绑定里重复的 `setTextSize` 与 `maxWidth` 布局请求，给 `getTextBeforeCursor` 补空安全。绘制路径同批处理：键帽背景整帧共用一个 Drawable，按键高亮改为只重绘受影响的键（`invalidateKeyRegion` + 脏区过滤），使每次触摸的绘制命令从「全部键」降到「1–2 个键」。改动与验证见[性能与内存](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Performance)。

第二轮（同日）把热路径进一步收窄：每键只向 native 取一次整页上下文（此前 `processKey` 与候选装配各取一次）、去掉每键无读取方的 `nativeStatus`、缓存方案 id；候选过滤的输入串每页只解析一次并给模糊音归因加同值短路；九宫格侧栏符号表按类型缓存；按键预览气泡改为常驻并复用背景。临时埋点交替测量同一按键序列，每键主线程处理中位数约 12.7 → 10.2 ms。联想探测改用显式音节分隔符并移除启动后立即 `updateCandidate()` 的自取消，修复了上屏后联想候选不出现的问题（根因两处，见性能记录第 16 项）。

同日追加：新增候选来源排序（精确候选存在时纯纠错候选后移，错码/模糊音不变）；纠错表改为始终解析，排序层只在本页有精确候选时执行；展开候选按内容跨列并允许续行，T9 左列改为真实读音首音节。排序层与冷启动解析开销尚未在干净真机上单独测量。

残留：上述结论只在 x86_64 模拟器上按功能路径验证。`dumpsys gfxinfo` 的对比样本量小、帧数不可控，只能当趋势，不能当定量结论；第二轮的埋点也不含 GPU 绘制与 vsync 等待。真机、横屏、单手模式、大字号候选与展开面板的形态切换都未覆盖。`onUpdateSelection` 的中文联想分支仍是每次光标变化都装配一次候选（后台查询已异步），没有合并同一前缀的重复请求。26 键全拼 `yuanl` 的切分问题已由 ADR 0007 修复；librime 本地补丁的升级差分与性能影响仍需在真机上验证。

验收：用 `Perfetto` / `gfxinfo` 或输入延迟埋点在真机上给出改动前后的对比数据；覆盖九键、全键、双拼、手写四类布局的连续输入；确认 `updateSymbolListView` 在拖拽调高键盘时不会每帧触发 Adapter 重建。
