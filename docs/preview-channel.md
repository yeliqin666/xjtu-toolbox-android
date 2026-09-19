# 预览渠道与灰度发布操作指引

本文档记录本项目（`yeliqin666/xjtu-toolbox-android`）预览渠道（dev 分支 + preview 构建包 + 客户端按比例拉取灰度）的日常发版与维护流程。

---

## 1. 架构与基本原理

- **分支分工**：
  - `main`：正式发布分支。CI 构建 `release` 变体、打 `v*` tag、发 GitHub Latest Release、全量同步至 Gitee。
  - `dev`：日常开发集成分支。CI 构建 `preview` 变体、打 `dev-N` tag、发 GitHub Pre-release（`make_latest: false`），**不同步 Gitee**。
- **构建类型 `preview`**：
  - 不混淆、不压缩（保留完整可读崩溃堆栈与 Log）。
  - **非 debuggable**（避免 Compose 调试卡顿）。
  - 正式签名，与正式版同签名同包名，可直接互相覆盖安装不丢登录态与本地数据。
  - `versionNameSuffix` 为 `-dev.${GITHUB_RUN_NUMBER}`。
- **客户端灰度分桶**：
  - 仅对在「设置 → 更新」中开启「接收预览版更新」的用户生效（GitHub 渠道）。
  - Release 说明中包含 `rollout: N`（默认 20%）。
  - 客户端使用本机随机 `rolloutId` 与 Release tag 计算哈希分桶（`SHA-256(rolloutId:tag) % 100 < N`）。
  - 预览包自身默认开启接收预览版更新。

---

## 2. 日常开发流程

1. **日常开发**：
   - 开发者从 `dev` 分支拉出功能分支（例如 `feat/...` 或 `fix/...`）。
   - 开发测试完成后，提 PR 合并回 `dev` 分支。
2. **预览包自动发出**：
   - PR 合并或 push 到 `dev` 后，CI 自动运行 `Build Preview APK`，构建出 `xjtu-toolbox-X.Y.Z-dev.N.apk` 并创建 GitHub Pre-release。
   - 初始默认推送比例为 20%（`rollout: 20`）。
   - CI 会自动清理并仅保留最近 5 个预览版 Pre-release。

---

## 3. 灰度比例调整与止血

- **调高/调低灰度**：
  - 进入 GitHub 仓库的 Releases 页面，找到对应的 Pre-release，点击编辑说明。
  - 直接修改文本中的 `rollout: 20` 行（例如改成 `rollout: 50` 或 `rollout: 100` 全量），保存即可。
  - 客户端下一次请求更新时会动态解析并重新判定命中，无需重新构建或发布 APK。
- **紧急刹车（紧急止血）**：
  - 如果发现当前预览包存在重大崩溃或异常，立刻编辑该 Release 说明，将比例改为 `rollout: 0`。
  - 尚未更新的用户将立即停止接收此预览包；修复后直接合入 `dev` 发出新版预览覆盖即可。

---

## 4. 发送正式版流程

当 `dev` 上的改动经过充分测试、准备发布正式版时：

1. **准备更新日志**：
   - 在 `dev` 分支确认 `app/src/main/java/com/xjtu/toolbox/util/AppChangelog.kt` 的 `ENTRIES` 顶部已写好当前版本（`X.Y.Z`）的更新条目。
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

1. 在 `main` 上开分支修复，`versionCode` 使用预留的那一个号（例如 63），`versionName` 设为 `X.Y.Z.1`。
2. 合并发布热修复。
3. 开 PR 把 `main` 回流至 `dev`（`main → dev`），解决冲突时**保留 `dev` 的主版本号与 versionCode**。
