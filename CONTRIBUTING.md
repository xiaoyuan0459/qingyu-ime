# 参与轻语输入法

项目处于 Pre-Alpha。欢迎修复缺陷、补充真机验证、改善构建和文档、核对第三方归属。功能状态以 [路线图](ROADMAP.md) 为准，工程缺口在 [技术债清单](docs/TECHNICAL_DEBT.md) 中跟踪。

## 开始贡献

1. 阅读 [开发指南](docs/DEVELOPMENT.md)，准备依赖并构建 debug APK。
2. 普通修复可直接提交 PR；新功能、架构、权限、用户数据或词库包格式变化先通过 issue 对齐范围。
3. 阅读[项目架构](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Architecture)与[界面与交互原则](https://github.com/xiaoyuan0459/qingyu-ime/wiki/UI-and-Interaction)，避免把本机设置和临时素材带入提交。
4. 安全问题按 [SECURITY.md](SECURITY.md) 私下报告，讨论遵守 [行为准则](CODE_OF_CONDUCT.md)。

## 工程边界

`app` 是应用壳；`imesdk` 负责输入法 UI、输入编排、数据和原生引擎。依赖从应用壳指向 SDK。详见[架构说明](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Architecture)。

把改动限制在一个可以评审的主题内。保留输入状态和持久化格式的兼容性；异步工作处理取消、超时与生命周期；输入内容、剪贴板、语音结果和用户词库不能进入日志、测试夹具或截图。

新增第三方内容时提供来源 URL、精确版本 / revision、用途、分发方式、SPDX 与许可证文本，并同步更新[来源与使用清单](CREDITS.md)和 [LICENSING.md](LICENSING.md)。仅作界面参考时写明参考产品、版本与未复制的内容。不得提交来源不明的代码、模型、数据、字体、图片或二进制。

## 验证改动

```powershell
.\gradlew.bat :app:assembleOfflineDebug :imesdk:testOfflineDebugUnitTest
```

验证范围应与风险相称。对行为修复优先在真实业务边界补回归测试；纯文档或简单布局改动不要求机械添加测试。无法自动化时，在 PR 中写明设备、系统版本、操作步骤和实际观察。

- 输入链路：组合、候选、上屏、退格、切方案和服务恢复。
- 键盘布局：横竖屏、手势 / 三键导航、不同密度、单手和悬浮模式。
- 数据与词库：旧版本升级、失败回滚、损坏输入、空间不足和中断恢复。
- 权限与依赖：同步核对隐私说明、归属材料和 release 产物。

实际命令和证据格式见[测试与验收](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Testing)。

## 提交与评审

提交标题简述问题或结果，如 `fix(keyboard): respect navigation bar insets`。每个提交使用 DCO 签名：

```sh
git commit -s -m "fix(keyboard): respect navigation bar insets"
```

`Signed-off-by` 表示你确认有权贡献这些内容，并按本项目许可证提交。PR 使用仓库模板，至少说明问题、改动后的行为、验证结果和必要的兼容 / 回滚说明。

提交前检查 `git diff --check` 和 `git status --short`。人工测试截图和构建日志可附在 PR，先清除真实输入、设备标识和个人路径。不要提交签名密钥、token、本机代理、下载缓存或设计草稿。

维护者优先评估输入正确性、用户数据与隐私、许可、回归风险和维护成本。PR 合入与正式发布是两个独立步骤；发行条件见[发布流程](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Release)。
