# 手写识别训练工具链（字体合成路线）

对应 [`imesdk/docs/手写识别开源迁移实施计划.md`](../../imesdk/docs/手写识别开源迁移实施计划.md) 的 A 轨：
64×64 灰度图像分类 + NCNN 端侧推理，用**自有语料**替换 CASIA 派生权重。

## 为什么不用爬虫抓字体图片

「搜索引擎抓图 → 训练」这条路在本项目里走不通，原因不是技术，是三条硬约束：

1. **授权链断了。** 抓来的图没有权利人、没有许可声明，训练出的权重无法回答「能否随应用商店分发」。
   本项目 Release 的前置条件就是每个资产都能追溯到来源与许可证（见 `LICENSING.md`、`docs/adr/0004`）。
2. **口径对不上。** 真机输入是 `preprocess.c` 的固定流程（bbox → 长边 56 → 居中 64 → 反相归一化）。
   网上图片带背景、带水印、带多字、分辨率随机，要先做切分和清洗，噪声比收益大。
3. **根本不需要抓。** 中文字形最干净的载体就是字体文件本身，OFL 字体可以直接从上游仓库拿到原始 TTF，
   许可明确、版本可锁定、可复现渲染。

所以这里的「数据源」是 **8 个 OFL-1.1 字体 × 27,664 类 × 增强变体**，全部走 `fonts.json` 登记 + SHA-256 锁定。

## 字表档位

类别数不是拍脑袋定的，上限由字体覆盖决定：

| 档位 | 汉字数 | 总类别 | 说明 |
| --- | --- | --- | --- |
| `basic` | 3,500 | 3,580 | 一级常用字，最小可用集 |
| `gb2312` | 6,763 | 6,843 | 4 个手写字体全部覆盖 |
| `tongyong` | 8,105 | 8,185 | 通用规范汉字表全集 |
| `cjk` | 20,992 | 21,072 | CJK 基本区全量（含全部生僻字） |
| `cjk_ext` | 27,584 | 27,664 | 基本区 + 扩展 A，**当前字体上限**（默认） |

生僻字的样本来源和常用字不一样，这一点必须知道：

| 区段 | 可用字体风格 | 后果 |
| --- | --- | --- |
| 3,500 以内 | 8 种（含 4 种手写） | 手写体样本充足 |
| 3,500–6,842 | 4 种手写 + 楷体 + 印刷体 | 仍以手写为主 |
| 6,843–27,584 | 只剩霞鹜文楷（楷体）+ Klee One + Noto（印刷体） | 没有毛笔/行草样本，**生僻字主要学到的是规范印刷写法** |

**扩展 B（U+20000 起，42,720 字）目前做不了**：LXGW 只覆盖 827 字、Noto 覆盖 54 字，
渲染出来是 `.notdef` 方框。要覆盖必须换 GB18030 全字集的 OFL 字体，否则只能等真人样本。


## 流程

```
fonts.json ──fetch-fonts.py──▶ .cache/fonts/*.ttf (+ fonts.lock.json)
                                      │
charset.json ◀──build-charset.py──────┘   （字频排序 + 字体覆盖过滤）
      │
      └──render-dataset.py──▶ .cache/dataset/v1/{train,val,test}.npz
                                      │   （渲染 → 增强 → preprocess_ref 64×64）
                                      └──train.py──▶ .cache/models/v1/model.{pt,onnx}
                                                            │
                                                  export-model.py ──▶ model-manifest.json / ncnn
```

关键设计：**增强作用在预处理之前的大画布上，预处理由 `preprocess_ref.py` 负责**。
`preprocess_ref.py` 是 `preprocess.c` 的逐行镜像，`verify-preprocess.py` 会把 C 源码真的编成 DLL
逐像素比对，所以「训练侧和真机侧同图同结果」是被测过的，不是靠约定。

## 环境（D 盘，不占 C 盘）

```bash
python -m venv tools/handwriting/.venv          # 用任意 Python 3.11+ 创建
tools/handwriting/.venv/Scripts/python.exe -m pip install numpy pillow jieba fonttools onnx
tools/handwriting/.venv/Scripts/python.exe -m pip install torch --index-url https://download.pytorch.org/whl/cpu
```

`.venv/` 与 `.cache/` 都已进 `.gitignore`：字体、数据集、模型、Python 环境都不进仓库、不进 APK。

## 用法

```bash
PY=tools/handwriting/.venv/Scripts/python.exe

$PY tools/handwriting/fetch-fonts.py          # 下载字体并生成 fonts.lock.json
$PY tools/handwriting/build-charset.py        # 默认 cjk_ext：27,584 汉字 + 80 标点/ASCII
$PY tools/handwriting/build-charset.py --target gb2312   # 只要常用字就换档
$PY tools/handwriting/verify-preprocess.py    # Python 预处理 vs C 实现一致性（必须先过）

# 小规模验证（约 15 秒）：前 500 类
$PY tools/handwriting/render-dataset.py --limit 500 --per-style 3 --out tools/handwriting/.cache/dataset/smoke

# 全量（约 2 分钟，22 万张）
$PY tools/handwriting/render-dataset.py --per-style 3 --weights 400 \
    --out tools/handwriting/.cache/dataset/v3

$PY tools/handwriting/train.py --data tools/handwriting/.cache/dataset/v3 \
    --out tools/handwriting/.cache/models/v3 --epochs 5 --threads 32

$PY tools/handwriting/export-model.py --model tools/handwriting/.cache/models/v3 --ncnn
```

`--weights 400` 只取可变字体的常规字重：字表上到两万多类时，多字重会把数据量翻三倍，
收益却不如多加一个真实手写风格。常用字档位可以放开成 `--weights 300,400,700`。

## 数据集口径

| 项 | 值 |
| --- | --- |
| 输入 | 64×64 灰度，float32，笔画≈1.0，背景≈0.0 |
| 类别 | 27,664（27,584 汉字 + 80 标点/ASCII），顺序即 `charset.json` 的 `classes` |
| 规模（v4） | 训练 666,375 / 验证 40,000（上限截断）/ 留出风格测试 20,526 |
| 风格 | 12 种：4 个手写体 + Klee One + 霞鹜文楷 + Noto Sans/Serif 各 3 个字重（300/400/700） |
| 增强 | 旋转、切变、缩放、弹性形变、**笔画独立位移/旋转**、笔宽增/减、随机断笔、墨水洇染、笔压不均、模糊与墨水浓度抖动 |
| 训练/验证 | 同一批字体，按样本切分（验证集用于选模型） |
| 留出测试 | `--holdout longcang`：该字体**完全不参与训练**，用来估计「陌生笔迹」表现 |

留出字体的数字才是真实参考。同字体验证集分数虚高，不要拿它下结论。
训练脚本默认按「1–3500 / 3501–8105 / 8106+」分频段输出 top1/5/10，生僻字要单独看。

多样性靠三处叠加，不是单纯堆数量：

1. **字形风格**：可变字体的 300/400/700 三个字重当三种写法，12 种风格而不是 8 种。
2. **每个风格的变体数**：`--per-style 4`，每个字每种风格 3 张训练图 + 1 张验证图。
3. **增强算子**：在几何形变之外补了墨水洇染（笔画变粗 + 边缘羽化）、笔压不均
   （低频随机场调制笔画深浅）和**笔画独立位移**（见下）。

### 笔画独立位移（`stroke_shift`）

思路来自 MIT 许可的 [bamboostrip/Handwriting-sim-rs](https://github.com/bamboostrip/Handwriting-sim-rs)
（`crates/core/src/core/perturb.rs`：4-连通标记 + 每笔画刚体变换），按本项目的 uint8 灰度口径重写。

字体渲染出的字是一个整体，而真人写字时每一笔的落笔位置和倾斜角度都有独立偏差，笔画之间会错位。
全局弹性形变做不到这一点——它用平滑位移场，相邻笔画只会一起弯；`stroke_shift` 把每个连通域
（4-连通，避免斜向相邻的笔画粘成一块）当刚体单独旋转平移。

实测单张增强 2.8 ms，对 66 万张的数据集只增加约 1 分钟渲染时间。

### 关于另外两个同类项目

调研过但**没有采用**，原因记录在案，避免以后重复踩：

| 项目 | 结论 |
| --- | --- |
| [ShiYioo/InkFlow](https://github.com/ShiYioo/InkFlow) | 仓库**没有 LICENSE**（API 返回空、LICENSE 404），内置 5 款字体是商业字库（李国夫/华阳/云烟/青叶/神韵英子），不能用于训练或随包分发。技术上是 Canvas 整页渲染，没有本项目缺的算法。 |
| [XEKernel/HandWrite-CPP](https://github.com/XEKernel/HandWrite-CPP) | 同样无 LICENSE，内置 9 款商业手写字体。纯字体渲染 + 扰动，与已有管线重合。 |

这两个都是**整页手写模拟器**（排版、纸张、导出 PDF），不是逐字样本生成器；它们的价值在字体，
而字体恰恰是不能用的部分。


## 已知边界

- **字体风格 ≠ 真人笔迹。** 字体是「规范写法」，真人有连笔、倾侧、笔顺错误、笔压变化。
  字体合成能把类别覆盖和基础结构学出来，但最终精度必须用 `server/handwriting-upload` 采集的
  真人样本做微调与验收。这也是计划里 A2 要上「手写采集页」的原因。
- **草书类字体（Liu Jian Mao Cao）只作数据增强的一部分**，不能让模型以为草书是默认写法；
  权重上靠样本比例控制，不做特殊处理。
- 目前只做单字分类。连续书写、词级重排属于 B 轨，需要带时序的轨迹输入。

## 验收

- `verify-preprocess.py` 必须全绿（最大像素差 ≤ 1/255）。
- `train-report.json` 里 `test_unseen_style.top1` 是主指标，`top5/top10` 用于候选质量。
- 模型上线前需 `model-manifest.json` 的类别数、字表哈希与 Android 侧启动校验一致。

## 实测状态（2026-10-23）

| 规模 | 配置 | 结果 | 耗时 |
| --- | --- | --- | --- |
| 500 类 | CPU，width 32，10 轮 | 验证 top1 **0.894** / top5 0.948；留出字体 top1 **0.672** / top10 0.961 | 6.6 分钟 |
| 27,664 类 | CPU，width 24，6 轮 | 2 轮后 val top5 仅 **0.010**，完全没收敛 | 10.5 分钟/轮 |
| 27,664 类 | RTX 4060，8 风格 22 万张，25 轮 | 留出字体 top1 0.886 | 18.4 分钟 |
| 27,664 类 | RTX 4060，12 风格 67 万张，20 轮 | 留出字体 top1 0.897（**含数据缺陷，见下**） | 42 分钟 |
| 27,664 类 | RTX 4060，12 风格 67 万张 + 笔画扰动，20 轮 | 留出字体 **fp16 0.890 / int8 0.884**，top5 0.970 / 0.967 | 45 分钟 |

### 第三版指标曾虚高：一个必须记住的教训

`tone` 增强最初用 `arr * gain` 整图乘增益，`gain` 最低 0.75 会把**背景**压到 191。
`preprocess.c` 用 `gray < 220` 判前景，于是整张图被当成前景，字形缩到 56 px 塞进灰底——
**62.7% 的训练样本是畸形图**，而真机背景恒为纯白。

它的危害是让离线指标虚高：旧模型在同样畸形的测试集上有 0.895，换到干净测试集只有 0.884，
而在畸形测试集上新模型直接崩到 0.223（说明新模型不依赖这个缺陷）。

修正后 `tone` 只调笔画浓度（`255 - ink*gain`），背景恒为 255。
**教训：增强算子只要碰到前景/背景的灰度边界，就必须和 `preprocess.c` 的阈值一起验证**，
不能只看增强图「像不像手写」。现在的检查方式是量「灰底像素占比」（v4 p50 = 53%，v6 p50 = 5.6%）。


GPU 上 44 秒/轮（v3 数据量）到 127 秒/轮（v4 数据量），比 CPU 快 5～14 倍。
验证集 99.6% 是同字体不同增强的**记忆分数**，不要当泛化看；
**留出字体（longcang，完全不参与训练）的 89.7% 才是真实参考**。

一个测量缺口：留出字体 Long Cang 只覆盖 6,842 字，所以**扩展区和生僻字没有跨风格测试样本**，
`test_unseen_style` 的 `8106-末` 桶只有 1,168 个样本（是 GB2312 内的低频字，不是扩展区）。
要给全部 27,664 类都拿到跨风格指标，得把 `--holdout` 加一个覆盖全字表的字体（如 `notoserifsc`），
代价是训练集少一种风格。

NCNN 产物 16.5 MB（fp16）。要压到 8 MB 左右需要 int8 量化 + 校准集，属于发版前的一步。


## int8 量化（16.5 MB → 8.05 MB）

```bash
$PY tools/handwriting/quantize-int8.py \
    --model tools/handwriting/.cache/models/v4 \
    --data  tools/handwriting/.cache/dataset/v4 \
    --calib 1000 --method kl
```

走 ncnn 官方三段工具，不自己造量化器：`ncnnoptimize`（fp16→fp32）→ `ncnn2table`（校准表）→ `ncnn2int8`。
校准图直接从训练集抽，像素口径就是 `preprocess.c` 的输出，所以 `mean=[0.0]`、`norm=[1/255]`、`pixel=GRAY`。

| 版本 | 体积 | top1 | top5 | top10 |
| --- | --- | --- | --- | --- |
| fp16 | 15.78 MB | 0.898 | 0.972 | 0.982 |
| **int8** | **8.05 MB** | 0.881 | 0.965 | 0.977 |
| 差值 | −49% | −1.72pp | −0.72pp | −0.47pp |

实测过三种校准配置，差别在噪声范围内（6000 张测试图，标准误约 ±0.4pp）：

| 配置 | top1 损失 |
| --- | --- |
| kl / 400 张 | −1.74pp |
| aciq / 800 张 | −1.87pp |
| **kl / 1000 张** | **−1.70pp** |

**Top-5 只掉 0.72pp**，对输入法来说是可接受代价（用户看的是候选列表）。
要再往下压损失只能上 QAT（量化感知训练），需要 ncnn 的 QAT 工具链，成本比收益高，暂不做。

三个必踩的坑，已经写进脚本：

1. `ncnn2int8` 的参数顺序是 `in.param in.bin out.param out.bin table`，**表在最后**。
2. `ncnn2table` 的 `mean` 必须和 `pixel=GRAY` 的通道数一致，写成 `mean=[0,0,0]` 会报 `expect 0 means, but got 1`。
3. pnnx 导出的 blob 名是 `in0`/`out0`，不是 ONNX 的 `input`/`logits`；而且要从 param 里按
   `4 + bottom_count` 的位置取 top blob，直接取第 5 个字段会拿到上一层输入，推理结果全是垃圾。

## GPU 环境（D 盘）

```bash
# CUDA 12.8 版，约 3 GB，装到项目内 venv
export PIP_CACHE_DIR=D:/project/QingyuIme/tools/handwriting/.cache/pip
tools/handwriting/.venv/Scripts/python.exe -m pip install --no-cache-dir \
    torch==2.9.1 --index-url https://download.pytorch.org/whl/cu128
```

ncnn 量化工具（`ncnnoptimize` / `ncnn2table` / `ncnn2int8`）在官方 Windows 包里：

```bash
curl -L -o ncnn.zip https://github.com/Tencent/ncnn/releases/download/20261020/ncnn-20261020-windows-vs2022.zip
unzip -q ncnn.zip -d tools/handwriting/.cache/ncnn/
# 工具在 .cache/ncnn/ncnn-20261020-windows-vs2022/x64/bin/
```

