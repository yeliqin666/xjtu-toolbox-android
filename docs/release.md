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

GitHub Pre-release 与 OSS 上的预览 APK 都只保留最近 5 个：CI 按版本目录删除旧的，残留的非当前版本与未完成分片上传由 bucket 生命周期规则兜底清理。

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
| `xjtu-toolbox/apk/latest/app-release.apk` | 固定别名，每次发版覆盖，`Cache-Control: no-cache`（ESA 跟随源站：边缘每次回源校验；发版后 CI 仍刷一次兜底） |
| `xjtu-toolbox/stable.json`、`history/stable-<versionName>.json` | 更新清单与历史清单 |

固定下载链接（官网、二维码等长期入口使用）：

```
https://dzhz.zyxf.top/xjtu-toolbox/apk/latest/app-release.apk
```

预览包只写 `apk/preview/<version>/app-preview.apk` 与 `preview.json`，不写别名。CI 会核对别名对象的 `Content-Length` 与本次 APK 一致。

CDN（`dzhz.zyxf.top`，阿里云 ESA）的缓存以线上规则为准（2026-11-24 起；ESA 套餐缓存规则上限 5 条，改之前先 `ListCacheRules` 看还有没有位）：

| 匹配 | 边缘 TTL | 说明 |
|---|---|---|
| `/xjtu-toolbox/**` | 跟随源站，缺省回退 30 天 | 清单上传时带 `Cache-Control: no-cache` → 边缘只做校验、不缓存；安装包没有缓存头 → 边缘 30 天 |
| `/assets/**` | 跟随源站，缺省回退 1 年 | nginx 对文件名带 hash 的产物给 `max-age=31536000, immutable` |
| `dzhz.zyxf.top` 其余路径 | 强制 5 分钟 | 官网 HTML 等；改版最多 5 分钟全网生效 |
| `oss.zyxf.top` | 7 天 | 资料站资源（与盒子无关） |
| `/api/`、`/.well-known/`、`/games/` | 不缓存 | 动态接口、证书签发、榜单服务 |
| `zyxf.top` 主站 | 无专门规则，默认跟随源站 | HTML 是 `no-cache` 不缓存，`/assets/**` 按源站 1 年 |

更新链路是：客户端读 `stable.json` / `preview.json`，按 `publishedAt` / `versionName` 比对版本，再从清单里的 `apkUrl` 下载——安装包按版本归档（不可变），长缓存不会读到旧包；清单带 `no-cache`、边缘每次回源校验，版本号一变客户端立刻能看到。

`apk/latest/app-release.apk` 这个固定别名同样带 `no-cache`，边缘会回源校验；发正式版时 CI 还会主动刷新它和本次版本的 APK 一次当兜底。清单 JSON 不缓存、不需要刷；版本目录的 APK 平时也不用刷，但**重发同一版本**（例如 OSS 主发布失败后重跑）会重新构建出字节不同的包，旧包若已被边缘缓存就会和清单里的 `sha256` 对不上（客户端报「安装包 SHA-256 校验失败」），所以 CI 每次发版都把该版本目录的 APK 一起刷。刷新凭据放在仓库 secrets：

| Secret | 用途 |
|---|---|
| `ESA_SITE_ID` | 站点 ID，`zyxf.top` 为 `190304393599084` |
| `ESA_ACCESS_KEY_ID` / `ESA_ACCESS_KEY_SECRET` | RAM 用户 `xjtu-toolbox-esa` 的 AK，只授 `esa:PurgeCaches`、`esa:DescribePurgeTasks`；没配时 [`esa_purge.py`](../.github/scripts/esa_purge.py) 只打 notice 跳过，不会让发版失败 |

## Cloudflare R2 更新渠道

客户端更新渠道可选 `阿里云`（默认）、`Cloudflare`、`GitHub`、`Gitee`。其中 `Cloudflare` 渠道读取 R2 自定义域名 `https://dl.dzhz.top`（桶 `dzhz-releases`，对象路径不带 `xjtu-toolbox/` 前缀，直接为 `stable.json`、`preview.json`、`apk/...`、`history/...`）。

CI 通过 [`.github/scripts/r2_publish.py`](../.github/scripts/r2_publish.py) 同步正式版与预览版，预览版同样仅保留最近 5 个版本目录；以下 secrets 任一未配置时自动跳过、不影响发版：

| Secret | 用途 |
|---|---|
| `R2_ACCOUNT_ID` | Cloudflare Account ID |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | 仅授桶 `dzhz-releases` Object Read & Write 的 R2 API Token（S3 凭据） |
| `R2_BUCKET` | 桶名，当前为 `dzhz-releases` |
| `R2_PUBLIC_BASE` | 可选，自定义下载基址，默认 `https://dl.dzhz.top` |
