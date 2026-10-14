# 外部审计复核 · 汇总索引与处置建议（36 条）

> **真源声明**：本文件是**汇总**，不是复核本身。每一条的代码证据、引用行号、复算过程与「最小复现路径」都在三份分报告里：
> `docs/audit-verify-1-correctness.md`（#01–#12）、`docs/audit-verify-2-security.md`（#13–#20）、`docs/audit-verify-3-rest.md`（#21–#36）。
> 本文件只做**索引、统计与处置建议**，不重新下结论；凡分报告没写的，这里标「报告未覆盖」/「无法判定」，不补猜。
>
> - 汇总口径真源：`docs/desktop-port-plan.md` §16（2026-10-14 会议；**D7-口径 = 丙「逐像素 / 逐状态全等」**，且**基准是 Android 现有行为**）。
> - 复核基线：`refactor/kmp-multiplatform` @ `5e9b2e53`；审计原文副本 `/tmp/audit-20261014.md`（1423 行，36 条）。
> - 本次汇总**未改任何已有文件**（只新建本文件），**未跑 gradle / 任何构建或测试**，未 `git add/commit/push`。
> - 文中不含任何真实凭据、令牌、学号或姓名；示例值一律编造。

---

## 0. 读表前必须知道的三件事

### 0.1 审计的路径/行号基线**不是 HEAD**

三份分报告各自独立地得出了同一结论：审计大量引用的路径/行号来自 **KMP 重构之前**的单模块 Android 树（`app/src/main/java/...`），而 HEAD 已按模块拆分（`app/src/main/java/...` → `core|data/src/commonMain/kotlin/...`）。

- 分报告 1 §0.1：审计点名的 14 个文件里 **12 个**已搬家（`app/.../score/`、`app/.../agent/`、`app/.../pay/`、`app/.../auth/` → `core/`/`data/`）；仍在 `:app` 的只有 `CampusProbe.kt`、`HeadlessSessions.kt`、`DownloadTaskEntity.kt`（本就是审计指的那个路径）。
- 分报告 3 §0.1：拿审计里最可核对的三处行号比对，**逐字落在 `origin/main`**（`HomeTab.kt:124-134`、`CommunityScreen.kt:35-36,53-59`），而 HEAD 上这些文件已搬走。
- ⇒ 读总表时请把「审计描述的旧位置」与「HEAD 的实际位置」分开看：**总表的证据列一律给 HEAD 的路径**；只有分报告里明确标注「origin/main」的才是旧基线的行号。

### 0.2 三份分报告之间的口径差异（如实指出）

三份分报告覆盖的条目互不重叠，**结论上没有互相矛盾**，但有三处口径差异值得记录：

1. **对「引文不存在」的归因强度不同**：分报告 1 认为 #01 那段 `scoreToGpaFromString` 与 #09 的 `MIGRATION_7_8`「在本仓任何 ref 都不存在 ⇒ 更像审计自身的引文错误，而不是旧提交」；分报告 3 则把基线判为「≈ `origin/main`/`origin/dev`」。两者可兼容（多数行号对得上 `origin/main`，少数引文在任何 ref 都搜不到），汇总里按**证据强度**分别标注：`git log --all -S` 零命中的按「引文不可采信」读，能对上 `origin/main` 行号的按「旧基线」读。
2. **「是否改变 Android 可见行为」的问法不同**：分报告 1、3 是直陈式（「D7：是否改变…」），分报告 2 是条件式（「**若修**，是否改变…」）。总表这一列按各自原文填；分报告 2 的条目请读作「**按我方建议的修法**」。
3. **一处分报告内部的小张力**：#08 的 D7 写「**改变**」，同一段又解释「重进会话时本来就已被 `imageFileExists` 过滤掉，**屏上无差异**」。总表按原文填「改变（屏上无差异）」；分组时它落在②，但实操上接近①。
4. **一处覆盖缺口**：审计 #10 的标题是「`JudgeApi`（本科评教）**与 `LmsApi`** 的实例级缓存……」，分报告 1 只核了 `JudgeApi` 那一半，`LmsApi` 半条**报告未覆盖**（总表已注明）。

### 0.3 「是否改变 Android 可见行为」这一列怎么读

按 §16 的 D7-口径，这一列是分组的核心维度，读法：

- **改变** = 按分报告给出的修法去修，Android 用户**能看到**不同的东西（数值、文案、列表内容、动效、是否弹窗、是否多一条提醒…）。⇒ 必须回到用户面前拍板（§16.4 第一条：丙全等 × C1 红线只允许各端**向 Android 现状对齐**）。
- **不改变** = 修法只影响开发者可见面（日志、CI、文件清理时机、契约接线）或只影响**非法/错误**状态的呈现。
- 「本轮不动 ⇒ 不变」（#30）与「修复后向 Android 应有行为对齐」（#26）是两个特殊读法，表里已写明。

---

## 1. 总表（36 条）

列说明：**审计的判定/严重度** = 审计原文的「认定存在」+ 它自报的严重度（审计原文只有类别/严重度，没有我方这种「成立/部分成立」标签）；**复核判定**与**重判严重度**逐字取自三份分报告（含括注）；**改可见行为**按 §0.3 读。

| # | 类别 | 审计的判定/严重度 | 复核判定 | 重判严重度 | 改 Android 可见行为 | 一行结论 |
|---|---|---|---|---|---|---|
| #01 | Bug | 认定存在 · **致命** | 部分成立 | 中 | 改变 | 清洗正则确实吃掉小数点（`"59.5"→595→4.3`，复核员用 JVM 复算过）；但审计引的映射表/行号全错（`良/中/及格` 在 HEAD **有**映射），且「上游会下发小数成绩」本仓无任何证据 |
| #02 | Bug / 安全 | 认定存在 · 高 | 成立 | **高** | 改变 | 切账号不重置 `AgentViewModel`（`bind()` 只跑一次、`accountId` 变化只刷 config），而落盘目录每次现取 `safeSuffix()` ⇒ A 的聊天记录＋身份画像被写进 B 的 `agent_sessions_<B>/` |
| #03 | Bug | 认定存在 · 高 | 成立 | 中 | 改变 | `ssoErrorMessage` 把「HTTP 401」原样塞进 `LoginResult.FAIL`，下游 `isCredentialFailure` 真有子串 `"401"` ⇒ 目标站 401 被判成「密码错」并全局熔断（同函数 `ssoFailure` 一路作者已规避） |
| #04 | Bug | 认定存在 · 高 | 成立 | 中 | 改变 | 明文 HTTP 探针 + `reach()` 只看 `code < 500` 是「Captive Portal 必误判成校内强结论」的完整成因；但审计引的 `HttpClients.probe` **不存在**，且误判有 10 分钟上界＋设置里的逃生门 |
| #05 | Bug | 认定存在 · 高 | 成立 | 中 | 改变 | 后台临时 `SessionManager` 默认 `NORMAL`、不读 `networkMode`/不探测 ⇒ 进程被杀后图书馆提醒在校外必失败；「100% 失败」夸大（回到校园网/前台起来后重试能送达） |
| #06 | Bug | 认定存在 · 中 | 成立 | 低 | 改变 | 注释两处承诺「退出 Composable 时 `DisposableEffect` flush」，代码里**一个都没有**，`scheduleSave` 的 500ms 随组合一起被取消 ⇒ 粘完 Key 立刻返回会丢 |
| #07 | Bug | 认定存在 · 中 | 成立 | 中 | 改变 | `keyValueStore("payment_code")` 无账号后缀，12 秒出码循环把（属于 A 的）券勾选每次同步给上游；三端同一存储名，加后缀要三端同改＋老用户重勾一次 |
| #08 | Bug / 性能 | 认定存在 · 中 | 成立 | 低 | 改变（屏上无差异） | 删会话/清缓存都不删 `filesDir/agent_images<后缀>/`，全仓只有「发送前撤图」一处删；审计引的 `AgentVision.deleteAll` 在 HEAD 已不存在 |
| #09 | Bug / 架构 | 认定存在 · 中 | 成立 | 低 | 改变 | `download_tasks` 无 `accountId`、DAO 全表、账号清理不碰它；但审计引的 `MIGRATION_7_8`/`jiaocai1_shelf` 加列/`clearAccountRoomData` 在 HEAD **全不存在** |
| #10 | Bug | 认定存在 · 低 | 成立 | 低 | 基本不改变 | `catch` 里也置 `appInitialized = true`（兄弟实现 `SchoolCourseApi` 不置，失败下次还会重试）；影响限于当前页面实例。**报告未覆盖**审计标题里的 `LmsApi` 半条 |
| #11 | Bug | 认定存在 · 低 | 部分成立 | 低 | 改变 | 「只认 `options.first() == "优秀"`」在升序量表下确实不命中；但「第一道题固定改『良好』」是**测试钉住 + UI 明示**的既有行为，属产品口径不是 Bug |
| #12 | Bug | 认定存在 · 低 | 成立 | 低 | 改变 | 注释写「字符**全**落 0x80..0xFF 才重解」、代码是 `none {}` ⇒ 正常 UTF-8 中文串含 `·`/`×`/`é` 时整串被 ISO-8859-1→GBK 毁掉（复核员复算 `"兴庆校区·东南门" → "????\uFFFD???"`） |
| #13 | 安全 | 认定存在 · **致命** | 部分成立 | **高** | 部分（深链降级不改；图片改「点击加载」会改） | 外部深链/Intent 确实**无确认**自动发送 + 写工具无确认 + Markdown 图片是真外带通道；但「致命」过重（要模型配合、图片只认 `https://`），工具 **27 不是 29** |
| #14 | 安全 / Bug | 认定存在 · 高 | 成立 | **高** | 不改变功能行为 | WebView `CookieManager` 里的 CAS `TGC` 等**从不清理**（`removeAllCookies` 全仓 0 命中），切账号只清 WebStorage、登出/删账号连 WebStorage 都不清 ⇒ 登出后仍能免密成 A |
| #15 | 安全 | 认定存在 · 高 | 部分成立 | 中 | 会改变联网拓扑 | HEAD **无任何硬编码凭据**（走 CI env + 已 gitignore 的 `feedback.properties`；`propOrEnv` 在本仓从未存在）；但「secret 进 APK、反编译换 token 读回复表」这条架构链仍成立 |
| #16 | 安全 / 隐私 | 认定存在 · 高 | 部分成立 | 中 | 基本不改变（改 `allowBackup=false` / 整库排除会变） | 排除清单的名字与审计引文**完全不同**，且 `agent_sessions*` 其实**已排除**（审计说反了）；但仍漏排 `campus_probe`/`xjtu_toolbox.db`/`agent_images`/`agent_memory`/`inbox`；「登出不清 `campus_probe`」**不成立** |
| #17 | 安全 | 认定存在 · 中 | 部分成立 | 低 | 不改变（按推荐修法） | 「`followRedirects(true)` 覆盖公网策略」**不成立**（策略本体是建连时 `GuardedSocket`；`PublicOnlyDns` 全历史 0 命中）；但图片客户端确实没挂策略 ⇒ 盲 SSRF |
| #18 | 安全 / 性能 | 认定存在 · 中 | 部分成立 | 低 | 不改变 | 「只剥 `Log.d`/`Log.v`」、`SiteSession` 两处认证失败日志未脱敏、学号明文日志都属实；但审计举的「`LmsApi` 打印带签名 token 的下载 URL」在 HEAD **不成立** |
| #19 | 安全 / 文案 / 产品 | 认定存在 · 中 | 部分成立 | 低 | 只改文案则无变化 | 「只存在这台设备上，**不上传**」与「整段偏好进 system prompt 发给第三方模型」冲突、README「工具可单独关掉」与已删开关冲突；审计引的文案与 `add_todo`/`complete_todo` 都不存在 |
| #20 | 安全 / 工程 | 认定存在 · 中 | 成立 | 中 | 不改变正常行为 | 文件名直接拼 `tag_name` 派生的 `info.version`，下载完只查前两字节是否 `PK`，不校验摘要/包名/签名（行号 `237-272` → HEAD `243/263-270`） |
| #21 | 架构 / 性能 / AI | 认定存在 · 中 | 部分成立 | 中 | 会 | 「历史不压缩 / 整段重发 / 整段落盘 / 落盘在主线程」四条都真；但「`ContextBudget.fit` 结果被丢弃」「`llmHistory.drop(1)`」两条证据在本仓**任何 ref 都不存在** |
| #22 | AI / 性能 | 认定存在 · 中 | 部分成立 | 低 | 会 | 工具**全量**塞（与登录态无关；27 不是 29）与同回合 `tool_calls` **串行**都真；「3,500+ tokens」「可提速 50%~70%」本仓无依据 |
| #23 | AI / Bug | 认定存在 · 低 | **不成立**（证据系伪造；残留一条设计取舍） | 建议 | 会（故不建议改） | `AgentPrompt.build(null, …)` 在任何 ref 都不存在；HEAD 是「一段对话只生成一次 system prompt」的**写进 KDoc 的故意取舍**，不是竞态 bug |
| #24 | 性能 / 架构 | 认定存在 · 中 | 部分成立 | 中 | 会 | 无前后台感知、进程活着就 10 分钟一轮、默认开、设置里**没有 UI 开关**（`KeepAlivePrefs` 字段有但全仓没人写）—— 全真；审计引的符号名在 HEAD 已改 |
| #25 | 性能 | 认定存在 · 中 | 成立（**修法依据不成立**） | 中 | 基本不变 | `courses.map{}` 串行 ＋ 每条未交作业一次 `getUserSubmitCount` 串行是真 N+1；但「上游有聚合接口 `getTodos()`」在仓库里**无任何依据** |
| #26 | Bug / 架构 | 认定存在 · 中 | 成立（**修法已过期**） | 中 | 会（向 Android 应有行为对齐） | 兄弟小组件都恢复 `AccountContext`、只有待办小组件不恢复 ⇒ 冷启动读 `default` 命名空间、显示「暂无待办」；审计给的 `InboxStore.init(context)` 在 HEAD **已不存在** |
| #27 | 架构 / 工程 | 认定存在 · 中 | 部分成立 | 低 | 不变 | 「多超大文件」只剩 2 个 >1500 行（2408/1561）；3019 行、1763/1783/1899/1472、537 文件、8 个测试文件、1.5% 覆盖率**全部对不上**，且 `LeaveScreen.kt` 在本仓任何 ref 都不存在 |
| #28 | 性能 / UI | 认定存在 · 低 | 部分成立 | 低 | 会 | 首屏 6 秒自定义着色器是真的（`MESH_FRAME_MS=33` ⇒ ~30fps，**不是**「每帧」）；「叠加 11 个站点会话恢复」**不实** —— 冷启动只登 `jwxt` 一个站（注册 18 个） |
| #29 | 产品 / UX | 认定存在 · 中 | 成立 | 中 | 会 | 读帖也要 GitHub 登录（Discussions 只能走 GraphQL，GraphQL 必须带 token，App 不内置 token）；审计点的 `CommunityScreen.kt:207` 实为 **221** |
| #30 | UX / 产品 | 认定存在 · 中 | 成立 | **高**（阻塞写路径对齐决策） | 本轮不动 ⇒ 不变 | 事实全对（业务 `:8080`、支付页 `:80`、两套是不同部署、`去支付` = 内置浏览器先过 OAuth 再跳支付页）；`:core` 的 serve 形态下 `paymentUrl`/`browserLoginUrl` **都是空串** ⇒ 按钮点了没动作 |
| #31 | UI / UX（无障碍） | 认定存在 · 低 | 成立 | 低 | 会 | 101 处 `contentDescription = null`、0 处 `minimumInteractiveComponentSize()`、`size(15–18.dp)` 与 `minHeight = 32.dp` 都真；审计点的 `CommunityScreen.kt:207` 实为 **221** |
| #32 | 隐私 / 产品 | 认定存在 · 低 | 成立 | 中（**比审计高**） | 会 | 昵称默认填真名 + `Dimensions.identity` 默认 `true` 把班级/省份/专业编进码；但学号不在码里，泄露面比审计说的小 —— 问题是「静默默认」 |
| #33 | 工程 / 安全 | 认定存在 · 高 | 成立 | **高** | 会（钉住**当前解析版本**则不变） | `miuix = "latest.release"` 逐字属实、`:app` 依赖全绑在它上；仓库**有**拒预发布 + 30 分钟解析缓存两条缓解，但不解决**跨时间**可重现性 |
| #34 | 工程 / 安全 | 认定存在 · 中 | 部分成立 | 中 | 不变 | heredoc 内联 `${{ }}`、token 拼进 git remote URL（还拼进 curl query/JSON body）都真；「CI 完全不跑单测」「没有 PR 检查工作流」**不成立**（`build.yml:46` 跑 `:app:testDebugUnitTest`，`core-gate.yml` 由 PR 触发） |
| #35 | Bug / 架构（服务端） | 认定存在 · 中 | **无法判定** | —（本仓无可修） | 不适用 | `server/leaderboard/index.js`、`nginx-Snippet.conf`、`LeaderboardApi.kt` 在本仓**任何分支都不存在**（`server/` 现在是 Kotlin `:server` 模块）⇒ 服务端可能已迁出本仓 |
| #36 | 工程 / 可持续 | 认定存在 · 建议 | **不成立**（残留半条） | 建议 | 不变 | `ISSUE_TEMPLATE/{bug,feature,announce,config}.yml` + `pull_request_template.md` + `PULL_REQUEST_TEMPLATE/announce.md` **都在**（`origin/main` 上也在）；真缺的只有 `CONTRIBUTING.md` / `SECURITY.md` |

---

## 2. 「审计不实之处」逐条清单

### 2.1 引文/符号/文件在**本仓任何 ref 都不存在**（复核手段：`git log --all -S`、`git ls-tree -r --name-only <分支>`、全仓 `grep`）

| # | 审计的断言（引文） | 复核核到的 | 核对手段（复核员用的） |
|---|---|---|---|
| #01 | `scoreToGpaFromString` 里 `val raw = grade.trim()` / `if (raw.isEmpty())` / 单档 `良 → 3.3` / `something else -> null` 收尾 | 该实现与那几行映射在本仓**任何一次提交**都不存在；HEAD 与 `origin/main` 都是「11 级 + `通过`/`不通过 → null」 | `git log --all --oneline -S 'g == "良" -> 3.3'`（0）、`-S 'g == "中" -> 2.3'`（0）、`-S 'g == "及格" \|\| g == "通过" -> 1.0'`（0）；对照 `-S 'g == "优" -> 4.0'`（2） |
| #01 | 「`优秀/良好/中等/合格` 未映射 ⇒ 静默漏算」 | HEAD 的 `良/中/及格` **有**映射；只有双字那几种落 `else -> null`，且被一条测试**显式钉住**为「不参与加权」 | 读 `core/.../score/ScoreCalculator.kt:47-70` + `data/src/jvmTest/.../ScoreReportApiJvmTest.kt:38-43` |
| #04 | 探针用 `HttpClients.probe`（审计写 `HttpClients.kt:99`） | HEAD 的 `HttpClients` 只有 `base`，探针 client 是 `CampusProbe` 自己 `new` 的 | `grep -n 'val ' data/.../network/HttpClients.kt`；读 `app/.../auth/CampusProbe.kt:114-121` |
| #08 | `AgentVision.deleteAll(paths)`（审计写 `AgentVision.kt:139-142`） | HEAD 的 `AgentVision` 只剩 `supportsVision`/`userContent`/`pruneOldImages`，删文件走宿主缝 `AgentHost.deleteImageFiles` | `grep -n 'deleteAll' core/.../agent/AgentVision.kt`（0）；`core/.../agent/AgentHost.kt:146` |
| #09 | `MIGRATION_7_8`；`jiaocai1_shelf` 加 `accountId` 列；`clearAccountRoomData` / `deleteAllForAccount` | 三者在 HEAD 与所有分支都不存在（版本最多到 `6→7`） | `for b in main origin/main origin/dev gitea-local/main; do git show $b:app/.../AppDatabase.kt \| grep -c MIGRATION_7_8; done`（全 0）；全仓 `grep -n 'deleteAllForAccount'`（0） |
| #13 / #19 / #22 | 「27~29 个工具」「29 个工具的完整 Schema」「6 个写工具」「`add_todo` / `complete_todo`」 | HEAD 与 `origin/main` 都是 **27** 个工具；`add_todo`/`complete_todo` 全仓 0 命中；写工具是 4 个不是 6 个 | `grep -c 'arr.add(agentTool(' core/.../AgentToolCatalog.kt` = 27；同法在 `origin/main` 数 `arr.add(tool(` = 27 |
| #15 | 源码里硬编码 `FEEDBACK_APP_ID`/`FEEDBACK_APP_SECRET`（`cli_…`），经 `propOrEnv(...)` 读取；`fetchAll(token, ids.toSet(), useFilter = false)` 无条件全表遍历 | 本仓**没有任何** `cli_*` 字面量、从来没有 `propOrEnv`；HEAD 恰恰是**不硬编码**（CI env + gitignore 的 `feedback.properties`），`replies()` 按工单号 `contains` 过滤 | `grep -rIno "cli_[A-Za-z0-9]*"`（0）；`git log --all -S "cli_a954f12e30381bd6"`（0）；`git log -S "FEEDBACK_APP_ID"`（命中的都是「改成注入式」的提交） |
| #16 | 排除清单为 `xjtu_secure_prefs*` / `cookie_jar*` / `session_snapshots*` / `data_cache*` / `credential_store_fallback*`；`agent_sessions*` **未**排除；「登出/删账号不清 `campus_probe` 的 JWT」；`github_community_fallback.xml` 明文长期留存 | 那些路径在本仓当前不存在；`agent_sessions*`（`file` 域）**是排除了的**（审计说反）；`campus_probe` 有 `onAccountSwitched` 钩子清（`ywtbToken = null`）；`SecurePrefs` 迁移完即 `deleteSharedPreferences(legacyFallbackName)` | 读 `app/src/main/res/xml/data_extraction_rules.xml:1-32`；`data/.../auth/SessionManager.kt:462-471,496-499` + `app/.../auth/AppLoginState.kt:373` + `CampusProbe.kt:74-80`；`SecurePrefs.kt:74-93` |
| #17 | 策略写成 `followRedirects(false)` / `followSslRedirects(false)` / `dns(PublicOnlyDns)`，被 `AgentTool.kt:98` 的 `.followRedirects(true)` 覆盖回 true | 策略本体是**建连时**的 `GuardedSocket`（不是重定向开关）；`PublicOnlyDns` 在本仓**全部历史零命中**；`followRedirects(true)` 覆盖的是一个不存在的标志位 | `grep -rn "PublicOnlyDns" --all`（0）；`git log --all -S "followSslRedirects(false)" -- AgentWeb.kt`（0）；读 `app/.../agent/AgentWeb.kt:136-145,175-176` |
| #17 / #13 | 举例 `![http://127.0.0.1:xxxx/…]` 被自动请求（回环/明文） | Markdown 只把 `![…](https://…)` 认成图，`load()` 还要求 `url.startsWith("https://")` ⇒ 该例子不成立 | `core/.../agent/AgentMarkdown.kt:174`；`app/.../agent/AndroidAgentHost.kt:424-436` |
| #18 | `LmsApi` 用 `Log.w`/`Log.e` 打印**带 token/timestamp 签名的**完整下载 URL | HEAD 打的是校内无签名的 `blob` 地址（LMS 鉴权走 cookie，URL 本身无签名） | `data/.../lms/LmsApi.kt:257-262,268-299,338-342`；全仓 `grep 'Log.(i\|w\|e)(… token'` 只剩 4 处且不含 token 值 |
| #19 | 「对话中说的习惯会自动记下」等文案；`AgentConfigPanel.kt:346-350`；`AgentTool.kt:88`「全部只读」 | 文案与行号在 HEAD 都不是那样（卡片移到 `:354-359` 且空偏好时不显示）；「全部只读」这句在同文件 `:63` 仍有，但同段 `:70-72` 已自陈例外 | 读 `core/.../agent/AgentConfigPanel.kt:347-359`；`app/.../agent/AgentTool.kt:63,70-72` |
| #21 | `ContextBudget.fit(history, config.contextTokens)` 的结果被丢弃；`buildRequestBody`；`persist()` 用 `llmHistory.drop(1).map { JSONObject(it.toString()) }` | 三个符号在本仓**任何 ref 都不存在**；HEAD 的 `ContextBudget` 是「按数据源限长」的 `clip()/payload()`（全 76 行），压根没有 `fit(history, tokens)` 这个 API | `git log --all --oneline -S "ContextBudget.fit"`（空）、`-S "buildRequestBody"`（空）、`-S "contextTokens"`（空）、`-S "llmHistory.drop(1)"`（空） |
| #23 | `AgentPrompt.build(null, …)` 把 `loginState` 硬编码成 `null` ⇒ 历史会话 system prompt 里没有姓名/学号/年级，且 `llmHistory[0]` 永不刷新 | 该调用在任何 ref 都不存在；`AgentPrompt.build` 的签名**从来没有** `loginState` 形参；`loadSession` 现名 `switchSession` | `git log --all -S "AgentPrompt.build(null"`（空）；读 `core/.../agent/AgentPrompt.kt:16-20`、`AgentViewModel.kt:398-402,416-445` |
| #25 | 「上游其实自带聚合待办接口 `LmsApi.getTodos()`（`LmsApi.kt:194`），改一次请求就行」 | `getTodos` / `api/todos` 在本仓库没有任何依据 | `git log --all -S "getTodos"`（空）、`-S "api/todos"`（空） |
| #26 | 修法 `InboxStore.init(context)` | 该 API 在 HEAD **已不存在**（改用 `keyValueStore` 平台缝）；现象本身成立 | `grep -rn "InboxStore.init"`（0）；`core/src/androidMain/.../KeyValueStore.android.kt:12-19` |
| #27 | `AgentTool.kt` 3,019 行；`VenueScreen` 1,899；`ScheduleScreen` 1,783；`LibraryScreen` 1,763；`LeaveScreen` 1,528；`AppChangelog` 1,472；`app/src/main` 537 个源文件；`app/src/test` **8 个**测试文件；覆盖率 **约 1.5%**；点名的 8 个测试文件 | 实测：`AgentTool.kt` **2408**（最近 30 次提交区间 2907–2954，**没有一次是 3019**）、`VenueScreen` 1110、`ScheduleScreen` 1380、`LibraryScreen` 861、`AppChangelog` 650；`LeaveScreen.kt` **在本仓任何 ref 都不存在**；`app/src/main` **138 个 `.kt`**（219 个全类型文件）；`app/src/test` **76 个**；覆盖率按四种口径是 **20.7% / 22.7% / 30.7% / 52.1%**（1.5% = 8/537，两个因子都取错）；点名的 8 个测试里 **4 个从不存在**（`InboxRulesTest`、`SelfTodoRulesTest`、`NetCodecTest`、`GameTests`） | `find app/src/main -name '*.kt' \| wc -l`；`find . -name '*.kt' \| grep -v /build/ … \| xargs wc -l \| sort -rn \| head`；`git log --all --diff-filter=A -- '*LeaveScreen.kt'`（空）、同法查那 4 个测试名（全空） |
| #28 | 「冷启动叠加 **11 个**站点会话恢复」「前 6 秒**每帧**触发」 | 冷启动只登 `jwxt` **一个**站（注释：「冷启动不做别的主动认证」），站点注册总数是 **18**；着色器是 **33ms 定时器**（~30fps）不是每 vsync | 读 `app/.../main/AppRoot.kt:301-320`；`grep -c "register(com.xjtu.toolbox.auth" app/.../AppLoginState.kt` = 18；`core/androidMain/.../MeshBackground.android.kt:102` |
| #34 | `build.yml` **只跑 `assembleRelease`**、**完全不跑单元测试**；「全仓没有 PR/push 检查工作流」 | `build.yml:46` 明确跑 `:app:testDebugUnitTest`（注释「单测不过就不出包」，`git log -S testDebugUnitTest` → `b7762420`），且有 `pull_request` 触发的 `core-gate.yml` | 读 `.github/workflows/build.yml:44-46`、`.github/workflows/core-gate.yml:14-16,60-80` |
| #35 | `server/leaderboard/index.js`（`clientIp()`）、`server/leaderboard/deploy/nginx-Snippet.conf`、`LeaderboardApi.kt`、`zyxf.top` 上的 Node 服务 | 这些文件在本仓**任何分支都不存在**；客户端也没有任何「排行榜」代码（只有一句注释「没有排行榜」） | `ls server/`；`git ls-tree -r --name-only origin/main origin/dev origin/announce refactor/kmp-multiplatform \| grep -c leaderboard` = 0 0 0 0；`git log --all -S clientIp`（空）；`grep -rn "排行榜" --include=*.kt` |
| #36 | `.github/` 只有 `workflows/build.yml` 与 `workflows/sync-announce.yml`，**没有** Issue/PR 模板 | 模板**都在**（HEAD 与 `origin/main` 都在）：`ISSUE_TEMPLATE/{announce,bug,config,feature}.yml`、`pull_request_template.md`、`PULL_REQUEST_TEMPLATE/announce.md`；且已有安全引导 | `find .github -type f`；`git ls-tree -r --name-only origin/main \| grep '^\.github'` |
| 审计 §四 | 自称「**服务端（100%）**：`server/leaderboard/index.js`、`moderation.js`、`deploy/*`」已精读 | 该目录不存在 ⇒ 这条**覆盖率/精读声明本身不可采信**；同节的「545 个源文件（12.3 万行）」也与 HEAD（861 生产 `.kt` / 163,798 行）和 `origin/main`（532 个 `.kt`）都对不上 | 同 #35 的手段；§0.3 A–E 的计数命令 |

### 2.2 结论方向成立、但**证据/数字/行号是错的**（这些条目按「部分成立」记，别当审计铁证）

- **#02**：接口名与行号漂移（`sessionStore` → `host.sessions()`；`bind` 在 `:201-208` 而非审计写的 `:202-208`）。
- **#06**：`saveDebounced` 在 HEAD 叫 `scheduleSave`（`AgentConfigPanel.kt:96`），审计的行号 `72-97`/`383-385` 已漂移。
- **#07**：审计引的 `PAYMENT_PREFS` 常量、`PaymentCodeScreen.kt:57-59,103-104,177-180`、`AccountManager.clearAccountPrefs` 在 HEAD 都已改名/搬走（现为 `PaymentSelectionStore` + `keyValueStore` + `wipeAccountFiles`）。
- **#10**：`appInitialized` 那段实为 `:157,164-182`；「重登后不重预热」半条**没有上游证据**，分报告标为推断。
- **#13**：`intent://…S.extra_launch_prompt` 的浏览器可用性描述不准（复核员的结论是：**任何本机第三方 App 直接 `startActivity` 带 extras 就能触发，一次点击都不需要** —— 比审计写的更严重）；全仓无「工具级确认条/审批」实现，`awaiting_user_confirm` 只是文案。
- **#14**：`onCreateWindow` 的 `parent.loadUrl(u)` 缺 scheme 校验属实，但「`<a target=_blank href="javascript:…">` 一定在父页执行」**证据不足**（随 WebView 版本而异，未实机）⇒ 记为附带风险。
- **#20**：行号 `237-272` → HEAD `243`/`263-270`；文件里确实出现过 `MessageDigest.getInstance("SHA-256")`，但那是**灰度分桶**（`isRolloutHit`），与 APK 完整性无关（修的时候别误读成「已经校验了摘要」）。
- **#24**：审计引的符号名（`keepAliveJob` / `isEnabled(context)` / `sessionManager.warmUp(topKeys)`）在 HEAD 已改；结论成立。
- **#28 / #29 / #31**：行号 `CommunityScreen.kt:207` → 实为 **221**；`#29` 的「80% 大一新生没有 GitHub 账号」是未经验证的统计（复核员的判断不依赖它）。
- **#30**：技术事实全对，但「易超时丢单」是推断；审计给的修法（OkHttp 预热 80 端口 + 注入 `JSESSIONID`）**不可直接采信**（80 那套认不认 8080 的 `SESSION` 无依据，且凭空往 WebView 注入会话 cookie 是安全敏感动作）。
- **#32**：泄露面比审计描述的小（学号**不在**码里，省份是从学号派生）。
- **#33**：审计没提仓库已有的两条缓解（拒预发布 + 30 分钟解析缓存）—— 缓解不解决可重现性，但读原文时要知道它们存在。
- **#34**：`(a)(b)` 成立且比审计说的更广（token 还进 4 处 query/JSON/表单）；`(c)` 不成立。
- **审计的严重度刻度**：审计把 #01、#13 判「致命」、8 条判「高」；我方重判后无一「致命」，「高」只剩 5 条（见 §5）。

### 2.3 **无法核实**的断言（本仓拿不到上游/实机证据；不是我方判它错，而是「不能当既成事实」）

| # | 未证的断言 | 需要什么才能定 |
|---|---|---|
| #01 | 「上游会下发带小数点的成绩文本（如 `59.5`）」 | 一次真实的成绩页抓包，或补一条 `testkit` 夹具（本仓夹具全是整数/「优秀」/「通过」） |
| #03 | 「某个校内子系统在 SSO 重定向落地时恰好返回 401」 | 抓包或假上游加一条 401 路由 |
| #04 | `reach()` 在真实 Captive Portal / 透明代理下的行为 | 该网络环境手测（`reach()` **没有任何测试**） |
| #05 | 「WorkManager 重试能否在 25 分钟窗口内救回来」 | 实测时序 |
| #07 | 「上游对空集 voucher 是『一张不用』」 | 校园卡后端实证（代码注释是唯一依据） |
| #10 | 「jwapp 的 module session 是否绑 `JSESSIONID`、重登后是否真 404」 | 上游行为 |
| #11 | 「gste 存在**选项升序**的量表」「服务端是否真会拒全优」 | 上游问卷样本 |
| #12 | 「上游场馆数据里会含 `·`/`×`/`é`」 | 上游数据样本（本仓夹具里没有任何 `·`） |
| #13 | 「模型被注入后确实会输出独占一行的 `![…](https://…)`」；`intent://` 的浏览器策略 | 实机 `adb shell am start …`（已配 Key 的机器） |
| #14 | 「`javascript:` 链接在父页执行」 | 实机（随 WebView 版本） |
| #15 | 「secret 真的在 release APK 里」 | 反编译一个真实 release 包（只读+不构建前提下做不到） |
| #16 | 「哪些备份域真的被导出」 | `adb shell bmgr backupnow`（未做；报告只做静态比对，且明说没穷举 20+ 个 prefs 名） |
| #17 | 「https 起点 302 到 `http://127.0.0.1` 会被跟随（盲 SSRF）」 | 自建 https 端点 + 让回复里出现该图 |
| #18 | 「R8 是否消除 `Log.d` 的参数求值」 | 构建一次 release 再反编译字节码（禁止构建） |
| #21 / #22 | schema 的 token 数（本仓无 tokenizer ⇒ 只能估 3.0k–5.0k） | tokenizer + 实测 |
| #25 | 「上游有聚合接口」 | 上游实证 |
| #28 | 「首屏掉帧」 | 性能测量（无设备、未跑性能测试） |
| #30 | 「80 端口那套是否认 8080 的 `SESSION`」 | 在线探测 |
| #35 | 「线上 `zyxf.top` 那套 Node 服务是否有此 bug」 | 那个仓库（不在本仓） |

---

## 3. 处置分组

分组按「**主要下一步动作**」取（严重度 × 是否改变 Android 可见行为）；有跨组成分的条目已注明。四组相加 = 36。

### ① 立刻可修且不改可见行为（9 条）

| # | 重判 | 一句话理由 | 指向的代码路径 |
|---|---|---|---|
| #10 | 低 | 删掉 `catch` 里的 `appInitialized = true`（一行）；D7「基本不改变可见行为」 | `data/.../judge/JudgeApi.kt:180`（对照 `data/.../schedule/SchoolCourseApi.kt:62-74`） |
| #14 | 高 | 在 `clearWebViewStorage()` 补 `CookieManager.removeAllCookies` + `flush()`；D7「不改变功能行为」（唯一影响是失去**非法**的免密残留） | `app/.../account/AccountManager.kt:66-72`（`switchToLocked` 调用点 `:96-98`；`logoutCurrent` `:284-295`、`removeAccountLocked` `:245-278` 也各调一次） |
| #17 | 低 | 给图片客户端加 `.applyPublicNetworkPolicy()`；D7「不改变正常行为」（外网图片都是公网 https） | `app/.../agent/AndroidAgentHost.kt:424-436`（策略本体 `app/.../agent/AgentWeb.kt:175-176`） |
| #18 | 低 | 补 `.redactUrl()`/`.redactBody(160)`、学号打码；D7「不改变用户可见行为」（两种脱敏工具别处已在用） | `data/.../auth/SiteSession.kt:336-342,409-413`；`app/.../account/AccountManager.kt:93,185,272,294`；`app/.../account/AccountMigration.kt:41,114` |
| #19 | 低 | **只改文案**：把绝对化的「不上传」改成如实描述，README/KDoc 同步；D7「无行为变化」 | `core/.../agent/AgentConfigPanel.kt:354-359`；`README.md:55`；`app/.../agent/AgentTool.kt:63` |
| #20 | 中 | 对 `info.version` 做字符白名单 + 加一次 SHA-256/包名（签名）校验；D7「不改变正常行为」（正常包一律通过） | `app/.../update/AppUpdater.kt:243,263-270,286-296,307-315`（注：「读不到摘要时拒装还是降级」那半条归②） |
| #25 | 中 | 只把 `courses.map{}` 换成有限并发（`Semaphore(3)` + `async`）；D7「可见行为基本不变」。**不要**按审计改用 `getTodos()`（无依据） | `app/.../lms/LmsDueCollector.kt:15-21,42`；`app/.../home/HomeStatsRefresher.kt:344` |
| #27 | 低 | 只把 `AgentTool.kt` 里与工具表**无关**的两坨（HTML 解析辅助、本地设置读写）先摘出去；D7「不变」 | `app/.../agent/AgentTool.kt`（2408 行，全仓最大生产文件） |
| #34 | 中 | 纯 CI：token 从 URL/表单搬进 header、版本号走 `env:`、`core-gate.yml` 补一行 `:app:testDebugUnitTest`；D7「不变」 | `.github/workflows/build.yml:125-127,186-187,199-202,213,244`；`.github/workflows/core-gate.yml:60-80` |

### ② 需要先定产品口径（21 条）

组内都是「按我方修法会改变 Android 可见行为，或修法本身要重新定义一条语义」的条目。⇒ 按 §16.4 第一条，**回到用户面前拍板后再动**。

| # | 重判 | 一句话理由 | 指向的代码路径 |
|---|---|---|---|
| #02 | 高 | 切账号要不要把「屁岱」的会话/画像重置（且**别**顺手改掉「一段对话只生成一次 system prompt」） | `core/.../agent/AgentViewModel.kt:201-208,394-402`；`core/.../agent/AgentScreen.kt:94-99` |
| #03 | 中 | 401 场景从「弹窗叫更新密码」变成「只报该站点失败」；按审计走 `LoginResult.isCredentialError` 是**改公共返回类型、三端都要跟** | `data/.../auth/XJTULogin.kt:443-447,491-496`；`data/.../auth/CasSiteSession.kt:58-64,95-99` |
| #04 | 中 | Captive Portal 网络下从「判校内、直连超时」变成「走 WebVPN」；探针该看哪些响应特征（校内判定不能误伤） | `app/.../auth/CampusProbe.kt:43-48,174-178`；`app/.../auth/AppLoginState.kt:147-149` |
| #05 | 中 | 校外/流量下会**多出**这条签到提醒（补齐既有承诺）；成本极低（只补一次 `networkMode` 判断） | `app/.../notification/HeadlessSessions.kt:65-104` |
| #06 | 低 | 关闭面板时把当前输入落盘（正是注释里承诺的行为） | `core/.../agent/AgentConfigPanel.kt:78-103,158-173` |
| #07 | 中 | `payment_code` 加账号后缀 ⇒ **三端同改** ＋ 老用户升级后**重勾一次**（唯一取舍点） | `core/src/androidMain/.../platform/KeyValueStore.android.kt:24-26,69`；`app/.../pay/AppPaymentCode.kt:46-50`；`app/.../account/AccountManager.kt:318-350` |
| #08 | 低 | D7 写「改变」但同段注明「屏上无差异」⇒ 实操接近①；删会话/清缓存顺手清 `agent_images<后缀>/` | `app/.../agent/AndroidAgentHost.kt:356-386`；`core/.../agent/AgentViewModel.kt:250-258` |
| #09 | 低 | 分报告原话「**先定产品口径再动手**」：本地下载记录算「本机公共」还是按账号隔离（改它 = 重新定义「本地已下载文件归谁」，0 改动也能收场） | `app/.../media/DownloadTaskEntity.kt:13-31`；`core/.../media/DownloadManager.kt:81` |
| #12 | 低 | 从乱码变正常文本；前提是上游真会下发含 `·` 的中文串（本仓夹具没有）⇒ 修法与夹具一起定 | `data/.../venue/VenueApi.kt:70-79,113-116,143-144,185,289` |
| #15 | 中 | 「secret 进包」只有靠 `server/` 转发才能根治 ⇒ 反馈链的联网拓扑/失败模式/离线行为全变，是**跨端契约级**改动；只删 secret 会让反馈功能直接不可用 | `data/.../feedback/FeedbackApi.kt:20-31,116-137`；`app/build.gradle.kts:32-59` |
| #16 | 中 | 改 `allowBackup=false` 会丢**全部**本地状态；整库排除会丢自定义日程/书架/下载记录 ⇒ 要定「换机后还剩什么」 | `app/src/main/res/xml/data_extraction_rules.xml:4-17` |
| #21 | 中 | 老会话早期工具结果变摘要会改变模型看到的上下文（建议「新会话生效、老会话原样」） | `core/.../agent/AgentViewModel.kt:266-284,656`；`core/.../agent/AgentRunner.kt:139-141,221` |
| #22 | 低 | 并发会改工具气泡顺序/失败语义；工具表裁剪会让模型选不到工具 ⇒ 先在本端验证再谈对齐 | `core/.../agent/AgentRunner.kt:221`；`core/.../agent/AgentToolCatalog.kt:24,114,125,185,201` |
| #24 | 中 | 后台不再静默保活 ⇒ 久置后再打开某些站点要重登一次；并新增一个设置项 | `app/.../auth/SessionKeepAlive.kt:23,33-52,66-77`；`app/.../auth/AppLoginState.kt:416-418` |
| #26 | 中 | 会改变可见行为（小组件从「暂无待办」改对），但方向是「向 Android **应有**的现状对齐」，**无口径分歧**（照抄兄弟组件三行） | `app/.../widget/TodoWidgetProvider.kt:62-66`（对照 `CampusCardWidgetProvider.kt:26-33`、`ScheduleWidgetProvider.kt:503-510`） |
| #28 | 低 | 首页卡片动效时长/是否流动 = 视觉变化（一行：`isRestoring` 时 `runForMillis = 0L`） | `core/.../home/HomeScreen.kt:272-278` |
| #29 | 中 | 未登录用户能看到帖子列表 ⇒ 产品决策（走 `:server` 只读代理，涉及 token/缓存/限流，且与「serve 只监听本机」的定位有张力） | `core/.../community/CommunityScreen.kt:61-66` |
| #30 | **高** | **写路径对齐唯一真卡点**：先定「契约里支付一跳的形态」，Android 本轮**不动**（这正是「向 Android 现状对齐」的含义） | `core/.../core/net/CampusVenueApi.kt:137-140,363-365`；`core/.../venue/VenueScreen.kt:137-140`；契约 §5.3.2 |
| #31 | 低 | TalkBack 朗读内容与部分控件热区变大（只做两处高价值点，装饰图标保持 null） | `core/.../community/DiscussionsScreen.kt:212`；`core/.../community/DiscussionReplies.kt:106-110` |
| #32 | 中 | 默认昵称与默认分享维度变化 ⇒ 改成默认脱敏 + 加一行「本码包含…」提示 | `core/.../social/MatchProfile.kt:119,296-303,347-348`；`core/.../social/MatchScreen.kt:152-156` |
| #33 | 高 | 钉住的版本可能与今天解析到的不同 ⇒ **先记录当前解析版本再钉**，确保「钉完 = 今天的样子」 | `gradle/libs.versions.toml:12`；`app/build.gradle.kts:175-188,253-260` |

### ③ 需要先拿真实证据（抓包/样本）才能判（3 条）

| # | 重判 | 一句话理由 | 指向的代码路径 |
|---|---|---|---|
| #01 | 中 | 唯一判据是「上游是否真下发带小数的成绩文本」—— **一次真实抓包或一条夹具就能把它升为高/致命**；修法本身会改数值显示 | `core/.../score/ScoreCalculator.kt:47-55,80-81,186` |
| #11 | 低 | 需要一张「选项升序」的 gste 问卷**样本**＋「服务端是否真拒全优」；改条件（`options.any { … }`）是向后兼容小修，可先备着 | `data/.../judge/GraduateJudgeApi.kt:290-296,379-381` |
| #13 | 高 | 链条每一环都能指到代码，但「模型被注入后是否真会输出独占一行的 `![…](https://…)`」未复现（做一次实机 `adb shell am start` 就能定）；图片是否改「点击加载」同时要拍板 | `core/.../agent/AgentScreen.kt:104-110`；`app/.../agent/AndroidAgentHost.kt:424-436`；`core/.../agent/AgentMarkdown.kt:174,367-378` |

> 另有若干条目也缺外部证据（#03 的 401、#04 的 Captive Portal、#12 的上游 `·`、#16 的实际备份域、#30 的 80 端口会话、#22 的 token 量、#28 的掉帧），但它们**主要的卡点是可见行为/口径**，故列在②（证据清单见 §2.3）。

### ④ 建议不做，或只记进文档（3 条）

| # | 重判 | 一句话理由 | 指向的代码路径 / 落点 |
|---|---|---|---|
| #23 | 建议 | 审计证据系伪造（`AgentPrompt.build(null, …)` 任何 ref 都不存在）；现行为是**写进 KDoc 的故意取舍**，审计的建议（每轮重写 `llmHistory[0]`）会主动打破前缀缓存与「前后人设一致」⇒ **不做**，只留档 | `core/.../agent/AgentPrompt.kt:9-12`；`core/.../agent/AgentViewModel.kt:398-402,416-445` |
| #35 | — | `server/leaderboard/*` 在本仓任何分支都不存在 ⇒ 本仓无可改；若线上那套仍在跑，把审计的 `clientIp()` 推理（回环才读 `x-real-ip` + Nginx `proxy_set_header X-Real-IP`）**转给那个仓库** | 无本仓路径（`ls server/` → `build/ build.gradle.kts src/`） |
| #36 | 建议 | 模板都在，真缺的只有 `CONTRIBUTING.md` / `SECURITY.md` ⇒ 只补一份 `SECURITY.md`，并在 `README.md` 加一行「从哪读起」指向 `docs/desktop-port-plan.md` / `docs/api-contract.md`；不必另写 `architecture.md` | `.github/`（现状见分报告 3 §2 #36） |

---

## 4. 与当前路线（§16「三端完全一致」）的交叉点

按 `docs/desktop-port-plan.md` §16 的口径（**丙：逐像素/逐状态全等；基准 = Android 现有行为**）与 §16.3 的施工批次，逐条对位：

### 4.1 多账号隔离族 #02 / #07 / #09（+ #14 的 Cookie）—— 直撞 D8「统一存档层」与 D9「三端都支持多账号」

- `docs/storage-layout.md` §1.1 已经把 `agent_sessions<suffix>/`（`index.json` / `convo_<id>.json`）与 `data_cache<suffix>/` 写成**真源形状**；#02 恰好是「**磁盘形状按账号、内存态不按账号**」——磁盘侧 `AgentSessionStore.kt:29-33` 每次现取 `safeSuffix()`，而 `AgentViewModel.kt:201-208` 的 `bind()` 只跑一次。修它在共享 `:core`，**三端一起变**。
- #07 的 `payment_code` 是 `storage-layout.md` §5.1「同名同键、形状不同」那一族的**邻位**问题：三端同一存储名且无后缀（`core/src/androidMain/.../KeyValueStore.android.kt:24-26,69`）。加后缀 = 三端同改 ＋ 老用户重勾一次 —— 与 storage-layout 未决问题 #2（「旧形状只读、新形状写」在 Android 上做不到）是**同一个取舍**。
- #09 的 `download_tasks` 是 Room 侧（Android 独有），但「本地已下载文件归谁」这条口径决定 `core/.../media/DownloadManager.kt:81` 在三端怎么呈现 ⇒ 分报告已明确「先定口径」。
- #14 与 **serve 的会话归属**：Android 的 WebView `CookieManager` **不在统一存档层里**（storage-layout 没有这一族），而 web 端的「切账号」＝ serve 换一套凭据（§16.1 D9）。⇒ 修 #14 之前必须先答「`:web`/serve 的会话算谁的」，否则同一个「切账号/登出」动作在三端语义不同（`app/.../account/AccountManager.kt:66-72,96-98,245-295`）。

### 4.2 #30 场馆「去支付」—— §16.3 批③「写路径对齐（含真支付，D7/D11b）」的唯一卡点

`:core` 的 serve 形态下 `paymentUrl` 与 `browserLoginUrl` **都是空串**（`core/.../core/net/CampusVenueApi.kt:137-140,363-365`，契约 §5.3.2 已记缺口）⇒ 屏上「去支付」点了**没动作**。「下单成、支付断」是半条链；分报告给的两步修法与本路线一致：**先把契约里这一跳的形态定下来**，Android 侧本轮不改（现状先 8080 OAuth 再跳 80 支付页就是基准端行为）。

### 4.3 跨端契约级 / 只在 Android 有面

- **#15**：把 secret 收回服务端、走 `server/` 转发 = 反馈这条链的**拓扑变更**（分报告原话「跨端契约级改动，不是小修」）⇒ 与 D7 的「web 由 `:server` 代操作」同一条建设线，必须一起排。
- **#13**：`xjtu://` 与 `EXTRA_LAUNCH_*` 在**仓库内没有任何内部生产者**（小组件/通知只用 `EXTRA_LAUNCH_ROUTE`/`EXTRA_LAUNCH_TAB`）⇒ 把深链降级为「只预填输入框」**只影响 Android 一条当前无人依赖的外部路径**，是三端里唯一「改 Android 而完全不动别端」的条目（但图片是否改「点击加载」会改可见行为）。
- **#33**：`latest.release` 决定「同一份源码两次构建出不同 UI」；它与「三端一致」的间接关系是「先让 Android 自身可重现，再谈对齐」——建议先记录当前解析版本再钉。

### 4.4 改的是共享 `:core` / 共享屏 ⇒ 三端同时变（要按「三处同改」走）

- **#01**：`core/.../score/ScoreCalculator.kt` 是三端共用的算分（分报告原话「三端共用这份 `:core` 代码，桌面/web 同步受益」）。
- **#21**：`core/.../agent/AgentRunner.kt` + `AgentViewModel.kt`（分报告原话「改调度与历史压缩 ⇒ 会；三端共享 `:core`」）。
- **#22**：同一份调度循环与工具表。
- **#31 / #32**：共享 `:core` 屏（分报告原话「三端共享屏改动会同时影响桌面/Web」）。
- **#08**：桌面 `DesktopAgentHost` 的图片四件是默认值 ⇒ **不受影响**（可单独在 Android 收口）。

### 4.5 明确**不影响**跨端口径的

- **#19**（分报告原话「本条不影响跨端口径」，只改文案）；**#16**（Android 独有备份域）；**#17** 的图片客户端（`app/.../AndroidAgentHost.kt:424-436` 是 Android 宿主，但 `core/.../agent/AgentMarkdown.kt:174` 的「只认 https 图」是三端共享规则）；**#26**（Android 小组件，但 `AccountContext` 在 `:core`）；**#35**（本仓无代码）；**#34**（纯 CI）。

---

## 5. 统计（数字全部用命令数出来，未估）

复核基线 `5e9b2e53`，36 条：

**我方复核判定**

| 判定 | 条数 | 编号 |
|---|---|---|
| 成立 | **19** | #02 #03 #04 #05 #06 #07 #08 #09 #10 #12 #14 #20 #25 #26 #29 #30 #31 #32 #33 |
| 部分成立 | **14** | #01 #11 #13 #15 #16 #17 #18 #19 #21 #22 #24 #27 #28 #34 |
| 不成立 | **2** | #23（证据系伪造） #36（残留半条） |
| 无法判定 | **1** | #35 |
| 合计 | **36** | |

（数法：`grep -hE '^\| #[0-9]{2} \|' docs/audit-verify-{1-correctness,2-security,3-rest}.md` 取三张总表，按各自列位取「判定」列后 `sort \| uniq -c`。注：#25/#26 原文带括注「成立（修法依据不成立）」「成立（修法已过期）」，按「成立」计入。）

**我方重判为「高」的编号（5 条）**

```
#02 #13 #14 #30 #33
```

（数法：同一批表行按严重度列 `grep -E '\|高'` 后取编号；#30 原文是「**高**（阻塞写路径对齐决策）」，按「高」计入。**没有一条判「致命」**。）

**严重度分布对比**

| 刻度 | 审计自报 | 我方重判 |
|---|---|---|
| 致命 | 2（#01 #13） | **0** |
| 高 | 8（#02 #03 #04 #05 #14 #15 #16 #33） | 5（#02 #13 #14 #30 #33） |
| 中 | 18 | 15 |
| 低 | 7 | 13 |
| 建议 | 1（#36） | 2（#23 #36） |
| —（不适用） | 0 | 1（#35） |
| 合计 | 36 | 36 |

单项严重度变动：**下调 16 条**（#01 致命→中；#13 致命→高；#03 #04 #05 #15 #16 高→中；#06 #08 #09 #17 #18 #19 #22 #27 中→低；#23 低→建议）、**上调 2 条**（#30 中→高；#32 低→中）、**变不适用 1 条**（#35 中→—）、**持平 17 条**。

**分报告自报的「高」清单**（三份合起来与我方重判一致）：
- 分报告 1：`#02（高）—— 唯一一条我判「高」；没有一条我判「致命」`。
- 分报告 2：`#13、#14`。
- 分报告 3：`#30、#33`（次高：#21 #24 #25 #26 #29 #32 #34）。

---

## 6. 未覆盖与未核对项（汇总视角）

1. **#10 的 `LmsApi` 半条**：审计标题含「与 `LmsApi` 的实例级缓存……」，分报告只核了 `JudgeApi` ⇒ 该半条**报告未覆盖**。
2. **三份分报告都明确「没跑构建/测试」** ⇒ 所有「测试钉住了什么」是**读测试文件**得出的，不是运行结果；#11 的「倒过来会让断言变红」是从代码推断。
3. **三份都没有实机、没有抓包、没有联网探测上游**（#01/#05/#13/#14/#16/#17/#18/#21/#22/#25/#28/#30 的上游或测量前提见 §2.3）。
4. **覆盖率是文件数/行数比**，不是行/分支覆盖率（禁止跑 jacoco）；「哪些解析器真被测到」只做了文件名+夹具存在性核对。
5. **审计基线到底对应哪一次提交，本汇总不能定**：能对上行号的对得上 `origin/main`/`origin/dev`，但 #01/#09/#21/#23/#25/#35 那几处引文在**本仓任何 ref** 都搜不到（§2.1）。
6. **本文件不评价正在被其他子代理改动的面**（`core/.../core/net/**`、`web/**`、`docs/api-contract.md`）；分报告 1/2 也各自声明过同样的边界。

---

## 7. 真源声明（重复一次）

本文件是**汇总**，逐条证据（引文、行号、复算、最小复现路径、未核对项）在三个分文件里：`docs/audit-verify-1-correctness.md`（#01–#12）、`docs/audit-verify-2-security.md`（#13–#20）、`docs/audit-verify-3-rest.md`（#21–#36）；审计原文副本为 `/tmp/audit-20261014.md`。本文件不含新增结论，若与分报告冲突，**以分报告为准**。
