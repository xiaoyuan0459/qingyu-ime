# 正式品牌素材

当前品牌是青色羽毛。以下文件参与源码交付与资源生成：

- `qingyu-icon-master.png`：应用图标的正式主图。
- `qingyu-glyph-transparent.png`：透明羽毛，用于启动器和工具栏。
- `tools/gen_launcher_icons.py`：从上述源文件生成各密度启动器图标、自适应前景和工具栏图片，需要 Pillow。

生成命令：`python tools/gen_launcher_icons.py`。普通应用构建使用已提交的 Android 资源，无需运行生成器或安装 Pillow。更新源素材时核对各密度、debug / release、系统掩模与工具栏；生成器不能依赖被忽略的草稿目录。

曾使用的矢量品牌方案可从 Git 历史恢复。其他生成草稿与产品参考图只留本地。现有正式素材的生成来源和授权记录仍需在公开发行前由维护者补齐，见 [许可待办](../TECHNICAL_DEBT.md#debt-license-01)。
