# 发版渠道与分发

版本号、分支与发布步骤以 [CONTRIBUTING.md](../CONTRIBUTING.md) 为准，本文只补充渠道、构建变体、止血与分发细节。

## 渠道

| 分支 | 构建 | 发布 |
|---|---|---|
| `main` | `release` 变体 | `vX.Y.Z` tag、GitHub Latest Release、`stable.json`，同步 Gitee，附桌面安装包 |
| `dev` | `preview` 变体 | `vX.Y.Z-beta.N` tag、GitHub Pre-release（`make_latest: false`）、`preview.json`，不同步 Gitee |

`preview` 构建类型 `initWith(release)`：混淆、资源收缩与正式版同一套，能提前暴露只在 R8 后才出现的 keep / 序列化问题；非 debuggable；正式签名、同包名，可与正式版互相覆盖安装。堆栈是混淆过的，CI 按版本上传 `mapping.txt` 供 retrace。

`N` 由 CI 查 GitHub 上同核心版本已发布的预览 tag 取最大值加 1；`[skip preview]` 不占号，同一 run 重跑按提交 SHA 找回原号。

### 手动触发

在 Actions 运行 `Build & Release` 并选 `dev`：

- `publish_preview`：补发 `dev` 上累积的改动，不受 `[skip preview]` 影响。
- `release_preview`：改用 `release` 变体（命令与正式发布一致）构建预览版，用于正式版上线前连验几轮。也可在 head commit 写 `[release preview]` 触发。它仍是预览渠道：只写 `preview.json`、发 Pre-release，不动 `stable.json`、`latest` 别名和 Gitee。

### 推送范围

预览版只推给在「设置 → 更新」里开启「接收预览版更新」的用户，不做灰度分桶。`rollout` 字段固定为 `100`，仅为兼容旧客户端保留。

GitHub Pre-release 与 OSS 上的预览 APK 都只保留最近 5 个，OSS 历史版本由 bucket 生命周期策略兜底清理。

## 止血

预览包出现严重问题时：

1. 首选立即合入修复，发布更高版本号的预览版；
2. 修复还没准备好时，先把有问题的 Pre-release 转为 draft 或删除，阻止未更新的用户继续拉取；
3. 不要靠改 Release 说明里的 `rollout` 止血，新客户端已不解析它。

## 分发

正式版由 CI 写入 OSS（`build.yml` 的 `Publish stable to OSS (primary)`）：

| 对象键 | 用途 |
|---|---|
| `xjtu-toolbox/apk/<versionName>/app-release.apk` | 按版本归档，`stable.json` 的 `apkUrl` 指向这里 |
| `xjtu-toolbox/apk/latest/app-release.apk` | 固定别名，每次发版覆盖，`Cache-Control: no-cache` |
| `xjtu-toolbox/stable.json`、`history/stable-<versionName>.json` | 更新清单与历史清单 |

固定下载链接（官网、二维码等长期入口使用）：

```
https://dzhz.zyxf.top/xjtu-toolbox/apk/latest/app-release.apk
```

预览包只写 `apk/preview/<version>/app-preview.apk` 与 `preview.json`，不写别名。CI 会核对别名对象的 `Content-Length` 与本次 APK 一致。

CDN（`dzhz.zyxf.top`）的路径缓存规则必须与对象划分对齐，否则会覆盖源站的 `Cache-Control`：

| 路径 | TTL | 权重 | 说明 |
|---|---|---|---|
| `/xjtu-toolbox/apk/` | 1 年 | 1 | 版本目录不可变 |
| `/xjtu-toolbox/apk/latest/` | 60 秒 | 2 | 别名会变，优先命中 |
| `*.json` | 0 | 1 | 清单不缓存 |
