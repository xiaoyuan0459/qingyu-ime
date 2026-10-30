# 来源、使用与致谢

本文把两件事分开写清楚：

- **实际使用**：代码、数据、模型或词库由轻语运行或随发行包分发，包括按需下载的模型。
- **设计参考**：只观察产品的界面、布局与交互，用于形成轻语自己的实现，不复制对方的代码或素材。

精确版本、哈希、许可证全文和未完成核验以 [LICENSING.md](LICENSING.md) 为准；本文不替代许可证，也不构成法律意见。

## 一、实际使用

### 1. 输入框架与引擎

| 项目 | 轻语中的用途 | 固定来源 | 许可 |
| --- | --- | --- | --- |
| YuyanIme | Android 输入法框架、键盘视图和设置结构的起点；本仓库在此基础上重构 | [gurecn/YuyanIme](https://github.com/gurecn/YuyanIme)，Copyright (c) 2026 王莹 | BSD-3-Clause |
| librime 1.11.2 | Rime 输入引擎，自行编译并带本地补丁 | [rime/librime](https://github.com/rime/librime) `5b09f35b…` | BSD-3-Clause |
| OpenCC | 仅 emoji 转换；不参与简繁改写 | [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC) `e5d6c5f1…` | Apache-2.0 |
| ncnn 20260526 | 手写识别推理运行时 | [Tencent/ncnn](https://github.com/Tencent/ncnn/releases/tag/20260526) | BSD-3-Clause |
| Vosk Android 0.3.47 | 本地离线语音识别 | [alphacep/vosk-api](https://github.com/alphacep/vosk-api)，`com.alphacephei:vosk-android` | Apache-2.0 |
| JNA 5.13.0 | Vosk Android 的传递依赖 | [java-native-access/jna](https://github.com/java-native-access/jna) | LGPL-2.1-or-later 或 Apache-2.0 |
| AndroidX、Material、Kotlin、coroutines、Room 等 | Android 与 JVM 运行时、界面和数据组件 | Gradle 依赖，完整版本见应用内第三方声明 | Apache-2.0 等，逐项见声明 |

原生依赖的 revision、SHA-256 与许可证全文见 `tools/native-deps.lock.json` 和 `imesdk/src/main/assets/licenses/native/manifest.json`；JVM 依赖完整清单见 `imesdk/src/main/res/raw/third_party_notices.txt` 与 CI 导出的依赖清单。

### 2. 词库、数据与模型

| 项目 | 轻语中的用途 | 固定来源 | 许可 |
| --- | --- | --- | --- |
| 雾凇拼音（rime-ice） | 可选独立词库、白霜上游来源 | revision `9e66b07…`，[iDvel/rime-ice](https://github.com/iDvel/rime-ice) | GPL-3.0-only |
| rime-essay | 默认词库的基础数据 | revision `e2652ea…`，[rime/rime-essay](https://github.com/rime/rime-essay) | LGPL-3.0 |
| rime-stroke | 五笔画词库；含 CNS11643 与北大中文论坛归属 | revision `1e8fff9b…`，[rime/rime-stroke](https://github.com/rime/rime-stroke) | LGPL-3.0-or-later |
| pinyin-data v0.15.0 | 汉字拼音表 `pinyin_table.txt` | [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data) | MIT |
| 白霜（rime-frost） | 内置默认词库与只读联想索引，保留上游词频 | revision `96278d8`，[gaboolic/rime-frost](https://github.com/gaboolic/rime-frost) | GPL-3.0-or-later |
| 手写识别模型 qingyu-hw-image-v1 | 默认手写识别，27,664 类 int8 权重，自有语料训练 | 本仓库 `tools/handwriting/` 流水线 | GPL-3.0-only（随项目整体） |
| OFL 训练字体 × 8 | 仅构建期渲染训练图像，不随包分发 | Ma Shan Zheng / Zhi Mang Xing / Long Cang / Liu Jian Mao Cao / LXGW WenKai / Klee One / Noto Sans SC / Noto Serif SC | SIL OFL 1.1 |
| Vosk 中文小模型 `vosk-model-small-cn-0.22` | 可选离线语音识别，首次开启时下载 | [Vosk models](https://alphacephei.com/vosk/models) | Apache-2.0 |
| Vosk 英文小模型 `vosk-model-small-en-us-0.15` | 可选离线语音识别，首次开启时下载 | [Vosk models](https://alphacephei.com/vosk/models) | Apache-2.0 |
| pnnx 20260526 | 将自训练模型 ONNX 转为 ncnn | [ncnn/pnnx](https://github.com/Tencent/ncnn/tree/master/tools/pnnx) | BSD-3-Clause |
| GoogleTest | 仅构建期测试，不进入发行包 | 随 librime 构建 | BSD-3-Clause |

默认词库包的 `SOURCE.md`、`LICENSE`、`manifest.json` 记录来源、revision 和逐文件哈希；手写模型的类别数、字表哈希与产物 SHA-256 见模型资产的 `model-manifest.json` 与 [LICENSING.md](LICENSING.md)。

### 3. 语音与系统能力

- 语音输入优先使用本地 Vosk 小模型；模型不随 APK 分发，用户首次开启“离线语音”时从 alphacephei.com 下载，可删除。
- 未启用离线语音时，回退到 Android 12+ 设备提供的系统端侧识别服务；已启用但模型缺失时提示下载，不采集音频。厂商服务行为不受本应用控制。
- 剪贴板分词使用 Android 系统 `BreakIterator`，不引入第三方分词库。

## 二、设计参考（未复制代码与素材）

| 参考对象 | 版本 / 范围 | 用途 | 边界 |
| --- | --- | --- | --- |
| 魅族搜狗输入法 | 10.32.39.202412161509 / versionCode 1018 | 候选、工具箱、剪贴板、符号 / 表情面板、设置、文本编辑、九键分词与手写侧栏的行为和几何对照 | 只观察与测量；未复制代码、图片、图标、皮肤、词库、模型或文案；参考 APK 与截图只留本地，不进入构建或发行包 |

具体结论和验证范围见[界面与交互原则](https://github.com/xiaoyuan0459/qingyu-ime/wiki/UI-and-Interaction)、[功能覆盖与自检](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Feature-Coverage)和[性能与内存](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Performance)。单次测试截图、设备日志和参考素材只留本地，不作为公开文档的许可证依据。

轻语与上述参考产品没有隶属、授权或背书关系；产品名称和界面归各自权利人所有。

## 三、历史来源与已移除内容

| 内容 | 状态 | 说明 |
| --- | --- | --- |
| 搜狗手写 SDK 及其模型 / 授权文件 | 已移除 | 旧 `libhandwriting.so`、`libSogouShell.so`、`libgpen_handwriter.so`、`libhwInterface.so` 与 `assets/hw/` 不再随源码或 APK 分发 |
| 原闭源 `libyuyanime.so` | 已移除 | 不再是构建或运行依赖，引擎改为本仓库自行编译的 librime |
| 同文 Trime 的旧 JNI 绑定 | 已重写 | 原 `Rime.kt` 的来源与许可存在不确定性；现改为按语义重写的 `RimeSession` / `EngineTypes`，不再复制旧绑定 |
| RichTap SDK | 已移除 | 触感改用 Android 公共 API；本机 AAR 仅在忽略的临时归档中，不随提交或 APK 分发 |
| pinyin4j / `ResourceHelper.java` | 已移除 | 曾以 GPL 源码拷入；现改用 MIT 的 pinyin-data 与自研 `HanziPinyin` |

历史细节与处置依据见 [MIGRATION.md](MIGRATION.md)、[ADR 0004](docs/adr/0004-整体GPL-3.0-only开源路线与上游许可义务.md) 和 [LICENSING.md](LICENSING.md)。

原生繁体资料来自 [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cedict)，快照 2026-10-28，原始文本 SHA-256 `714c3e7ea6086956f163eb9387d627859e5c00c0e51725197b7a0c670cc2641a`，CC-BY-SA-4.0。词形与读音索引、白霜词频连接和来源标注的异体排序适配层使用 GPL-3.0-only（[CC 官方单向兼容说明](https://creativecommons.org/compatible-licenses/)）；原始归属、许可及修改说明随 APK 与 pack 分发。

## 四、尚未完成归属的内容

以下内容仍在 [DEBT-LICENSE-01](docs/TECHNICAL_DEBT.md#debt-license-01) 跟踪，本文不把它们写成已确认：

- `imesdk/src/main/java/com/qingyu/imemodule/libs/cropper`、`expression`、`recyclerview` 三个移植目录的上游来源与版权头。
- 键盘图标资源及正式品牌素材的逐项来源记录。
- 真人手写基准集尚未建立，当前手写模型只在「未见过的字体」上验证过，未在真人笔迹上验证。
- JVM 依赖的完整版权归档、最终发行 SBOM 与对应源码交付。

## 五、维护规则

新增代码、数据、模型、字体或图片时，在 PR 中同时更新本文与 [LICENSING.md](LICENSING.md)，记录来源 URL、精确版本 / revision、用途、分发方式、SPDX、许可证文本和必要修改。仅作界面参考时，写明参考产品、版本、日期与未复制的内容，不提交其截图、素材或反编译产物。
