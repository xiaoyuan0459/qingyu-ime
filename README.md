<div align="center">

<img src="docs/brand/qingyu-icon-master.png" width="96" alt="轻语输入法图标" />

# 轻语输入法

**本地优先的 Android 中文输入法 · 基于 Rime**

九宫格 · 全拼 · 双拼 · 乱序 17 · 五笔画 · 手写 · 离线语音

[简体中文](README.md) · [English](README.en.md)

[![Build](https://github.com/xiaoyuan0459/qingyu-ime/actions/workflows/build.yml/badge.svg)](https://github.com/xiaoyuan0459/qingyu-ime/actions/workflows/build.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

</div>

> **状态：Pre-Alpha（`0.1.1-dev`）。** 输入链路已在模拟器上完整跑通，arm64 真机与厂商 ROM 覆盖仍在补。尚无推荐日常使用的正式版本；想先试用可以直接下 [v0.1.1-dev 预览包](https://github.com/xiaoyuan0459/qingyu-ime/releases)（debug 签名）。进度见[路线图](ROADMAP.md)与[技术债清单](docs/TECHNICAL_DEBT.md)。

---

## 为什么是轻语

- **输入不出本机。** 没有云输入、账号、同步、广告和统计 SDK。候选、词库、手写推理全部在设备上完成，核心输入链路不需要网络。
- **手写覆盖到生僻字。** 自训练闭集分类模型，**27,664 个字符类**，int8 权重 8.4 MB，ncnn 本地推理；未见书写风格测试集 top-1 88.8%、top-5 97.1%。
- **词库可换、可回滚。** 内置固定 revision 的雾凇拼音；支持导入 Rime 词库包，安装前做清单、路径、大小与 SHA-256 校验，原子切换，失败保留上一版本。
- **方案齐全。** 九宫格、全拼、小鹤 / 自然码 / ABC / 微软 / 搜狗 / 紫光双拼、乱序 17、五笔画、英文、中英混输。
- **权限只有 5 条。** 核心链路只用 `VIBRATE`、`RECORD_AUDIO`、`POST_NOTIFICATIONS`；`INTERNET` / `ACCESS_NETWORK_STATE` 只服务于用户主动开启的离线语音下载与手写样本上传。
- **源码可审计。** GPL-3.0-only，原生引擎自行编译，依赖版本、提交与哈希全部锁定，不依赖预编译二进制。
- **输入链路可自检。** 调试包内置 15 段引擎自检（打字、上屏、翻页、简繁、五笔画、乱序 17、中英混输、联想、换包回滚），把「候选对不对」变成可重复的断言，而不是靠肉眼点。

## 功能

| 能力 | 当前实现 |
| --- | --- |
| 中文输入 | 九宫格、全键拼音、小鹤 / 自然码 / ABC / 微软 / 搜狗 / 紫光双拼、乱序 17、五笔画、简繁切换 |
| 词库 | 内置雾凇拼音（固定 revision）；本地导入 Rime 词库包，校验、原子安装、失败回滚；可选白霜词库独立分发 |
| 英文与符号 | 全键英文、数字行、符号、Emoji、颜文字、花漾字、符号配对 |
| 手写 | 自训练单字模型，27,664 类、int8 8.4 MB，全本地推理；训练语料仅 OFL 字体合成，不含 CASIA 派生数据 |
| 语音 | 长按空格；优先本地 Vosk 小模型，未启用离线语音时回退 Android 12+ 系统端侧识别 |
| 键盘 | 深色主题、自定义主题、尺寸调节、单手、悬浮、工具栏自定义与排序 |
| 输入辅助 | 剪贴板历史、常用语、中文联想、文本编辑、侧栏符号、用户数据备份与恢复 |
| 数据与隐私 | 无云输入；剪贴板与手写样本有独立开关，详见[隐私与数据清单](imesdk/docs/隐私与数据清单.md) |

## 技术栈

| 层次 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Kotlin 2.2.20 | 307 个文件、约 38,400 行；无 Compose，使用 View 体系与 XML / Splitties DSL |
| 输入引擎 | librime 1.11.2（自编译） | 固定 Git 提交与递归子模块，带本地补丁；通过 JNI 暴露 `RimeSession` / `RimeEngine` |
| 手写推理 | ncnn 20260526 + 自训练模型 | 64×64 单通道输入，27,664 类输出，int8 量化；`pnnx` 完成 ONNX → ncnn 转换 |
| 语音识别 | Vosk Android 0.3.47 | 中文 / 英文小模型首次开启时按需下载，识别在本机完成 |
| 词库与转换 | 雾凇拼音、rime-stroke、pinyin-data、OpenCC | 词库包由 `tools/build-rime-pack.py` 从锁定上游编译 |
| 应用框架 | AndroidX、Material 1.12、Room 2.6.1、Coroutines 1.9、kotlinx.serialization | 数据、异步与设置持久化 |
| 构建 | AGP 9.4.0 / Gradle 9.6.0 / JDK 21 / NDK 30 / CMake 3.22.1 | 原生依赖由 `tools/bootstrap-native-deps.py` 按锁定清单准备 |
| 平台 | minSdk 23，compile / target SDK 37 | debug 含 arm64-v8a + x86_64；release 仅 arm64-v8a |
| CI | GitHub Actions | 构建双 ABI、lint、依赖与许可校验，导出依赖 SHA-256 清单 |

原生侧只有约 1,440 行 C/C++（`imesdk/src/main/cpp`），负责 JNI 桥接、笔迹预处理与 librime 会话。不维护上游 fork 分支；个别行为修改（如纠错切分权重）以补丁脚本在构建期重放。工程分层、输入数据流与线程模型见 [wiki：架构](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Architecture)与 [wiki：输入链路](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Input-Pipeline)。

## 性能与内存

以下数据来自 **Android 15（API 35）x86_64 模拟器、debug 包、软件渲染**，采集脚本是 `tools/measure-ime-memory.sh` 与 `dumpsys gfxinfo` / `dumpsys meminfo`，完整方法、改动清单与未验证项见 [wiki：性能与内存](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Performance)。它们用于判断优化方向和回归趋势，**不是真机承诺**：arm64 真机基线仍属 [DEBT-DEVICE-01](docs/TECHNICAL_DEBT.md)，release 包也未单独测量。

| 指标 | 优化后 | 优化前 | 口径 |
| --- | --- | --- | --- |
| 每键处理耗时（均值） | **10.2 ms** | 12.7 ms | 按键事件进入 → 候选 / 气泡 / 候选栏同步刷新完成 |
| 帧耗时 P50 | **20 ms** | 31 ms | `dumpsys gfxinfo`，连续输入 |
| 帧耗时 P90 | **48 ms** | 53 ms | 同上 |
| 稳定态内存 PSS | **≈100 MB** | 104.6 MB | `dumpsys meminfo`，打开设置页后的稳定态 |
| Native Heap PSS | **21.9 MB** | 25.0 MB | librime + ncnn 分配 |
| 冷启动首屏 PSS | ≈78 MB | — | 引擎未加载词库前 |

内存构成（稳定态，debug）：Native Heap 约 22 MB、词库 mmap 约 16.6 MB、librime 匿名页约 17 MB、Dalvik + ART 约 20 MB。占用大头是**词库表文件的 mmap** 与 **librime 内部词典结构**，不是 UI；手写模型已改为懒加载并在 `onTrimMemory` 释放，不再常驻。这个量级对输入法偏高，压它的下一步是「只映射当前启用方案」与减少 librime 内部碎片，两项都还没做。

安装包与资产：

| 项目 | 体积 | 说明 |
| --- | --- | --- |
| debug APK | 74 MB | 含 arm64-v8a 与 x86_64，x86_64 未 strip |
| 预发布包 `v0.1.1-dev` | 74 MB | 同一个 debug 包，附在 GitHub Release，带 `SHA256SUMS.txt` |
| release APK | 未发布 | 仅 arm64-v8a，体积显著小于 debug |
| 内置词库包 `rime-base.zip` | 36 MB | 解包约 83 MB，保留一层回滚版本 |
| 手写模型 int8 | 8.4 MB | 27,664 类，随包分发 |
| 离线语音模型 | 约 40–42 MB | 不随包，首次开启时下载 |

输入热路径的优化集中在「每键都会重复执行、且结果不变」的操作：候选栏形态缓存、候选列表差量刷新、键帽背景复用与脏区重绘、九宫格侧栏 Adapter 复用、注音气泡宽度去重、每键只向引擎取一次上下文。全部改动不改变候选顺序与输入语义。

## 安装

不想自己编译，可以从 [Releases](https://github.com/xiaoyuan0459/qingyu-ime/releases) 下载 `app-offline-debug.apk`（预发布包，debug 签名，校验值见同页 `SHA256SUMS.txt`）。

自己构建的产物：

```sh
adb install -r app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

在系统中启用：

1. 设置 → 系统 → 语言和输入法 → 屏幕键盘 → 管理屏幕键盘，启用「轻语输入法」。
2. 选择「轻语输入法」为当前输入法。
3. 模拟器连接物理键盘时，在轻语设置中开启「物理键盘显示软键盘」。

调试包名：`com.qingyu.inputmethod.offline.debug`。

## 从源码构建

需要 JDK 21、Python 3.11+、Git、Android SDK、NDK 30.0.14904198 与 CMake 3.22.1+。完整版本矩阵见[开发指南](docs/DEVELOPMENT.md)。

首次构建先准备固定版本的原生依赖（需要联网）：

```sh
python tools/bootstrap-native-deps.py
python tools/bootstrap-native-deps.py --check
```

Windows：

```powershell
.\gradlew.bat :app:assembleOfflineDebug
```

Linux / macOS：

```sh
./gradlew :app:assembleOfflineDebug
```

输出：`app/build/outputs/apk/offline/debug/app-offline-debug.apk`。

验证：

```powershell
.\gradlew.bat :imesdk:testOfflineDebugUnitTest :app:lintOfflineDebug
```

工具链细节、原生编译坑与签名见 [wiki：构建](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Build)；设备用例与证据格式见 [wiki：测试与验收](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Testing)。

## 权限与数据

核心输入链路不联网，不提供云输入、账号、同步、广告或遥测。Manifest 声明的网络权限只服务于用户主动开启的可选能力。

| 权限 | 用途 |
| --- | --- |
| `VIBRATE` | 按键触感 |
| `RECORD_AUDIO` | 语音输入；首次长按时申请 |
| `POST_NOTIFICATIONS` | 未完成首启设置时的提醒；未授权则跳过 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 下载 Vosk 离线语音小模型；在构建者配置上传地址且用户明确同意后上传手写样本 |

- **语音**：开启「离线语音」后从 alphacephei.com 下载约 40–42 MB 的 Vosk 小模型；识别在本机完成，音频与识别文本不落盘。未启用时回退系统端侧识别；已启用但模型缺失时提示下载，不采集音频。
- **手写**：默认模型随包本地推理；保存样本默认关闭，只在明确选字时写入本机；自动上传默认关闭，需单独同意，默认仅 Wi-Fi，上传笔迹、选中的字与日期，不含账号、设备标识、其他输入或截图。
- **剪贴板**：历史默认开启，保存最近 50 条文本，可在设置中关闭。这是本应用收集内容最多的一项，隐私政策里单独写明，也提供一键清空。

完整字段、存储位置与保留策略见[隐私与数据清单](imesdk/docs/隐私与数据清单.md)，对照版见 [wiki：数据与隐私](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Data-and-Privacy)。

## 已知限制

开源不等于完成。以下是当前明确未做完的部分，详细验收条件见[技术债清单](docs/TECHNICAL_DEBT.md)：

- **真机覆盖不足**：绝大多数结论来自 x86_64 模拟器，arm64 真机与厂商 ROM 矩阵仍是发行阻断项。
- **release 未发布**：只出 debug 包；签名、SBOM 与最终许可归档尚未收口。
- **联想首次有延迟**：某个末字首次联想约 250–400 ms（26 次前缀探测），命中缓存后无感；多音字按第一读音查。
- **笔画键盘第 6 键（通配）无效果**：librime 没有通配能力，待定用途。
- **单笔画易混**：模型会把一根横线首选识别成 `…`，「一」排第二，属训练分布问题。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `app/` | 应用壳、Manifest、打包与签名配置 |
| `imesdk/` | 输入法服务、键盘 UI、输入链路、数据与原生引擎 |
| `imesdk/src/main/cpp/` | JNI 桥接、笔迹预处理、librime 会话 |
| `imesdk/src/main/assets/` | 词库包、手写模型、OpenCC 数据与许可证材料 |
| `tools/` | 原生依赖准备、词库打包、模型转换与验证脚本 |
| `server/handwriting-upload/` | 可选的手写样本上传中转；不参与 APK 构建 |
| `docs/` | 开发指南、ADR、技术债与品牌素材 |

模块依赖单向：`app` → `imesdk`。输入法 UI、输入编排、数据与引擎都在 `imesdk` 内，应用壳只负责打包与入口。

## 开发方式

这个项目的实现阶段以 **AI 编程助手为主力编码工具**：OpenAI Codex、Claude 系列模型、Qoder、WorkBuddy 等参与了代码编写、重构、测试补全与文档整理。维护者负责产品定义、架构取舍、交互设计、代码评审与真机验收；每个提交都经过人工复核后才进入 `master`。

AI 生成不改变任何授权条件：项目仍为 GPL-3.0-only，第三方代码、词库、模型与图像的归属声明照常适用。我们把它写在这里，是因为读者有权知道代码是怎么来的，也因为这确实是当前效率的来源之一。

工程约束、模块边界与验证要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 来源与许可

本项目采用 [GPL-3.0-only](LICENSE)。第三方代码、词库、模型和图像保留各自许可证，根许可证不能替代其归属声明。

**实际使用**：

| 项目 | 用途 | 许可 |
| --- | --- | --- |
| [YuyanIme](https://github.com/gurecn/YuyanIme) | Android 输入法框架、键盘视图与设置结构的起点，本仓库在此基础上重构 | BSD-3-Clause |
| [librime](https://github.com/rime/librime) 1.11.2 | Rime 输入引擎，自行编译 | BSD-3-Clause |
| [OpenCC](https://github.com/BYVoid/OpenCC) | 简繁、Emoji 转换数据 | Apache-2.0 |
| [雾凇拼音 rime-ice](https://github.com/iDvel/rime-ice) | 内置默认词库 | GPL-3.0-only |
| [rime-stroke](https://github.com/rime/rime-stroke) | 五笔画词库 | LGPL-3.0-or-later |
| [pinyin-data](https://github.com/mozillazg/pinyin-data) | 汉字拼音表 | MIT |
| [ncnn](https://github.com/Tencent/ncnn) | 手写识别推理运行时 | BSD-3-Clause |
| [Vosk](https://github.com/alphacep/vosk-api) | 本地离线语音识别 | Apache-2.0 |
| [rime-frost](https://github.com/gaboolic/rime-frost) | 可选白霜词库，独立分发 | GPL-3.0-or-later |

**参考过的输入法**（只观察行为与几何，不复制代码、图片、图标、皮肤、词库、模型或文案）：

| 参考对象 | 参考内容 | 边界 |
| --- | --- | --- |
| 魅族版搜狗输入法 10.32.39.202412161509 | 候选栏形态、工具箱与剪贴板布局、符号 / 表情面板、设置层级、文本编辑、九键分词与手写侧栏的行为和几何对照 | 逐项用自己的实现重写；参考 APK 与截图只留本地，不随源码或 APK 分发 |
| [YuyanIme](https://github.com/gurecn/YuyanIme)（上游为轻语提供起点） | Android 输入法框架、键盘视图与设置结构的整体骨架 | 按 BSD-3-Clause 保留归属；原生层、输入链路与手写部分已重写 |
| 同文 Trime | 早期 JNI 绑定的方法划分方式 | 绑定层已按语义重写为 `RimeSession`，不再复制旧绑定，也不再保留其命名 |

**明确不参考**：不采用云输入、联想词云、账号体系与广告样式；不引入需要联网才有完整能力的输入路径。

完整的「实际使用 / 设计参考 / 历史与已移除 / 尚未确认」清单见 [CREDITS.md](CREDITS.md)，许可状态与待核验项见 [LICENSING.md](LICENSING.md)。

## 参与贡献

阅读[贡献指南](CONTRIBUTING.md)，通过 [Issues](https://github.com/xiaoyuan0459/qingyu-ime/issues) 报告问题或讨论方案。漏洞请按[安全策略](SECURITY.md)私下报告；讨论遵守[行为准则](CODE_OF_CONDUCT.md)。

- [技术文档 wiki](https://github.com/xiaoyuan0459/qingyu-ime/wiki) — 架构、输入链路、构建、性能与内存、数据与隐私、手写模型、界面语义、功能覆盖、测试与验收、发行
- [开发指南](docs/DEVELOPMENT.md) — 工具链、原生依赖、构建与签名
- [架构决策记录](docs/adr/) — 关键取舍的背景与后果
- [技术债清单](docs/TECHNICAL_DEBT.md) — 已知缺口与发行阻断项
