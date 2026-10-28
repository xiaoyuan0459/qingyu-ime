# 许可证与第三方内容

仓库根 [LICENSE](LICENSE) 是本项目 GPL-3.0-only 的许可依据。第三方组件与资产保留自己的版权和许可；发行包需要包含相关文本与对应源码材料。本清单记录工程事实与待办，不宣称全部发行审计已经完成。

整体走 GPL-3.0-only 的决策依据、上游 BSD-3 与整包许可的辨析，以及已清除的阻断项，见 [ADR 0004](docs/adr/0004-整体GPL-3.0-only开源路线与上游许可义务.md)。组件在轻语中的实际用途、设计参考边界和历史来源见 [CREDITS.md](CREDITS.md)；本清单只登记许可状态与待办。

## 已登记内容

| 内容 | 当前记录 | 来源 / 证据 |
| --- | --- | --- |
| Android 输入框架的上游代码 | YuyanIme，BSD-3-Clause；保留原作者版权 | [上游仓库](https://github.com/gurecn/YuyanIme)、应用内第三方声明 |
| librime 1.11.2 | BSD-3-Clause | [锁定提交](tools/native-deps.lock.json)、依赖源码 LICENSE；本地补丁见 [ADR 0007](docs/adr/0007-纠错切分权重.md) |
| glog、LevelDB | BSD-3-Clause | librime 固定子模块及其 LICENSE |
| marisa-trie | 双许可；项目登记 BSD-2-Clause 分支 | librime 子模块 COPYING / 许可证，发行前复核选择 |
| yaml-cpp / RapidJSON | MIT | 固定依赖源码的 LICENSE |
| OpenCC | Apache-2.0 | 固定 revision 的 LICENSE / AUTHORS 随 APK 归档；该 revision 无 NOTICE |
| Boost 1.87.0 | BSL-1.0 | [官方归档](https://archives.boost.io/release/1.87.0/source/)、锁定文件 |
| ncnn 20260526 | BSD-3-Clause | [固定发行版](https://github.com/Tencent/ncnn/releases/tag/20260526)、锁定文件 |
| Vosk Android 0.3.47 | Apache-2.0 | [`com.alphacephei:vosk-android`](https://github.com/alphacep/vosk-api)，Maven POM |
| JNA 5.13.0 | LGPL-2.1-or-later 或 Apache-2.0 | Vosk Android 的传递依赖，Maven POM |
| Vosk 中文 / 英文小模型 | Apache-2.0 | [Vosk models](https://alphacephei.com/vosk/models)；用户首次开启时下载，不随 APK 分发 |
| pinyin-data v0.15.0 | MIT | `imesdk/src/main/assets/pinyin_table.txt` 中的声明 |
| 默认雾凇词库 | GPL-3.0-only，固定 rime-ice revision | 内置 pack 的 SOURCE.md、LICENSE、manifest.json |
| 自有频率层与叠加层 | GPL-3.0-only（随项目整体） | `tools/gen-dict-freq.py`、`imesdk/rime-src/cn_dicts/qingyu_overlay.dict.yaml`；只重算权重与增补词条，不改变分表的词条来源与许可 |
| 笔画与词库基础数据 | rime-stroke / rime-essay 的 LGPL 声明 | pack 内许可证与 AUTHORS.rime-stroke |
| 可选白霜词库 | GPL-3.0-or-later，独立导入包 | 可选 pack 的清单和来源，不随 APK 内置 |
| 手写识别模型 qingyu-hw-image-v1 | 自有语料训练，随项目整体 GPL-3.0-only 分发 | 见下方「手写识别」小节与 ADR 0008 |
| 训练用 OFL 字体（8 个） | SIL Open Font License 1.1；字体不随包分发 | `tools/handwriting/fonts.json` 与 `fonts.lock.json` |
| 本仓库手写推理层 | GPL-3.0-only（随项目整体） | `qingyu_hw_jni.cc` 与 `QingyuHwRecognizer.java` |
| Kotlin、AndroidX、Material 等 JVM 依赖 | 按各组件声明登记 | Gradle 解析结果与应用内第三方声明 |

版本声明以锁定文件、Gradle 配置和 pack manifest 为准；不要把 README 或 [CREDITS.md](CREDITS.md) 中的来源说明当作许可证证明。

## 手写识别（自训练模型）

### 当前使用方式

手写识别使用本仓库自训练的 27,664 类单字分类器（`qingyu-hw-image-v1`），随包 int8 权重 8.05 MB。
训练语料**只由 OFL-1.1 字体离线渲染生成**，不含 CASIA-HWDB/OLHWDB 等不可再分发数据集；
HCCR（CASIA 派生权重）与 PP-OCRv6 分支已连同资产移除，决策记录见
[ADR 0008](docs/adr/0008-手写识别切换为自训练模型.md)。

| 项 | 内容 |
| --- | --- |
| 模型产物 | `imesdk/src/main/assets/handwriting/qingyu-hw-image-v1/`（`model.ncnn.param` / `model.ncnn.bin` / `charset.json` / `model-manifest.json`） |
| 权重许可 | 本仓库自有产物，随项目整体 GPL-3.0-only 分发 |
| 类别 | 27,664（27,584 汉字 + 80 标点/ASCII），顺序即 `charset.json` 的 `classes` |
| 产物校验 | `model-manifest.json` 记录类别数、`classes_sha256` 与各文件 SHA-256；App 启动时强校验 |
| 训练字体 | Ma Shan Zheng、Zhi Mang Xing、Long Cang、Liu Jian Mao Cao、LXGW WenKai、Klee One、Noto Sans SC、Noto Serif SC，均为 SIL Open Font License 1.1 |
| 字体分发 | **字体文件不随 APK 或源码分发**，仅用于离线生成训练图像；来源与 SHA-256 见 `tools/handwriting/fonts.lock.json` |
| 复现方式 | `tools/handwriting/` 下的 fetch-fonts → build-charset → render-dataset → train → export-model → quantize-int8 → publish-model，固定随机种子 |

推理层 `qingyu_hw_jni.cc` / `QingyuHwRecognizer.java` / `preprocess.c` 为本仓库自研代码，
随项目整体 GPL-3.0-only 分发。

### OFL 合规说明

SIL OFL 1.1 允许将字体用于任何用途，包括生成训练数据；其再分发义务（保留许可证文本与保留字体名）
只在本项目实际分发字体文件时触发。本仓库不分发字体文件，只在构建期下载到 git-ignored 的
`tools/handwriting/.cache/`，因此 APK 与源码分发均无 OFL 文本义务。若将来改为随包分发字体子集，
必须补齐 OFL 全文与保留字体名声明。


模型文件仅随 assets 分发，不含训练数据；训练数据来源、权重再分发条件与最终许可结论仍按 DEBT-LICENSE-01 核验。

## 语音识别（Vosk）

| 项 | 内容 |
| --- | --- |
| 上游项目 | [alphacep/vosk-api](https://github.com/alphacep/vosk-api)，`com.alphacephei:vosk-android` 0.3.47 |
| 许可 | Apache-2.0 |
| 中文模型 | `vosk-model-small-cn-0.22`，下载约 42 MB，解压约 60 MB，Apache-2.0 |
| 英文模型 | `vosk-model-small-en-us-0.15`，下载约 40 MB，解压约 58 MB，Apache-2.0 |
| 模型来源 | [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) |
| 分发方式 | 不随 APK；用户首次开启“离线语音”时下载到应用私有目录，可在设置中删除 |
| 传递依赖 | JNA 5.13.0，LGPL-2.1-or-later 或 Apache-2.0 |

识别本身在设备本地完成；模型下载是唯一由该功能发起的网络请求。

## 仍需核验的内容

见 [DEBT-LICENSE-01](docs/TECHNICAL_DEBT.md#debt-license-01)：

- RichTap 的代码依赖和 AAR 已从构建与源码交付树移除，触感由 Android 公共 API 实现；本机原始 AAR 留在忽略的临时归档中，不随提交或 APK 分发。
- 内嵌 cropper / expression / recyclerview 的版权头与上游来源需要逐项恢复。
- 键盘图标、正式品牌素材及历史产品截图需要可追溯的来源记录。
- 原生许可全文已在 `imesdk/src/main/assets/licenses/native/` 归档并供应用内查看；JVM 完整版权、最终发行 SBOM 与对应源码交付仍需统一整理。CI 导出的运行时依赖清单包含传递依赖、精确版本和 SHA-256，不等同于已完成的许可审计。

设计草稿、其他输入法参考截图和本机调查日志不属于公开源码交付物，留在本地。正式构建需要的代码和品牌源文件不能用忽略规则掩盖。

## 贡献与发行

新增第三方内容须记录 URL、精确 revision / 版本、文件哈希、用途、分发方式、SPDX 与许可文本，并保留必要的修改说明。未知授权的组件不得因“已在本机可运行”而自动视为可公开发行。

本项目不更改第三方许可证，不移除原作者声明，也不以重命名或重写接口名称作为消除归属义务的依据。源码、词库和 APK 的发行步骤见[发布流程](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Release)。

应用内声明在 `imesdk/src/main/res/raw/third_party_notices.txt`；发布前须与实际依赖和随包资产一起核对。
