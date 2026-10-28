# 轻语输入法 SDK

`imesdk` 实现键盘、输入法服务、词库管理与原生引擎；`app` 提供应用入口与打包。当前 SDK 随本仓库应用共同开发，不作为已发布的独立二进制产品。

- 功能、截图与使用方式：[`../README.md`](../README.md)
- 开发环境与构建：[`../CONTRIBUTING.md`](../CONTRIBUTING.md)
- 原生依赖与迁移背景：[`../MIGRATION.md`](../MIGRATION.md)
- 许可与第三方组件：[`../LICENSING.md`](../LICENSING.md)

在仓库根目录执行 `.\gradlew.bat :app:assembleOfflineDebug` 构建调试 APK。语音输入需要 Android 12 及以上设备提供的本地识别服务，首次使用需要麦克风权限；设备不提供该服务时不可用，真机效果仍需验证。
