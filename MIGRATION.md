# 原生迁移与数据兼容

本项目从 YuyanIme 的 Android 输入框架迁移而来，主工程模块已统一为 `app` 与 `imesdk`，JNI 包与应用品牌已改为轻语。本文保留迁移中的兼容事实；日常环境准备统一见 [开发指南](docs/DEVELOPMENT.md)。

## 当前实现

- `RimeSession` / `EngineTypes` 定义 Kotlin 与 JNI 的边界。
- `libqingyu_rime.so` 自行编译 librime 1.11.2，并提供应用所需的 JNI 入口。
- 原闭源 `libyuyanime.so` 不再是构建或运行依赖。
- 默认词库由固定来源重新编译，以 `assets/rime-base.zip` 分发；用户导入包按清单校验并支持失败回滚。
- 手写识别由 ncnn 与本地 JNI 实现；代码、模型的归属核验仍见 [许可清单](LICENSING.md)。
- 候选笔画筛选只读当前包的 `stroke.reverse.bin`，使用固定 librime 的 ReverseDb C++ 类型；升级需验证头文件和反查二进制兼容，见 [ADR 0003](docs/adr/0003-候选展示筛选与只读笔画反查.md)。

接口详见 [原生层契约](imesdk/docs/自研native层接口契约.md)，数据管理详见[架构说明](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Architecture)。

## 原生依赖

`tools/bootstrap-native-deps.py` 按 `tools/native-deps.lock.json` 准备 librime 的固定提交及其子模块、Boost 头文件和 ncnn Android Vulkan 静态库。源码与下载缓存只在本地保留。构建配置自动运行 `tools/patch-opencc-deps.py`，解决固定依赖与当前 Clang 的兼容问题；另运行 `tools/patch-librime-correction-type.py` 增加纠错拼写类型 `kCorrectionSpelling` 与切分策略（见 [ADR 0007](docs/adr/0007-纠错切分权重.md)）。补丁不改变依赖来源或许可证。

详细版本、目录结构和校验命令见 [开发指南](docs/DEVELOPMENT.md)。不要从旧工程复制来源不明的 `.so`、词库二进制或手写模型替代缺失依赖。

## 旧词库二进制

旧工程继承的 `*.table.bin` 与上游 librime 1.11.2 的元数据布局不同，曾造成装载词典时崩溃：旧格式头部为 `0x48` 字节，上游为 `0x44` 字节，字符串表字段的位置不同。

`tools/patch-table-bin-for-upstream-librime.py` 是历史诊断与修复工具。当前词库应从源数据重新编译，不依赖该脚本作为运行前提，也不将旧继承二进制重新打入 APK。

生成词库包使用 `tools/build-rime-pack.py`。默认雾凇、可选白霜和笔画数据的来源 / revision 以 pack 的 `SOURCE.md`、许可证和 manifest 为准；源码包、编译工具与运行时包是不同产物，分发时需保留对应关系。

## 升级注意事项

- 用户词库和词频与可替换的基础词库分开保存；导入失败应保留原来的可用包。
- 基础包的内容变化需要同步版本和哈希；不要仅替换 zip 而保留旧 manifest。
- pack 的逐文件 SHA-256 证明本次产物完整性，不能直接证明两次构建字节完全一致；时间戳等可复现性问题见 [DEBT-BUILD-02](docs/TECHNICAL_DEBT.md#debt-build-02)。
- 修改内置 zip 后应核对最终 APK 内容；出现异常增量体积时先清理模块构建输出再重新打包。
- 迁移结论主要来自 x86_64 模拟器；arm64 与厂商系统覆盖仍需独立验证。
