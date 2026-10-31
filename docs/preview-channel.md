# 预览渠道与发版操作指引

本文档记录本项目（`yeliqin666/xjtu-toolbox-android`）预览渠道（dev 分支 + preview 构建包 + 全量推送）的日常发版与维护流程。

---

## 1. 架构与基本原理

- **分支分工**：
  - `main`：正式发布分支。CI 构建 `release` 变体、打 `v*` tag、发 GitHub Latest Release、全量同步至 Gitee。
  - `dev`：日常开发集成分支。CI 默认构建 `preview` 变体、打 `dev-N` tag、发 GitHub Pre-release（`make_latest: false`），**不同步 Gitee**；本次不发布时在 head commit / PR 标题写 `[skip preview]`。
- **构建类型 `preview`**：
  - 不混淆、不压缩（保留完整可读崩溃堆栈与 Log）。
  - **非 debuggable**（避免 Compose 调试卡顿）。
  - 正式签名，与正式版同签名同包名，可直接互相覆盖安装不丢登录态与本地数据。
  - `versionNameSuffix` 为 `-dev.${GITHUB_RUN_NUMBER}`。
- **预览版全量推送**：
  - 仅对在「设置 → 更新」中开启「接收预览版更新」的用户生效（GitHub 渠道）。
  - 开启后不做灰度分桶，所有预览版都会推送给这些用户；`rollout` 字段固定为 `100`，只为兼容旧客户端保留。
  - 预览包自身默认开启接收预览版更新。

---

## 2. 日常开发流程

1. **日常开发**：
   - 开发者从 `dev` 分支拉出功能分支（例如 `feat/...` 或 `fix/...`）。
   - 开发测试完成后，提 PR 合并回 `dev` 分支。
2. **预览包按需发出**：
   - PR 合并或 push 到 `dev` 后，CI 默认运行 `Build Preview APK`，构建出 `xjtu-toolbox-X.Y.Z-dev.N.apk` 并创建 GitHub Pre-release。
   - 如果本次改动暂不发预览，在本次推送的 head commit（通过 PR 合并时写在 PR 标题）加入 `[skip preview]`。CI 只跑校验、测试与 release 收缩检查，不构建、不发布预览包；改动会在下一次发布预览时一并进入 beta。详见 [DEVELOP.md](../DEVELOP.md) §1.6。
   - 只想补发当前 `dev` 上累积的改动时，可在 Actions 中手动运行 `Build & Release`，选择 `dev` 并勾选 `publish_preview`。
   - 预览版全量推送，不设灰度比例。
   - CI 会自动清理：GitHub Pre-release 与 OSS 上的预览 APK 都只保留最近 5 个版本；OSS 的历史版本与删除标记由 bucket 生命周期策略兜底清理。

---

## 3. 紧急止血

预览版已取消灰度分桶，`rollout` 固定为 `100`，不能再通过 `rollout: 0` 阻止分发。

发现预览包存在重大崩溃或异常时：

1. **首选**：立即合入修复并发布更高版本号的预览版；所有开启「接收预览版更新」的用户都会收到新版本。
2. **修复包尚未准备好**：先把有问题的 GitHub Pre-release 转为 draft 或删除，阻止还没更新的用户继续拉取；已经安装的用户会在修复预览发布后升级。
3. 不要修改 Release 说明里的 `rollout` 字段来止血。新客户端已不再解析它，旧客户端也不应把它当作唯一手段。

---

## 4. 发送正式版流程

当 `dev` 上的改动经过充分测试、准备发布正式版时：

1. **准备更新日志**：
   - 在 `dev` 分支确认 `app/src/main/java/com/xjtu/toolbox/update/AppChangelog.kt` 的 `ENTRIES` 顶部已写好当前版本（`X.Y.Z`）的更新条目。
2. **合并至 main**：
   - 提交 PR：`dev → main`，代码审核通过后合并。
   - `main` 的 CI 将自动运行正式版发布流程：构建 Release APK、打 `vX.Y.Z` tag、生成 Release Notes、发布到 GitHub Latest、并自动同步代码、tag 与 Release 到 Gitee。
3. **dev 提升版本号（必须立即执行）**：
   - 正式版发出后，**立刻**在 `dev` 分支上把 `app/build.gradle.kts` 中的 `versionName` 升至下一个规划的正式版号（例如 `X.Y.(Z+1)`），并把 `versionCode` 增加 2（为 `main` 上的可能热修复预留 1 个 code）。
   - **同一个提交里**在 `AppChangelog.kt` 的 `ENTRIES` 顶部加一个新版本号的条目（内容可以先占位，发版前补全）。`AppChangelog` 启动时会校验当前版本有条目，缺了预览包会一打开就崩；CI 也会检查，缺条目时预览构建直接失败。
   - 提交并推送到 `dev`。
   - *防呆保证*：如果忘记此步骤，CI 的预览构建步骤会校验 dev 版本必须大于最新正式 tag，若不满足则会直接报错退出，避免发出比正式版还“旧”的预览版。

---

## 5. main 热修回流流程

如果线上正式版发现紧急 Bug，需在 `main` 上直接修复：

1. 在 `main` 上开分支修复，`versionCode` 使用预留的那一个号（例如 63），`versionName` 递增 `PATCH`（例如 `X.Y.(Z+1)`）；不要使用 `X.Y.Z.1` 这类四段式编号。
2. 合并发布热修复。
3. 开 PR 把 `main` 回流至 `dev`（`main → dev`），解决冲突时**保留 `dev` 的主版本号与 versionCode**。

---

## 6. 分发渠道与固定下载链接

正式版由 CI 同时写到三处（见 `.github/workflows/build.yml` 的 `Publish stable to OSS (primary)`）：

| 对象键 | 用途 |
|---|---|
| `xjtu-toolbox/apk/<versionName>/app-release.apk` | 按版本归档；`stable.json` 的 `apkUrl` 指向它 |
| `xjtu-toolbox/apk/latest/app-release.apk` | **固定别名**，每次发版覆盖，`Cache-Control: no-cache` 避免 CDN 继续下发旧包 |
| `xjtu-toolbox/stable.json` + `history/stable-<versionName>.json` | 客户端更新清单与历史清单 |

固定别名的公网直链（域名取 `secrets.OSS_PUBLIC_BASE`，与 `AppUpdater.OSS_BASE_URL` 一致）：

```
https://dzhz.zyxf.top/xjtu-toolbox/apk/latest/app-release.apk
```

用途：官网、文档、二维码等不会随版本变化的下载入口。**注意**别名只在正式版发布后存在；
预览包只写 `apk/preview/<version>/app-preview.apk` 与 `preview.json`，不写别名。

验证要点（CI 已内置）：别名对象的 `Content-Length` 必须与本次 APK 一致；
`Cache-Control` 不是 `no-cache` 时打 warning；公网侧用带版本号查询串的 HEAD 抽查绕过缓存。

CDN 侧（`dzhz.zyxf.top`，`PayByTraffic` 按流量计费）的路径缓存规则必须与上面的对象划分对齐，否则会盖掉源站的 `Cache-Control`：

| 路径 | TTL | weight | 说明 |
|---|---|---|---|
| `/xjtu-toolbox/apk/` | 31536000（1 年） | 1 | 版本号目录不可变，长缓存省回源；`latest` 也在该前缀下，靠下面的规则优先命中 |
| `/xjtu-toolbox/apk/latest/` | 60 | 2 | 固定别名会变，短 TTL：发版后最多 60 秒内仍可能下发旧包 |
| `*.json`（`filetype_based_ttl_set`） | 0 | 1 | 清单不缓存，客户端每次都取最新 |

2026-10-28 已在控制台核对并补齐 `latest` 这一条（原只有 1 年那条，会把别名锁死一年）。
