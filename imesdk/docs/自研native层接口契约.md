# 自研 native 层：接口契约与实现说明

## 2026-10-08：只读笔画反查

新增后台 `RimeSession.strokeCodes(characters)` 和 private JNI `nativeStrokeCodes(path, characters: ByteArray)`，批次最多 128 个单汉字／1024 字节标准 UTF-8。路径由当前 pack 和固定 `build/stroke.reverse.bin` 生成；返回按输入顺序排列的编码，查无该字返回空编码，装载失败或返回数量不符报不可用。JNI ByteArray 避免补充平面汉字经过 modified UTF-8。

会话仍通过原有 C API 操作；新 `stroke_lookup.cc` 使用 librime 1.11.2 的 ReverseDb C++ 类型打开局部只读映射，不访问会话或用户词库，因此不持有会话锁。升级时需核对该类型及反查格式，不能仅检查 C API。异步结果由候选面板校验请求、修订号、代次和可见性后应用；取消不强行中断正在执行的有界 native 读取。契约与取舍见 [ADR 0003](../../docs/adr/0003-候选展示筛选与只读笔画反查.md)。下面 2026-09-19 的入口数量与产物尺寸保留为历史记录。

## 2026-10-07：独立联想会话

`RimeSession.associationProbe(input, prefix, maxPages)` 仅供后台使用。JNI 验证 1–32 个小写 ASCII 字母与 1–2 页上限，`association.cc` 管理独立的 associate 会话；查询不切换输入方案、不修改输入会话的组合串或 Kotlin 输入缓存。引擎停止时先释放联想会话再 finalize。

输入与探测共用 native 互斥锁，每次探测单独获取锁，26 个声母探测之间允许输入请求插入。取消是协作式：正在执行的有界 native 探测结束后停止下一次探测；2.5 秒超时不强行终止 librime。结果应用前核对请求序号、输入状态与引擎代次，切输入框、按键、切键盘和收起窗口都会使旧结果失效。缓存键包含引擎代次，重启或切包后不复用旧结果。

Android 15 设备回归验证冷查询返回联想词且保留方案、简繁选项、组合串和候选；恢复旧查询时同一用例复现组合串丢失。JNI 已移除按键码与组合串正文日志，保留启动 / 失败元数据。

> 状态：**绑定层（Kotlin 侧）与 native 实现（`jni.cc`）均已完成**；arm64-v8a 已编译通过。
> 待真机验证运行时行为。
> 关联：`MIGRATION.md`（迁移总览）、`LICENSING.md`（为什么必须自研）、
> `联想功能规格与实现方案.md`（联想能力，后续阶段）

---

## 一、为什么自研这一层

三件事同时解决（详见 `LICENSING.md`）：

1. **包名绑定** —— 旧 `libyuyanime.so` 内嵌 `com.yuyan.pinyin.*` 白名单，本项目包名不在其中；
2. **无法升级** —— 旧库内嵌 librime 1.11.2（2024-12），且源码不可得；
3. **来源不清** —— 旧绑定层的方法名与 GPL-3.0 的 Trime 高度重合，BSD-3 主张站不住。

自研后：类名、方法名、字段名**全部由本项目定义**，不再受任何外部二进制或第三方命名约束。

---

## 二、Kotlin 侧（已完成）

| 文件 | 说明 |
|---|---|
| `core/RimeSession.kt` | `object`，唯一 native 通道；`start()` / `stop()` 管理生命周期，`start` 幂等 |
| `core/EngineTypes.kt` | 7 个数据类：`Candidate` / `Schema` / `Commit` / `Composition` / `CandidateMenu` / `Status` / `InputContext` |

命名对照（旧 → 新）：

| 旧 | 新 |
|---|---|
| `Rime`（class + companion） | **`RimeSession`**（object） |
| `getInstance()` / `startup()` / `destroy()` | `start()` / `start()` / `stop()` |
| `getRimeContext()` | `currentContext()` |
| `getRimeStatus()` | 内部 `refreshStatus()`（对外暴露 `isComposing` / `compositionText` 等属性） |
| `getRimeCommit()` | `commit()` |
| `processRimeKey()` / `replaceRimeKey()` / `clearRimeComposition()` | `processKey()` / `replaceInput()` / `clearInput()` |
| `getCurrentRimeSchema()` / `selectRimeSchema()` / `selectRimeCandidate()` | `currentSchemaId()` / `selectSchema()` / `selectCandidate()` |
| `setRimeOption()` / `getRimeKeycodeByName()` | `setOption()` / `keycodeOf()` |
| `setRimePageSize()` | **已删除**（上游无此能力，见第六节） |
| `getRimeAssociateList()` / `selectRimeAssociate()` | `associateWords()` / `selectAssociate()` |
| `RimeCommit` / `RimeComposition` / `RimeContext` / `RimeMenu` / `RimeStatus` / `CandidateListItem` / `SchemaItem` | `Commit` / `Composition` / `InputContext` / `CandidateMenu` / `Status` / `Candidate` / `Schema` |

> **数据类字段名保持不变** —— 字段由 JNI 按名填充，是接口契约；现有字段名已语义清楚，无需改动。

---

## 三、native 侧需要实现的 14 个导出符号

Kotlin 侧全部为 `private external fun`，统一以 `native` 开头；对应 C++ 导出符号如下
（实例方法，故 JNI 第二参数是 `jobject` 而非 `jclass`）：

```
Java_com_qingyu_inputmethod_core_RimeSession_nativeStart
Java_com_qingyu_inputmethod_core_RimeSession_nativeBuildWorkspace
Java_com_qingyu_inputmethod_core_RimeSession_nativeStop
Java_com_qingyu_inputmethod_core_RimeSession_nativeProcessKey
Java_com_qingyu_inputmethod_core_RimeSession_nativeReplaceInput
Java_com_qingyu_inputmethod_core_RimeSession_nativeClearInput
Java_com_qingyu_inputmethod_core_RimeSession_nativeCommit
Java_com_qingyu_inputmethod_core_RimeSession_nativeContext
Java_com_qingyu_inputmethod_core_RimeSession_nativeStatus
Java_com_qingyu_inputmethod_core_RimeSession_nativeSetOption
Java_com_qingyu_inputmethod_core_RimeSession_nativeCurrentSchema
Java_com_qingyu_inputmethod_core_RimeSession_nativeSelectSchema
Java_com_qingyu_inputmethod_core_RimeSession_nativeSelectCandidate
Java_com_qingyu_inputmethod_core_RimeSession_nativeKeycodeOf
```

> `nativeStart` 现在返回 `jboolean`（会话建不起来时调用方要能感知），
> `nativeBuildWorkspace` 是词库包编译入口。旧联调用
> `nativeAssociateWords` / `nativeSelectAssociate` 已删除 —— 中文联想改在
> Kotlin 侧用上游能力实现，见 `AssociatePredictor`。

实现文件：`src/main/cpp/qingyu_rime/jni.cc`。

### 分工

- **13 个**由上游 `rime_api.h` 直接实现（`nativeBuildWorkspace` 走其中的 deployer 段：
  `RimeDeployerInitialize` + `RimeDeployWorkspace`）；
- **`nativeKeycodeOf`** 走上游未在公开头文件里导出、但同为 librime 自带的
  `RimeGetKeycodeByName`（`rime/key_table.h`）；
- **中文联想不在这份契约里**：上游没有按文字前缀检索的能力（旧实现是打在 librime 本体里的
  私有补丁 `rime::AssociateRear` 等 58 个符号）。现在改成 Kotlin 侧
  `AssociatePredictor`：末字转拼音 + `associate` 方案的 `enable_completion` 前缀检索，
  native 边界因此比旧库更薄，也不再有任何私有补丁痕迹。

---

## 四、数据类的字段契约（JNI 按名填充）

| 类 | 字段 |
|---|---|
| `Commit` | `commitText: String` |
| `Composition` | `length: Int`、`cursorPos: Int`、`selStart: Int`、`selEnd: Int`、`preedit: String?` |
| `CandidateMenu` | `pageSize: Int`、`pageNo: Int`、`isLastPage: Boolean`、`highlightedCandidateIndex: Int`、`numCandidates: Int`、`candidates: Array<Candidate>` |
| `Candidate` | `comment: String`、`text: String` |
| `Status` | `schemaId`、`schemaName: String`；`isDisable`、`isComposing`、`isAsciiMode`、`isFullShape`、`isSimplified`、`isTraditional`、`isAsciiPunch: Boolean` |
| `InputContext` | `composition: Composition?`、`menu: CandidateMenu?`、`commitTextPreview: String?`、`selectLabels: Array<String>` |
| `Schema` | `id: String`、`name: String` |

### 填充方式：走构造函数，不是逐字段写

这些类的字段全部是 Kotlin `val`（编译为 JVM `final` 字段），**无法用
`SetXxxField` 写入**。因此 `jni.cc` 一律用 `NewObject` + 全参构造函数，签名必须与
上表**声明顺序严格一致**。

由此带来两条实现约束：

1. 字段**增删或调序**都必须同步改 `jni.cc` 里的构造签名；
2. C 侧的 `char*` 可能是 null，而 Kotlin 侧这些字段是非空的 —— 传 null 会在构造函数里
   触发 `checkNotNullParameter` 抛异常。`jni.cc` 统一用 `MakeString()` 把 null 转成空串。

---

## 五、数据侧：配置文件同样来自旧库

**这是自研过程中最隐蔽的一处依赖。**

旧 `assets/rime/` 里只有 `build/*.bin`（14 个 prism/table 二进制），**没有任何
`*.schema.yaml`**。而 librime 的 Schema / Config 组件走的是
`DeployedConfigResourceProvider`——只从 `build/` 目录找 `.yaml`：

```
core_module.cc:
  r.Register("config", config_loader);            // ← DeployedConfigResourceProvider
  r.Register("schema", new SchemaComponent(config_loader));

service.cc:
  DeployedResourceResolver:
    root_path          = user_data_dir  / "build"   (staging_dir)
    fallback_root_path = shared_data_dir / "build"  (prebuilt_data_dir)
```

也就是说：**配置与编译产物必须同处 `build/`**。旧库的做法是把这些配置
**内嵌在 `.so` 的 `.rodata` 里**，运行时再写出到 `build/`——这解释了为什么
`assets/` 里看不到它们。

本工程改为**直接把配置放进 `assets/rime/build/`**，随 assets 一起拷贝，
不再需要运行时写文件。内容由工具从旧库中还原：

| 工具 | 说明 |
|---|---|
| `tools/extract-embedded-rime-config.py` | 扫描 `.so`，按 NUL 分隔取出内嵌 YAML，依内容特征识别归属并写出 |

还原结果（已入库）：

- `build/default.yaml` —— 内嵌的只有 `schema_list` 一段（11 个方案）
- `build/*.schema.yaml` —— 11 个方案的完整定义，与 14 个 `.bin` 一一对应

> ⚠️ 这些配置**不是** rime 官方配方，而是上游作者自制的精简配方：没有 `menu` 段
> （页大小取 librime 默认值 5）、`punctuator.symbols` 与常见配方不同。
> 改动它们等于改输入行为，须谨慎。

---

## 六、与旧实现的已知行为差异（需真机验证）

| # | 差异 | 说明 |
|---|---|---|
| 1 | **候选页大小** | 旧实现在每次按键前调用私有补丁 `setRimePageSize(100)`，让候选一次取满；上游无此能力，已删除。页大小现由 schema 的 `menu/page_size` 决定（当前为默认 5）。上层 `DecodingInfo.nextPageCandidates` 是**累积式**翻页，累积后下标天然等于全局下标，故 5 不影响正确性，只影响首屏候选数量与翻页次数。 |
| 2 | **区间替换** | 上游没有「替换输入串某一段」的 API。`nativeReplaceInput` 用「读出 preedit → 拼接 → `RimeSetInput` 整体设回」实现。旧实现是私有补丁，行为是否完全一致需真机对照。 |
| 3 | **`recheckData` 参数** | 上游语义是让 deployer 重新校验/重建数据。本工程只分发编译产物、不含 `*.dict.yaml` 源，设备端无法重建，故该参数当前不生效，预留给将来接入 schema 源后使用。 |
| 4 | **联想** | 两个方法返回空结果，联想候选暂时消失（见 `联想功能规格与实现方案.md`）。 |

---

## 七、构建依赖（均已就位）

| 依赖 | 位置 | 说明 |
|---|---|---|
| librime **1.11.2** | `third_party/librime` | 浅克隆 + 6 个依赖子模块，66 MB |
| Boost **1.87.0** | `third_party/boost/include` | **仅头文件**（15435 个 .hpp，181 MB）。已验证 librime 在非 Linux 平台不需要编译 Boost.Regex |

两者均被 `imesdk/.gitignore` 排除，fresh clone 后需重新获取（见 `MIGRATION.md`）。

### 构建编排（`src/main/cpp/qingyu_rime/CMakeLists.txt`）

不走 librime 的 `find_package`（那要求依赖已 install 到某前缀），而是把依赖当源码
子目录直接编成静态库，再把 librime 期望的变量指过去：

| librime 期望的变量 | 指向 |
|---|---|
| `YamlCpp_*` | `third_party/librime/deps/yaml-cpp`（target `yaml-cpp`） |
| `LevelDb_*` | `third_party/librime/deps/leveldb`（target `leveldb`） |
| `Marisa_*` | OpenCC 内建的 `deps/marisa-0.2.6`（target `marisa`） |
| `Opencc_*` | `deps/opencc`（target `libopencc`） |
| Boost | 仅头文件，`include_directories` 直接给路径 |
| Glog | **不需要** —— `ENABLE_LOGGING=OFF` 关掉了 glog 日志 |

三个必须注意的坑：

1. **marisa 只能引入一次**。OpenCC 会 `add_subdirectory(deps/marisa-0.2.6)`，
   而 librime 的 `deps/marisa-trie` 是同版本的另一份，两者 target 同名（`marisa`）。
   故**不**在外层引入 librime 那一份，直接复用 OpenCC 的。
2. **`BUILD_SHARED_LIBS` 必须写成 CACHE 变量**。子项目的 `option(BUILD_SHARED_LIBS ...)`
   在未设 CMP0077 时会清掉同名普通变量并改用默认值（OpenCC 的默认是 `ON`）→ 会编出动态库。
3. **OpenCC 的头文件布局与 librime 的包含形式不一致**。OpenCC 源码内部用相对包含
   （`#include "Common.hpp"`），而 librime 用 `<opencc/Config.hpp>`（期望"已安装"布局）。
   构建时按安装后布局把 OpenCC 头复制到垫片目录 `opencc-include/opencc/`，
   不改动第三方源码。

### ABI

`app` 模块只出 `arm64-v8a`（`app/build.gradle.kts` 的 `abiFilters`），
而 native 构建由 `imesdk` 驱动。原先 `imesdk` 声明了 4 个 ABI，会让 librime 及其
4 个依赖各编 4 遍却从不被打包 —— 已收敛为 `arm64-v8a`。

> 旧 SDK 声明 4 个 ABI 是为了随包分发预编译的 `libyuyanime.so`；该库在新工程已移除。

## 八、构建产物与验证（2026-09-19 实测，arm64-v8a）

`./gradlew :app:assembleOfflineDebug` → **BUILD SUCCESSFUL**

| 项 | 值 |
|---|---|
| 产物 | `lib/arm64-v8a/libqingyu_rime.so` |
| APK 内大小 | **2 425 128 字节**（约 2.4 MB，Deflate 后约 1.0 MB） |
| 动态依赖 | 仅 `liblog` / `libandroid` / `libm` / `libdl` / `libc` —— **不依赖 `libc++_shared`** |
| 导出符号 | **14 个** `Java_com_qingyu_inputmethod_core_RimeSession_native*`，无静态库符号泄漏 |

尺寸对照：旧闭源 `libyuyanime.so` 为 4.4 MB。我们更小的原因是关掉了 glog 日志
（`ENABLE_LOGGING=OFF`），并加了 `-fvisibility=hidden`、`-Wl,--gc-sections`、
`-Wl,--exclude-libs,ALL`。

### 构建类型固定为 RelWithDebInfo

AGP 会给 debug 变体传 `-DCMAKE_BUILD_TYPE=Debug`（即 `-O0`），而 librime 的主要工作是
上千万词条的表/棱镜查找，未优化版本在真机上慢到会让"验证主链路"失去意义。
CMakeLists 因此把构建类型固定成 `RelWithDebInfo`：拿到 `-O2 -DNDEBUG`，同时保留 `-g`
与帧指针以便用 ndk-stack 定位 native 崩溃。

### 顺带修复的一处既有缺陷：AGP 未能剥离 .so

构建日志里的警告：

```
Unable to strip library '.../libqingyu_rime.so'
        due to missing strip tool for ABI 'arm64-v8a'. Packaging it as is.
```

根因**不在本工程的新增代码**：`app/build.gradle.kts` 一直没声明 `ndkVersion`，
AGP 便用它内置的默认 NDK 版本去找 strip 工具，而本机只装了 `30.0.14904198`。

后果是**所有** .so 都以未剥离状态进包 —— 包括当时名为 `libhccr_jni.so` 的手写库（现已更名为 `libqingyu_hw_jni.so`）：

| 库 | 修复前进包 | 修复后进包 |
|---|---|---|
| `libhccr_jni.so`（手写，既有） | 15 573 168 | **9 990 560** |
| `libqingyu_rime.so`（本项目） | 63 236 000 | **2 425 128** |

修法是在 `app/build.gradle.kts` 补上 `ndkVersion = "30.0.14904198"`（与 `imesdk` 一致）。
仅此一项即让 APK 少约 63 MB。
