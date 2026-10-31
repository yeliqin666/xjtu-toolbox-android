# 开发规范

本文件是岱宗盒子（XJTU ToolBox）的开发规范，面向所有向本仓库提交代码、构建安装包或参与发版的开发者。

规范中的「必须」「不得」为强制要求；「应当」「建议」为默认做法，偏离时应当在 PR 中说明理由。

本文件与代码一起接受版本管理。修改规范本身也需要通过 PR，并在同一个 PR 中同步修改负责执行该规则的 CI 检查、单元测试或脚本。

---

## 1. 版本号规范

### 1.1 总则

版本号遵循 [Semantic Versioning 2.0.0](https://semver.org/lang/zh-CN/) 的格式与优先级规则，并在此基础上约定开发版（beta）的编号方式。

| 类型 | 格式 | 示例 |
|---|---|---|
| 正式版 | `MAJOR.MINOR.PATCH` | `5.1.2` |
| 开发版（beta） | `MAJOR.MINOR.PATCH-beta.N` | `5.1.2-beta.1` |

- `MAJOR`、`MINOR`、`PATCH`、`N` 均为非负整数；除 `0` 本身外不得有前导零，`beta.01` 不合法。
- 预发布标识固定使用小写 `beta`。不得使用 `alpha`、`rc`、`dev`、`snapshot`、`beta1`、`b1` 等写法。
- `versionName` 中不带 `v` 前缀；`v` 只用于 Git tag 和面向用户的展示，例如 `v5.1.2`。
- 版本号一经发布即不可变。同一个版本号不得对应两份内容不同的安装包；需要重新构建时必须递增版本号。

### 1.2 核心版本号 X.Y.Z

核心版本号是当前开发周期最终要发布的正式版号。

1. 必须严格大于当前最新正式版。例如最新正式版为 `5.1.1`，则 `dev` 上的核心版本号至少为 `5.1.2`；不得继续用 `5.1.1` 发 beta，也不得使用比 `5.1.1` 更小的号。
2. 按 SemVer 语义递增：
   - `MAJOR`：存在不兼容的接口、数据格式或行为变更；
   - `MINOR`：向下兼容的功能新增，或需要单独标记的功能版本；
   - `PATCH`：向下兼容的问题修复与常规迭代。
3. 本项目日常迭代默认递增 `PATCH`。例如最新正式版为 `5.1.1`，本迭代包含若干功能改动与修复，目标版本定为 `5.1.2`，开发版从 `5.1.2-beta.1` 开始。只有需要单独标记功能里程碑时才递增 `MINOR`，例如 `5.2.0-beta.1`。
4. 核心版本号在迭代开始时确定。beta 发布后不得随意更换核心版本号，例如不得把 `5.1.2-beta.3` 直接改成 `5.1.3-beta.4`；确需更换目标版本时，必须从新核心版本的 `beta.1` 重新开始。
5. 不得使用两段式（`5.1`）或四段式（`5.1.2.1`）版本号。历史上出现过的 `4.71`、`4.9.8.1` 等写法仅用于兼容旧客户端，不得新增。

### 1.3 beta 序号 N

1. `N` 在每个核心版本内独立编号，从 `1` 开始：`5.1.2-beta.1`、`5.1.2-beta.2`、`5.1.2-beta.3`……
2. 每发布一个 beta，`N` 递增 `1`，不得跳号。
3. 核心版本变化后，`N` 必须重置为 `1`。例如 `5.1.2` 正式版发布后，下一轮开发从 `5.1.3-beta.1`（或 `5.2.0-beta.1`）开始，不得写成 `5.1.3-beta.8`。
4. 不得跨核心版本累加 beta 序号，也不得使用 CI 运行号、提交数、日期等全局递增数字代替 `N`。`5.1.2-dev.244` 这类写法不再允许。
5. 已发布的 beta 序号不得复用。某个 beta 构建有问题时，发布 `N+1` 修复；如果问题严重且修复尚未就绪，可以先把该预发布转为 draft 或删除，阻止尚未更新的用户继续拉取，详见预览渠道文档。不得删除后重新用同一个 `N` 发布不同内容。
6. 同一核心版本的所有 beta 共用同一个更新日志条目，见 1.5。
7. 跳过发布的开发版推送不占用 `N`；`N` 只在真正发布 beta 时递增。被跳过的改动会在下一次发布时一并进入 beta，见 1.6。

### 1.4 版本顺序与升级路径

版本比较以 `versionName` 为准，遵循 SemVer 预发布优先级：

```text
5.1.2-beta.1 < 5.1.2-beta.2 < ... < 5.1.2 < 5.1.3-beta.1 < ...
```

必须满足以下升级路径：

| 当前版本 | 目标版本 | 是否应收到更新 |
|---|---|---|
| `5.1.2-beta.N` | `5.1.2-beta.N+1` | 是 |
| `5.1.2-beta.N` | `5.1.2` | 是 |
| `5.1.1` 正式版，未开启预览 | `5.1.2-beta.1` | 否 |
| `5.1.1` 正式版，已开启预览 | `5.1.2-beta.1` | 是 |
| `5.1.2` 正式版 | `5.1.2-beta.N` | 否 |

由此得到两条硬性要求：

1. `X.Y.Z` 正式版必须大于同一核心版本的所有 `X.Y.Z-beta.N`。这是 SemVer 的固有顺序，不得通过修改 `versionCode`、发布日期或发布渠道绕过。
2. beta 用户必须能升级到对应的正式版。正式版发布时：
   - `versionName` 必须是不带后缀的 `X.Y.Z`；
   - Release 不得标记为 prerelease；
   - 更新清单中的版本号必须是 `X.Y.Z`，灰度比例 `rollout` 必须为 `100`；
   - 不得只发布正式版 APK 而不更新客户端使用的更新清单。

预览版不做灰度分桶。用户开启「接收预览版更新」后，所有预览版都会推送；更新清单中的 `rollout` 固定为 `100`，仅为兼容旧客户端保留。新客户端不得再按该字段过滤预览版。

`versionCode` 的要求：

- 必须单调不减。任何新安装包的 `versionCode` 不得小于它要覆盖的线上版本。
- 同一核心版本的正式版 `versionCode` 不得低于该版本所有 beta 包；建议在正式版发布前递增一次，确保严格大于 beta。
- `versionCode` 只用于 Android 安装与覆盖判断，不参与版本号语义比较。不得用增大 `versionCode` 的方式替代 `versionName` 递增。

### 1.5 版本号写在哪里

| 位置 | 要求 |
|---|---|
| `app/build.gradle.kts` 的 `defaultConfig.versionName` | 只写本次迭代的目标正式版核心号，例如 `5.2.2`。不得手写 `-beta.N`；开发版后缀由构建流程（`preview` 构建类型与 CI）统一生成。 |
| `app/build.gradle.kts` 的 `defaultConfig.versionCode` | 按发布节奏递增；发布前确认不低于线上任何可覆盖版本。 |
| `desktop/build.gradle.kts` 的 `desktopPackageVersion` | 必须与 `app` 的 `versionName` 逐字相同，且只能是 `x.y.z`（jpackage 限制）。`DesktopPackageVersionJvmTest` 会校验。 |
| `app/src/main/java/com/xjtu/toolbox/update/AppChangelog.kt` | 在 `ENTRIES` 顶部为核心版本 `X.Y.Z` 增加条目。beta 与正式版共用该条目；匹配逻辑会忽略 `-beta.N` 后缀。 |
| `README.md` 的版本徽章 | 发版后如有必要更新。 |
| CI 与发布脚本 | 不得硬编码版本号；必须从 `app/build.gradle.kts` 读取核心版本号，并据此生成 beta 后缀、tag 与更新清单。 |

发版前应当检查仓库中是否还有遗漏的版本字面量：

```bash
grep -rn "5\.[0-9]\+\.[0-9]\+" --include="*.kts" --include="*.kt" --include="*.md" . | grep -v "/build/"
```

把示例中的版本号换成当前版本；重点检查构建脚本、桌面端版本、更新日志与文档。

### 1.6 发版流程中的版本号操作

**开始一个新版本**

1. 确认当前最新正式版 tag 与版本号。
2. 把 `dev` 上 `app/build.gradle.kts` 的 `versionName` 提升为一个严格大于最新正式版的核心版本号，并同步递增 `versionCode`。
3. 在 `AppChangelog.kt` 顶部追加该核心版本的条目；内容可以先占位，发版前补全。
4. 同步修改 `desktop/build.gradle.kts` 的 `desktopPackageVersion`。
5. 提交并推送到 `dev`。默认会构建 `X.Y.Z-beta.1` 并发布预发布版；如果本次只想先进入 `dev`、暂不发预览，按下面的「发不发预览版」处理。

**日常开发**

- 功能分支从 `dev` 拉出，通过 PR 合回 `dev`。
- 每次合入 `dev` 默认会构建并发布一个新的 beta。beta 序号由构建流程按同一核心版本递增，开发人员不需要手动修改 `N`。
- 如果本次改动包含新的用户可见功能或修复，确认 `AppChangelog.kt` 中对应核心版本的条目已更新。
- 不得为了让版本号「看起来更新」而手动修改 beta 序号或核心版本号。

**发不发预览版**

推送前必须明确本次是否发布预览版：

1. 如果本次改动已经可以给预览用户测试：正常推送，CI 构建并发布 `X.Y.Z-beta.N`；迁移期内仍可能是 `X.Y.Z-dev.<run>`。
2. 如果本次改动还不适合发预览：在本次推送的最后一个提交（head commit）的提交信息中加入 `[skip preview]`。通过 PR 合并时，把标记写在 PR 标题里，确保它进入合并提交信息。CI 仍会执行版本号与更新日志校验、单元测试和 release 收缩检查，但不构建、不发布预览包。
3. 被跳过的改动不会丢失，也不占用 beta 序号。下一次发布预览时，CI 会以当时的 `dev` HEAD 构建，这些改动会一并进入 beta。
4. 如果只想补发当前 `dev` 上已经累积的改动，可以在 Actions 中手动运行 `Build & Release`，选择 `dev` 分支并勾选 `publish_preview`。手动运行不受 `[skip preview]` 影响。
5. 不得用跳过预览来绕过测试或版本号检查。该标记只控制「发不发」，不控制「测不测」。

**推送前检查清单**

- [ ] 本地相关单元测试已通过；涉及构建配置时，至少跑过一次 `assemblePreview` 或 `minifyReleaseWithR8`。
- [ ] 本次是否发布预览版已经确定。
- [ ] 如果发布：核心版本号严格大于最新正式版，`AppChangelog.kt` 有对应条目，`desktopPackageVersion` 已同步。
- [ ] 如果不发布：`[skip preview]` 已写入 head commit 或 PR 标题，并确认这些改动可以由下一次 beta 一并发布。
- [ ] 推送后检查 Actions 结果：预览版已发布，或按预期跳过。

**发布正式版**

1. 确认 `AppChangelog.kt` 的核心版本条目完整，`issues` 只写发版时仍然存在的问题。
2. 确认 `versionCode` 不低于该核心版本的所有 beta 包。
3. 通过 PR 将 `dev` 合并到 `main`；不要在这一步修改核心版本号。
4. CI 在 `main` 上构建 `X.Y.Z` 正式版，创建正式 Release 并更新更新清单。
5. 正式版发布后，立即在 `dev` 上把核心版本号提升到下一个版本，并递增 `versionCode`（为 `main` 上的热修复预留空间），同时在 `AppChangelog.kt` 顶部追加新核心版本的占位条目。
   - 如果忘记这一步，下一次 `dev` 构建的版本号将不大于最新正式版，CI 应当拒绝发布，见 1.7。

**热修复**

- 从 `main` 拉出修复分支，递增 `PATCH` 发布新的正式版，例如 `5.1.3`；不要使用 `5.1.2.1`。
- 修复发布后，把 `main` 合并回 `dev`。解决冲突时保留 `dev` 上更高的核心版本号与对应的 `versionCode`，不要把 `dev` 降回热修复版本。

### 1.7 校验与失败处理

以下情况必须在 CI 或发布检查中直接失败，不得依赖人工记忆：

- `dev` 的核心版本号不大于当前最新正式版；
- `versionName` 带有非 `beta` 的预发布标识，或 beta 序号格式不合法；
- `AppChangelog.kt` 缺少当前核心版本的条目；
- `desktopPackageVersion` 与 `app` 的 `versionName` 不一致；
- 正式版的 `versionCode` 低于同核心版本的 beta 包；
- 带 `[skip preview]` 的推送跳过了单元测试、版本号校验或更新日志校验。该标记只允许跳过预览包的构建与发布。

### 1.8 从 `-dev.N` 迁移

版本号条款自本文件合并后的下一个核心版本起强制执行；当前正在进行的核心版本按本节顺序收尾。

无论使用 `-dev.N` 还是 `-beta.N`，`[skip preview]` 的语义相同：只跳过预览包的构建与发布，不跳过校验，也不占用序号。

在本规范生效前，预览包的版本号形如 `X.Y.Z-dev.<CI 运行号>`。运行号跨版本持续累加，正是本规范要废除的写法。

迁移时需要注意版本排序：字符串比较中 `beta` 小于 `dev`，因此已经安装 `X.Y.Z-dev.N` 的用户不会把 `X.Y.Z-beta.1` 视为更新。推荐按以下顺序切换，避免预览用户被卡住：

1. 先完成当前核心版本的正式版发布，让现有预览用户升级到 `X.Y.Z`；
2. 从下一个核心版本开始使用 `X.Y.Z-beta.1`；
3. 如果必须在当前核心版本中途切换，需要同时提供旧 `-dev.N` 用户的升级路径，或在发布说明中明确要求手动安装新 beta。

迁移完成后，`-dev.N` 只作为历史版本存在，不再产生新的构建。

---

## 2. 分支与发布

| 分支 | 用途 | CI 行为 |
|---|---|---|
| `main` | 正式发布分支 | 构建正式版，创建 `vX.Y.Z` Release，更新正式渠道 |
| `dev` | 日常集成分支 | 默认构建并发布预览版；head commit 带 `[skip preview]` 时只跑检查，不发布 |
| `feat/*`、`fix/*`、`chore/*` 等 | 功能与修复分支 | 通过 PR 合回 `dev` |

- 功能与修复分支从 `dev` 拉出，默认合回 `dev`；只有发版时才把 `dev` 合入 `main`。
- 推送到 `main` 后，CI 用签名密钥构建 Release APK，并按 `versionName` 创建或更新对应的 GitHub / Gitee Release；Release 说明由 `AppChangelog.kt` 自动生成，其中 `issues` 字段写发版时仍存在的问题。
- 校园系统临时故障或维护公告：对 `announce` 分支的 `bulletin.json` 提 PR，或开一个「校园系统状态」Issue。
- 预览版的发与不发由 head commit 的 `[skip preview]` 或手动触发时的 `publish_preview` 输入决定；不要通过删除或修改已有 Release 来模拟跳过。
- 不得直接向 `main` 推送日常开发提交；紧急热修复也应当通过分支与 PR 完成。
- 不得手动创建或删除 CI 生成的 Release/tag，不得修改其版本号或安装包附件；发布记录的止血操作按预览渠道文档执行。
- 预览渠道的止血与发布细节见 [`docs/preview-channel.md`](docs/preview-channel.md)。该文档中的版本号示例如与本文冲突，以本文为准。

## 3. 提交与 PR

- 提交信息使用 Conventional Commits 风格：`feat(scope): ...`、`fix(scope): ...`、`refactor(scope): ...`、`chore(scope): ...`、`docs: ...`、`test: ...`、`ci: ...`。
- `scope` 使用模块名或功能名，例如 `home`、`schedule`、`update`、`ci`；没有明确范围时可以省略。
- 一个提交只做一件事。格式化、重命名与行为修改尽量分开，便于 review 和回滚。
- PR 描述应当说明：改了什么、为什么改、如何验证（命令、测试结果、必要的截图或录屏）。
- 涉及 UI 的改动应当附截图或录屏；涉及更新与发版的改动应当说明版本号与升级路径。
- 不得在提交、Issue、PR 或截图中包含账号、密码、Cookie、API Key、签名口令或其他凭据。

## 4. 构建、测试与模块边界

- 本地环境：JDK 21、Android SDK 37。构建命令见 [`README.md`](README.md)。
- 提交 PR 前至少运行与改动相关的单元测试；App 模块的基线命令是：

  ```bash
  ./gradlew :app:testDebugUnitTest
  ```

- CI 会运行 `core-gate`：`:core` 与 `:web` 必须能编译到 wasmJs，`:shared:*`、`:feature:*`、`:data`、`:desktop` 的测试也要通过。不要把 Android/JVM 专属依赖带进 `:core` 的 `commonMain`。
- 修改桌面端时运行 `./gradlew :desktop:test`；涉及安装包时确认 `desktopPackageVersion` 已同步。
- 不得提交构建产物、APK/AAB、`build/` 目录、签名文件或本地配置。

## 5. 凭据与本地文件

以下文件已被 `.gitignore` 忽略，只能存在于本地或 CI Secrets，不得提交：

- `release.jks`、`keystore.properties`；
- `feedback.properties`；
- `leaderboard.properties`；
- `server/leaderboard/aliyun.env`、`server/leaderboard/banned.txt`；
- `local.properties`。

需要新增本地配置时，应当同时更新 `.gitignore`，并在文档中说明配置项与获取方式；不要把真实凭据写进示例。

## 6. 规范维护

- 本文件是开发规范的唯一入口。新增规则直接追加到对应章节，不要在多个文档里维护互相矛盾的副本。
- 修改规则时，必须在同一个 PR 中更新负责执行该规则的 CI 检查、单元测试或脚本。规则与实现不一致时，以本文件为准，并尽快修正实现。
- 模块专属的设计与实现细节放在 `docs/` 下，由本文件引用，不在本文件中重复。
