# 文档索引

仓库只保留构建、许可和决策必需的材料。架构、性能、交互与测试等长文档在 [Wiki](https://github.com/xiaoyuan0459/qingyu-ime/wiki) 维护。

## 仓库内

| 面向 | 文档 |
| --- | --- |
| 首次访问 | [项目介绍](../README.md)、[English README](../README.en.md)、[路线图](../ROADMAP.md) |
| 贡献者 | [贡献指南](../CONTRIBUTING.md)、[开发环境与构建](DEVELOPMENT.md) |
| 工程维护 | [技术债清单](TECHNICAL_DEBT.md)、[架构决策记录](adr/) |
| 许可与来源 | [来源、使用与致谢](../CREDITS.md)、[许可证清单](../LICENSING.md)、[迁移说明](../MIGRATION.md) |
| 数据与隐私 | [隐私与数据清单](../imesdk/docs/隐私与数据清单.md)、[安全策略](../SECURITY.md) |
| 接口与规格 | [自研 native 层接口契约](../imesdk/docs/自研native层接口契约.md)、[联想功能规格](../imesdk/docs/联想功能规格与实现方案.md) |
| 素材 | [品牌素材](brand/README.md) |

## Wiki

| 页面 | 内容 |
| --- | --- |
| [Architecture](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Architecture) | 模块边界、依赖方向、输入数据流 |
| [Input-Pipeline](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Input-Pipeline) | 按键到上屏的状态与线程模型 |
| [Build](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Build) | 工具链、原生依赖、产物与 CI |
| [Performance](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Performance) | 每键耗时、帧耗时、内存构成与优化清单 |
| [Data-and-Privacy](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Data-and-Privacy) | 词库包、用户数据、备份事务、权限边界 |
| [Handwriting](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Handwriting) | 手写模型、训练流水线、量化与精度 |
| [UI-and-Interaction](https://github.com/xiaoyuan0459/qingyu-ime/wiki/UI-and-Interaction) | 界面层级、状态语义、交互约束 |
| [Feature-Coverage](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Feature-Coverage) | 功能清单、自检结论、已知局限 |
| [Testing](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Testing) | 自动化命令、设备场景、证据格式 |
| [Release](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Release) | 发行验收、签名、归属材料 |

文档使用仓库相对路径或稳定 wiki 链接，并说明结论适用的版本与验证范围。架构决定进入 ADR，待完成工作进入技术债清单；本机日志和单次测试产物只留本地，可在 PR 中附脱敏证据。
