# 手写样本自动上传中转（腾讯云函数）

App 不直接连 GitHub，也不在 APK 里放 token。数据流：

```
轻语输入法（用户明确同意 + 仅 Wi-Fi）
  → 腾讯云 SCF HTTP 触发地址（默认 HTTPS 域名，无需自购域名）
  → 校验 JSONL
  → 直接写入私有 GitHub 数据仓库（不经过对象存储）
```

App 只在设置里保存 SCF 的触发地址；GitHub fine-grained token 只存在函数环境变量里，权限限定到单个私有数据仓库。

## 前置

1. 一个私有 GitHub 数据仓库，例如 `xiaoyuan0459/qingyu-handwriting-data`。公开的 `qingyu-handwriting-dataset` 只放格式规范，不接收自动上传的训练数据。
2. 腾讯云账号（已完成实名），开通云函数 SCF 与 API Gateway。
3. 一个 fine-grained GitHub PAT：
   - Resource owner：你的账号；
   - Repository access：只选上述私有数据仓库；
   - Permissions：`Contents: Read and write`，其余全部 No access；
   - 设置到期时间，泄露时立即撤销。

## 部署

### 1. 创建云函数

- 区域：优先香港；国内区域访问 GitHub 可能不稳定，部署后先用函数里的“测试”确认能访问 `api.github.com`。
- 运行环境：Node.js 18 或 20。
- 函数代码：把 [index.js](index.js) 全文粘贴到在线编辑器（单文件，无 npm 依赖）。
- 执行入口：`index.main_handler`。
- 内存/超时：256 MB / 30 秒。
- 环境变量：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `GITHUB_TOKEN` | 是 | 上述 fine-grained PAT |
| `GITHUB_OWNER` | 是 | 例如 `xiaoyuan0459` |
| `GITHUB_REPO` | 是 | 私有数据仓库，例如 `qingyu-handwriting-data` |
| `GITHUB_BRANCH` | 否 | 默认 `main` |

### 2. 添加 API Gateway 触发

- 触发方式：HTTP API / API Gateway；
- 方法：`POST`，路径 `/upload`；
- 鉴权：免鉴权（函数只做数据校验和写入私有仓库；不要给 token 加额外权限）；
- 域名：使用腾讯云提供的默认 HTTPS 地址，不需要购买域名或备案自有域名；
- 限流：建议 5–10 QPS、burst 20 以内，请求体上限 256 KB。

部署完成后记下触发地址，形如：

```
https://xxxx.apigw.tencentcs.com/upload
```

### 3. 用 curl 验证

```bash
curl -sS -X POST 'https://xxxx.apigw.tencentcs.com/upload' \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary @fixtures/sample.jsonl
```

预期返回：

```json
{"ok":true,"accepted":1,"path":"inbox/20261020/<hash>.jsonl","duplicate":false}
```

然后到私有数据仓库确认 `inbox/20261020/<hash>.jsonl` 已生成。重复提交同一批次应返回 `duplicate:true`，不会再生成新文件。

### 4. 配置 App

把触发地址填到：

`imesdk/src/main/res/values/strings.xml`

```xml
<string name="handwriting_upload_endpoint" translatable="false">https://xxxx.apigw.tencentcs.com/upload</string>
```

重新构建并安装：

```bash
./gradlew.bat :app:assembleOfflineDebug
```

设置 → 手写设置 → 帮助改进手写：

1. 先开启“保存手写样本”；
2. 再开启“自动上传样本”，阅读并同意弹窗；
3. 默认仅在 Wi-Fi 下上传；写一个字并明确选字后，状态会显示待上传/已上传。

## 隐私与安全边界

- 只上传：笔迹轨迹、选中的字、采集日期；
- 不上传：账号、设备标识、输入框其他文字、截图；密码等敏感输入框不采集；
- 只上传开启同意之后新产生的样本，之前保存在本机的样本不会补传；
- 数据仓库必须是私有仓库，不公开展示；
- 函数不记录请求体；错误日志只写状态码/错误类别；
- API Gateway 限流 + 256 KB/50 条上限降低滥用风险，但不能替代正式后端；
- 删除：样本 `id` 在本地和仓库文件中；目前由维护者按 `id` 从私有仓库删除，后续可加删除接口；
- token 只放函数环境变量，泄露后立即在 GitHub 撤销并重发。

## 规模限制

- GitHub Contents API 的写入频率受 token/仓库限制；一批一次 commit，减少调用；
- 私有仓库不适合长期存放大规模训练数据；用户量上来后应迁移到正式后端和私有对象存储；
- 函数每次新建一个文件，避免并发覆盖；训练侧按样本 `id` 去重；
- 免费额度/计费以腾讯云当期规则为准，低流量成本通常很低，但 API Gateway 和流量可能产生少量费用。

## 本地测试

```bash
cd server/handwriting-upload
node --test index.test.js
```

测试只验证格式与语义，不会访问 GitHub。
