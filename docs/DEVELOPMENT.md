# 开发环境与构建

所有命令从仓库根目录执行。`imesdk` 当前与应用共同开发，不作为独立发布的 SDK；RichTap 本地 AAR 已移除。

## 工具链

| 工具 | 项目版本 |
| --- | --- |
| JDK / 字节码目标 | 21 |
| Gradle Wrapper / Android Gradle Plugin | 9.6.0 / 9.4.0 |
| Kotlin 编译器插件 / KSP | 2.2.20 / 2.3.12；使用 AGP 内置 Kotlin 与新 DSL |
| Android SDK | compile / target 37，min 23 |
| Android NDK | 30.0.14904198 |
| CMake | 3.22.1，Gradle 允许兼容的更高版本 |
| Python | 3.11+；原生补丁与依赖准备使用标准库 |
| Git | 支持子模块；librime 的依赖由固定子模块提交控制 |

在 Android Studio SDK Manager 或 `sdkmanager` 中安装所需 SDK、NDK 与 CMake。`local.properties` 保存本机 SDK 位置，或使用 `ANDROID_HOME` / `ANDROID_SDK_ROOT`；它们不入库。代理放在个人 Gradle / Git 设置或进程环境，不写入项目配置。

API 37 的部分 SDK 分发使用 `android-37.0` 目录名。若 AGP 仍查找 `android-37`，确认实际安装的平台包后，在个人 SDK 目录建立相应别名；不要改写工程的 compileSdk 来绕过平台缺失。CI 对此差异做条件处理。

## 原生依赖

```sh
python tools/bootstrap-native-deps.py
python tools/bootstrap-native-deps.py --check
```

脚本首次下载依赖，需要联网；应用本身的离线能力与构建机下载依赖是不同边界。锁定版本与下载哈希见 [native-deps.lock.json](../tools/native-deps.lock.json)。

| 依赖 | 安装位置 | 校验 |
| --- | --- | --- |
| librime 1.11.2 | imesdk/third_party/librime | 固定 Git 提交和递归子模块 |
| Boost 1.87.0 头文件 | imesdk/third_party/boost/include/boost | 官方归档 SHA-256、头文件版本 |
| ncnn Android Vulkan 20260526 | imesdk/third_party/ncnn-android/ABI | 归档 SHA-256、版本与静态库；arm64-v8a / x86_64 |

下载缓存放在 `build/native-deps-cache`。脚本先在本仓库内暂存、校验，再移动到目标目录；已有目录不会被覆盖，版本不符会报错。`--check` 只验证已安装依赖的版本与结构，不重新证明已有文件的归档哈希。排查不一致时保留本地修改，核对后再自行替换依赖。

CMake 自动运行 `tools/patch-opencc-deps.py`，重放固定依赖的 Clang 兼容补丁。词库构建与历史格式说明见 [MIGRATION.md](../MIGRATION.md)。

glog 的可选栈回溯后端固定为 `WITH_UNWIND=none`，防止 Linux 交叉编译时检测到主机 libunwind 并链接进 Android 产物；C++ 异常展开仍由对应 ABI 的 NDK 运行时提供。librime 日志已关闭。

CI 用 CMake 编译器 launcher 接入 ccache，并按编译器内容、源码与实际编译参数校验命中；缓存容量限制为 1 GB。检查失败时仍保存已编译结果以便重跑，固定依赖的版本 / 哈希检查每轮都执行。该缓存只保存构建机的编译产物，不包含输入法用户数据。

## 构建与测试

Windows：

```powershell
.\gradlew.bat :app:assembleOfflineDebug
.\gradlew.bat :imesdk:testOfflineDebugUnitTest :app:lintOfflineDebug
.\gradlew.bat :imesdk:connectedOfflineDebugAndroidTest
.\gradlew.bat -I tools/dependency-inventory.gradle :app:writeDependencyInventory
```

Linux / macOS 用 `./gradlew` 执行相同任务。输出为 `app/build/outputs/apk/offline/debug/app-offline-debug.apk`。debug 包含 arm64-v8a 与 x86_64；release 目前以 arm64-v8a 为发行目标。

安装后在系统设置启用并选择输入法。连接物理键盘的模拟器需要开启“物理键盘显示软键盘”。人工场景见[测试与验收](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Testing)。

设备回归使用 instrumentation APK 的独立数据目录与合成记录；覆盖置顶剪贴板、v1→v6 数据库迁移、主线程查询拒绝、联想冷查询保持组合串 / 候选 / 选项，按需手动执行。CI 构建两个 ABI，执行 lint、依赖 / 许可校验并保存 APK 与实际运行时依赖 SHA-256 清单，不执行单元或设备测试。更新数据库 schema 后提交 `imesdk/schemas/`；工作流拒绝未提交的 schema 差异。

两个模块的版本由根 `version.properties` 控制。每次分发提高 versionCode，版本名使用语义版本；不要再按构建时钟生成版本。相同 revision 的 Git 元数据保持一致；完整字节级重建仍需按技术债清单验证词库时间戳与隔离工具链。

## 发布签名

debug 使用 Android 标准开发签名，不需要 `keystore`。旧本地 debug 曾使用 release 签名，因此新 debug 证书可能无法直接覆盖旧安装；请使用独立测试环境，并先保留已有用户数据。

release 支持以下环境变量：`RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`；也可使用本地 `keystore/keystore.properties`：

```properties
storeFile=keystore/release.jks
storePassword=<local password>
keyAlias=<local alias>
keyPassword=<local password>
```

这些文件和口令不提交。正式发行先完成[发布验收](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Release)，再执行 `:app:assembleOfflineRelease` 并核对签名与产物；debug APK 不作为正式发行。
