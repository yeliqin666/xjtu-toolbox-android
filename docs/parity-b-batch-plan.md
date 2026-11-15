# 三端一致 B 批：5 路并行计划（目标 3 小时）

> **2026-11-15 变更（优先于下文）**
>
> - 不单开 PR，不推送。各路只在本地分支 `parity-b/<代号>` 提交，把提交哈希和摘要交给审计员；审计员按 A → C → B → D → E 的顺序 cherry-pick 到 `chore/parity-a-batch`，统一推送一次，更新 PR #150。下文里“开 PR / rebase / PR 正文”都按这条理解：摘要的内容就是原来 PR 正文要写的。
> - 不强求真机验证。交付时不再列“需真机验证”清单；只用假上游验证过、把握不足的请求，在摘要里用一句话标注。
> - 每路使用独立工作区 `D:\XJTUAPP-b-<代号>`，不要在 `D:\XJTUApp` 和 `D:\XJTUAPP-parity` 里改动或构建；所有命令在后台隐藏运行。

基线：`chore/parity-a-batch`（PR #150）最新提交。安卓是基准，桌面 / Web（serve 形态）跟进。
背景与判据见 `docs/parity-ledger.md`（§8.14 是本批起点）和 `docs/api-contract.md`。

## 0. 所有 bot 共同遵守

**分支与合并**

- 每个 bot 从 `chore/parity-a-batch` 拉自己的分支 `parity-b/<代号>`（A–E），PR 目标是 `chore/parity-a-batch`，**不是 dev**。
- 开 PR 前 `git fetch && git rebase origin/chore/parity-a-batch`；合并顺序 **A → C → B → D → E**，后合的负责解冲突。
- 不许 force push 别人的分支；不碰 `main` / `dev`。
- 提交信息用中文、Conventional Commits 前缀；**不加任何 Co-Authored-By / Claude 署名**。

**改代码**

- 只改本组「独占文件」；「热点文件」只做表里写明的那一处改动（见 §6），别顺手重排、重格式化。
- 用 Edit 工具改文件，不用 sed / python 批量写（仓库是 LF，Windows 上脚本写回会变 CRLF）。
- 注释从简：只写是什么 / 为什么，不写历史沿革；动到的旧注释顺手精简。
- 不派子代理，自己做完。
- 契约改动写进 `docs/api-contract.md` 末尾**本组预留的小节号**（§6）；**不改 `docs/parity-ledger.md`**，结论写在 PR 正文里，由集成方统一记 §8.15。

**验收（每个 PR 都要过）**

```bash
./gradlew :app:compileDebugKotlin :desktop:compileKotlin :web:compileKotlinWasmJs
./gradlew <本组涉及模块的 jvmTest / :server:test / :desktop:test>
```

CI 的 `Core KMP Gate` 必须绿。PR 正文末尾列一节「**需真机验证**」：bot 没有真实校园账号，凡是只靠夹具证明的上游交互都要列出来，交给 owner 用真账号走一遍。

## 1. Bot A —— 评教切到 QMS（桌面 + serve + Web）

**为什么**：安卓 `117d819b` 把本科评教切到 QMS 平台（`LoginType.QMS`），桌面和 serve 还在调旧的教务 `JudgeApi`，评教在两端都不可用。

**现成零件**：`shared/data/.../auth/QmsSession.kt`、`shared/data/.../judge/QmsJudgeApi.kt`、`QmsJudgeSource`（`shared/data/.../judge/AppJudgeSources.kt:11`）。安卓接法见 `app/.../judge/AppJudgeScreens.kt`。

**做什么**

1. 桌面：`DesktopAuth` 加 QMS 站点 key（`QmsSession.SITE_KEY`）并纳入登录时建会话的站点；`ToolboxDesktopApp.kt` 的 `AppRoute.Judge` 本科分支改用 `QmsJudgeSource`（研究生 `GraduateJudgeSource` 不动）。
2. serve：`ServeSession` 加 QMS 站点；`JwxtRoutes.kt` 里 `/api/jwxt/evaluations/*`（列表 / 状态 / 提交）改接 `QmsJudgeApi`。问卷形状不同（`QmsQuestionnaire` vs `Questionnaire`），投影按新形状重写，路径能保持就保持。
3. Web：`shared/network/.../judge/CampusJudgeApi.kt` 与 `web/.../WebJudgeScreen.kt` 跟上新投影。
4. 删掉旧 `UndergraduateJudgeSource` 在桌面 / serve 的调用；它若已无调用方，一并删除。

**测试**：`:server:test` 补评教三条端点的契约测试（假上游照 `QmsJudgeApi` 的请求形状写）；`:desktop:test` 里涉及评教的用例跟上。
**契约**：§5.15。**需真机**：QMS 登录、列表、提交。

## 2. Bot B —— 学业进度（桌面 + Web）+ 小岱工具

**为什么**：安卓新增学业进度（#255），桌面和 Web 都还是占位页。

**现成零件**：屏 `feature/score/.../degree/DegreeScreen.kt`（共享）；取数 `shared/data/.../degree/AppDegreeSource.kt`（commonMain）；安卓外壳 `app/.../degree/AppDegree.kt`（57 行，照着写桌面版）；小岱工具 `feature/score/.../degree/DegreeAgentTools.kt`。

**做什么**

1. 桌面：`ToolboxDesktopApp.kt` 把 `AppRoute.DegreeProgress -> NotPortedScreen` 换成真屏，source 用 `AppDegreeSource` + 桌面的教务会话与课表缓存。
2. serve：新开 `DegreeRoutes.kt`（`/api/degree/*`：方案列表、进度、换方案、在修课程），复用 `AppDegreeSource`，在 `ServeEndpointRoutes` 注册。
3. Web：`shared/network` 加 `CampusDegreeApi : DegreeSource`；`ToolboxWebApp.kt` 加 `AppRoute.DegreeProgress` 分支（campus-api 形态画 `WebServeOnlyScreen`，与其他只读域同一规矩）；`WebSupport.openableRouteTypes` 加 `DegreeProgress::class`，删掉文件头那条说明；`WebRouteCoverageTest` 差集去掉 `DegreeProgress`。
4. 小岱：`server/.../ServeAgentTools.kt` 的 `NAMES` 加 `get_degree_progress`。

**测试**：`:server:test` 端点契约测试；`:web:wasmJsBrowserTest` 路由覆盖；`:desktop:test` 的 `DesktopRouteCoverageJvmTest` 跟上。
**契约**：§5.16。**需真机**：教务培养方案那一串请求。

## 3. Bot C —— 成绩报表补全（Web）+ 思源待交（桌面 / serve）

**为什么**

- 安卓 `c790f002` 把成绩报表并入成绩查询，看不到的成绩用报表补全。Web 的 `CampusJwappScoreApi.reportedGrades` 直接抛 `UnsupportedOperationException`（`shared/network/.../CampusJwappScoreApi.kt:77`），serve 也没有端点。
- 待交缓存 `LmsDueStore` 的唯一写入方 `LmsDueCollector` 只在 `:app`（`app/.../lms/LmsDueCollector.kt`），桌面和 serve 那份缓存**永远是空的**，所以 `/api/lms/due`、桌面「接下来」、安卓 `d10640f7` 新加的思源待交一览，到了两端都是空的。

**做什么**

1. serve：`JwappRoutes.kt` 加 `GET /api/jwapp/score/reported`（复用 `shared/data/.../score/ScoreReportApi.kt`，走教务会话）；Web 端 `CampusJwappScoreApi.reportedGrades` 实现它。桌面走共享取数，确认 `reportedGrades` 真能拿到数据（不能就修）。
2. 把 `LmsDueCollector` 下沉到 `:data`（`ctx` 参数注释里已说明用不到，删掉），`:app` 两个调用方（`HomeStatsRefresher`、`LmsDeadlineWorker`）跟着改。
3. 桌面：进思源屏 / 日程屏时按 TTL 跑一次收集（参照 `DesktopReminders` 的节奏，别每次重组都拉）。
4. serve：`/api/lms/due` 缓存过期时先收集一轮再返回，并更新 §5.12 里「不为作业发任何请求」那句。
5. 核对 `LmsScreen` 新加的「本学期待交一览 / 课程卡截止」在桌面、Web 的宿主里有数据可显示。

**测试**：`:data:jvmTest`（收集器下沉后的单测）、`:server:test`（两条端点）、`:feature:score:jvmTest`。
**契约**：§5.17。**需真机**：报表补全的匹配结果、思源截止数据。

## 4. Bot D —— 校车（Web）+ 桌面帖子详情 + 存档登记 + 小岱工具

**做什么**

1. 校车：`BusApi`（`shared/network/.../bus/BusApi.kt`）走黄页那条路，桌面已接（`ToolboxDesktopApp.kt` 的 `AppRoute.CampusBus`）。serve 在 `infoRoutes` 旁加 `/api/bus/*` 转发；`shared/network` 加 Web 用的 source；`ToolboxWebApp.kt` 加 `AppRoute.CampusBus` 分支；`WebSupport` 加 `CampusBus::class` 并删掉文件头那条；`WebRouteCoverageTest` 差集去掉 `CampusBus`。
2. 桌面帖子详情：`AppRoute.CommunityPost -> NotPortedScreen` 换成真屏，照 Web 的接法（`ToolboxWebApp.kt` 里 `is AppRoute.CommunityPost -> CommunityScreen(... discussionNumber = route.number)`），用桌面的 GitHub 会话。
3. 存档登记：`feature/settings/.../archive/ArchiveRegistry.kt` 补 dev 新加的本地店：`friend_timetables`、`degree`、`agent_shares`，并核对 `self_todos<后缀>`（自建作业待办）是否已登记。逐个定 Scope（按账号 / 全局）与 Carrier，判据照文件里已有条目；补 `:feature:settings:jvmTest` 用例。
4. 小岱：`ServeAgentTools.NAMES` 加校车工具（名字以 `feature/agent/.../AgentShared.kt` 里的为准）。

**测试**：`:server:test`、`:feature:settings:jvmTest`、`:desktop:test`、`:web:wasmJsBrowserTest`。
**契约**：§5.18。**需真机**：无（黄页公开接口），帖子详情要 GitHub 登录态看一眼。

## 5. Bot E —— Web 账号写操作（serve 写端点）

**为什么**：Web 账号页只读（`web/.../WebAccountsHost.kt:79-89` 四个动作全是 `notWritable()`），serve 只有 `GET /api/accounts`（`AccountRoutes.kt`，`readOnly = true` 写死）。

**口径（已定，不用再问）**

- 四个写端点：`POST /api/accounts/switch`、`POST /api/accounts`（新增）、`DELETE /api/accounts/{id}`、`POST /api/accounts/{id}/password`。
- 与现有写端点（评教提交、成绩单提交）**同一道闸门**：整个 `/api` 已强制挂 `AccessTokenGate`（`ServeModule.kt` 的 `install(accessTokenGate(...))`），写端点放在同一个 `route(API_PREFIX)` 下即可，不另起口径；`readOnly` 改为 `false`。对外部署仍按契约 §3.3 先加反代 + 鉴权。
- 密码只在请求体里出现，永不写日志、永不回显；落盘用 serve 现有的安全存储，与桌面 `DesktopAccounts` 同一套。
- 语义照桌面 `DesktopAccounts` / `DesktopAuth`：切换 = 重建会话（换 `sessionManager` 的账号命名空间）；删除当前账号的处理与桌面一致；改密 = 用新密码重登一次，成功才落盘。
- 学号在响应里继续打码（`AccountRoutes.kt` 的现有打码函数）。

**做什么**：`AccountRoutes.kt` 四个端点 + `ServeSession` 切换支持；`shared/network` 客户端；`WebAccountsHost` 四个动作接上，去掉只读提示里过时的话。

**测试**：`:server:test`（不带令牌 ⇒ 401、带令牌 ⇒ 成功、日志与响应里都不出现密码）。
**契约**：§5.19（同时更新 §5.10 那句「这一批没有写端点」）。**需真机**：新增账号、切换、改密各走一遍。

## 6. 冲突预案

**预留编号**：契约 §5.15 = A，§5.16 = B，§5.17 = C，§5.18 = D，§5.19 = E。都追加在文件末尾，rebase 时按编号排好。

**热点文件**（多组都要动，只做下面这一处改动）

| 文件 | 谁 | 只做什么 |
|---|---|---|
| `desktop/.../ToolboxDesktopApp.kt` | A / B / D | A：`AppRoute.Judge` 分支；B：`DegreeProgress`；D：`CommunityPost` |
| `web/.../ToolboxWebApp.kt` | B / D | B 在 `AppRoute.JwappScore` 分支后加 `DegreeProgress`；D 在 `AppRoute.YellowPage` 分支后加 `CampusBus` |
| `web/.../WebSupport.kt` | B / D | 各加一行 `::class`，各删文件头自己那一条 |
| `web/.../WebRouteCoverageTest.kt` | B / D | 差集列表各删自己那一项 |
| `server/.../ServeAgentTools.kt` | B / D | `NAMES` 各加一项 |
| `server/.../ServeSession.kt` | A / E | A：QMS 站点 key；E：切换账号 |
| `server/.../ServeEndpointRoutes.kt` | B / D | 各注册一行 |
| `docs/api-contract.md` | 全部 | 只写本组预留小节 |

**独占**：A 评教相关；B `degree/*`、`DegreeRoutes.kt`；C `JwappRoutes.kt`、`LmsRoutes.kt`、`LmsDueCollector`；D `ArchiveRegistry.kt`、`bus/*`；E `AccountRoutes.kt`、`WebAccountsHost.kt`。

## 7. 时间线（3 小时）

| 时段 | 做什么 |
|---|---|
| 0:00–0:20 | 读本计划和涉及文件，确认现成零件能用；发现前提不成立就在 PR 草稿里写明并缩小范围，**不扩大范围** |
| 0:20–2:00 | 实现 + 单测 |
| 2:00–2:30 | 跑验收命令，rebase，开 PR（正文：做了什么 / 没做什么 / 需真机验证） |
| 2:30–3:00 | 集成方按 A → C → B → D → E 合并，记台账 §8.15 |

**3 小时内做不到的**：真机验证（QMS、教务培养方案、账号写操作），留给 owner 在合并后用真账号走一遍。

## 8. 不在本批

campus-api 形态下的 15 屏（部署形态的选择，不在本仓库补）；安卓平台专属能力（自动勿扰、动态通知、桌面小组件、扫一扫等）；`ArchiveScoreReport` 整族去留（等作者定）。
