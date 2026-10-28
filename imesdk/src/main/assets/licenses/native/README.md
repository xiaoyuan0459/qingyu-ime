# 原生许可归档

本目录随 APK 分发，应用内第三方声明页显示全部文本。`manifest.json` 记录来源、版本 / revision、上游文件名和文本 SHA-256；`python tools/check-native-licenses.py` 校验归档完整性。

librime 与子模块的文本直接复制自 `tools/native-deps.lock.json` 锁定的源码树；Boost 文本取自经锁定 SHA-256 验证的 1.87.0 官方 tar 包；ncnn 与 RapidJSON 文本取自官方固定版本。保留完整上游文本，不以 SPDX 名称替代条款或版权。

OpenCC revision `e5d6c5f1b78e28a5797e7ad3ede3513314e544b7` 的根目录包含 LICENSE / AUTHORS，没有 NOTICE。[Apache-2.0 §4(d)](https://www.apache.org/licenses/LICENSE-2.0) 的 NOTICE 保留要求以实际提供 NOTICE 为条件；升级时重新核对，不生成虚构的上游 NOTICE。

本归档不证明手写权重、移植 UI / 图标或 JVM 组件的全部发行条件已核验，剩余事项见仓库 LICENSING.md 与 DEBT-LICENSE-01。
