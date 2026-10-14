# 外部审计复核 · 第 3–6 节（#21 ~ #36）

复核对象：`/tmp/audit-20261014.md` 第 825–1423 行（「#### 3. AI 助手『屁岱』设计」到文末）。
复核仓库：`/mnt/nas/data/works/xjtu-toolbox-android`，分支 `refactor/kmp-multiplatform`，HEAD `5e9b2e53`（2026-10-14）。
复核方式：只读（read / grep / sed / wc / find / git show / git log）。**未跑 gradle、未跑测试、未改任何已有文件。**

---

## 0. 两条前置结论（先说清，后面每条都用到）

### 0.1 审计基线 ≈ `origin/main` / `origin/dev`（KMP 重构前的 Android 单模块），不是 HEAD

拿审计里最「可核对」的三处行号去比：

```bash
git show origin/main:app/src/main/java/com/xjtu/toolbox/home/HomeTab.kt | sed -n '124,134p'
#   → com.xjtu.toolbox.ui.components.MeshBackground( ... runForMillis = 6_000L, )   ← 正好落在 126~132
git show origin/main:app/src/main/java/com/xjtu/toolbox/community/CommunityScreen.kt | sed -n '35,36p;53,59p'
#   → 「必须登录 GitHub 才能看」+ if (signedIn == null) { CommunityLoginScreen(...) } else { CommunityForum(...) }  ← 与审计引文逐字一致
```

而 HEAD 上这些文件已经搬走（`home/HomeTab.kt` 只剩 135 行的「子系统连接状态」弹窗，首页在 `core/.../home/HomeScreen.kt`）。所以：**审计的行号大多不是 HEAD 的行号**，下文一律给出 HEAD 的**实际位置**，并在需要时同时给 `origin/main` 的数字。

### 0.2 但有 5 处「已确认」证据，在本地**全部** ref 里都搜不到

```bash
git log --all --oneline -S "ContextBudget.fit"     # 空
git log --all --oneline -S "buildRequestBody"      # 空
git log --all --oneline -S "contextTokens"         # 空
git log --all --oneline -S "llmHistory.drop(1)"    # 空
git log --all --oneline -S "AgentPrompt.build(null" # 空
git log --all --oneline -S "getTodos"              # 空
git log --all --oneline -S "api/todos"             # 空
for b in origin/main origin/dev origin/announce refactor/kmp-multiplatform; do
  git ls-tree -r --name-only $b | grep -c leaderboard; done   # 0 0 0 0
```

即：`ContextBudget.fit(...)`、`llmHistory.drop(1).map{}`、`AgentPrompt.build(null, …)`、`LmsApi.getTodos()`、`server/leaderboard/index.js` **在本仓库任何一个分支的任何一次提交里都不存在**。这几条「证据」不能被当作事实依据 —— 下文对相应条目会明确标注。

### 0.3 我量的关键数字（命令 + 输出）

| # | 量什么 | 命令 | 结果 |
|---|---|---|---|
| A | HEAD `app/src/main` 源文件 | `find app/src/main -name '*.kt' \| wc -l` | **138**（全类型文件 219）；`origin/main` 同路径 = **424 .kt / 511 文件** |
| B | HEAD 生产 `.kt`（去 build / miuix-ref / 测试源集） | `find . -name '*.kt' \| grep -v /build/ \| grep -v ^./miuix-ref/ \| grep -vE '/src/(test\|jvmTest\|commonTest\|androidTest)/' \| wc -l` | **861 文件 / 163,798 行** |
| C | HEAD 测试 `.kt` | 同上反向过滤 | **264 文件 / 37,168 行**（`app/src/test` 76、`core` 72、`data` 59、`desktop` 28、`server` 15） |
| D | HEAD 最大文件（生产，去 miuix-ref/build） | `find … \| xargs wc -l \| sort -rn \| head` | `agent/AgentTool.kt` **2408**、`card/CampusCardScreen.kt` **1561**、`desktop/ToolboxDesktopApp.kt` 1531、`testkit/JwxtFakeUpstream.kt` 1400、`schedule/ScheduleScreen.kt` 1380、`emptyroom/EmptyRoomScreen.kt` 1212、`social/MatchScreen.kt` 1204、`venue/VenueScreen.kt` 1110、`update/AppChangelog.kt` 650 |
| E | `AgentTool.kt` 各提交行数 | `for c in $(git log --format=%h -30 -- …/AgentTool.kt); do git show $c:… \| wc -l; done` | 最近 30 次提交区间 **2907–2954**（`origin/main` 2907，HEAD 2408）——**没有任何一次是审计说的 3019** |
| F | 工具个数（HEAD） | `grep -c 'arr.add(agentTool(' core/.../AgentToolCatalog.kt` | **27**（同法在 `origin/main` 数 `arr.add(tool(` = **27**）——不是审计说的 29 |
| G | 工具 schema 体量（HEAD） | 提取 `AgentToolCatalog.kt` 全部字符串字面量 | 字面量 **3,484 字符**（其中 CJK 1,458）；69 个参数；估 JSON 串 ≈ **7.9k 字符** ⇒ 粗估 **3.0k–5.0k tokens**（本机无 tokenizer，且不许跑构建 ⇒ **这是估算不是实测**） |
| H | 无障碍 | `grep -rn "contentDescription = null" core/src/commonMain app/src/main \| wc -l` 等 | `contentDescription = null` **101**、`Icon(x, null, …)` **50**、`minimumInteractiveComponentSize` **0**、`Canvas(` **19** |
| I | 站点会话数 | `grep -c "register(com.xjtu.toolbox.auth" app/.../auth/AppLoginState.kt` | **18**（不是审计 #28 说的「11 个站点会话恢复」） |
| J | 保活间隔/站点数 | `grep -n WARM_SITES data/.../auth/SessionManager.kt` / `KeepAlivePrefs` | `WARM_SITES = 4`、默认 **10 分钟**、`KeepAlivePrefs(KEY_ENABLED 默认 true)` |

**覆盖率口径（自己定的，写清分母）** —— 这是文件数/行数比，**不是**行/分支覆盖率（本机不许跑 gradle + jacoco）：

- 口径 A（生产 vs 测试文件数）：264 / 861 = **30.7%**
- 口径 B（生产 vs 测试行数）：37,168 / 163,798 = **22.7%**
- 口径 C（只算 `:app`，即审计点名的那个模块）：76 / 146 = **52.1%**
- 口径 D（用审计自己的算法 = 测试文件 ÷ `app/src/main` 文件）：`origin/main` = 106 / 511 = **20.7%**；HEAD = 76 / 219 = **34.7%**
- ⇒ 审计的「约 1.5%」在任何口径下都不成立（它等于 8/537，而 8 和 537 两个数字我都复现不出来）。

---

## 1. 总表

| # | 类别 | 判定 | 我重判的严重度 | 一句话结论 | 证据（HEAD，除注明外） |
|---|---|---|---|---|---|
| #21 | AI/架构/性能 | **部分成立** | 中 | 「历史不压缩、整段重发、整段落盘、落盘在主线程」四条都真；但「`ContextBudget.fit` 结果被丢弃」「每次重跑 fit 估算」两条证据在本仓库任何 ref 里都不存在 | `core/.../agent/AgentViewModel.kt:104,266-284,394,656`；`AgentRunner.kt:139-141,221`；`data/.../agent/AgentSessionStore.kt:67-75` |
| #22 | AI 设计 | **部分成立** | 低 | 工具是**全量**塞（与登录态无关），这点比审计说的更绝对；但工具数是 **27 不是 29**；同回合 `tool_calls` 确实串行（`AgentRunner.kt:221`） | `core/.../agent/AgentToolCatalog.kt:24,114,125,185,201`；`AgentRunner.kt:131,138,156,221`；`AgentViewModel.kt:405` |
| #23 | AI 设计 | **不成立**（证据系伪造；残留一条设计取舍） | 建议 | `AgentPrompt.build(null,…)` 在任何 ref 里都不存在（`git log -S` 空）；HEAD 是「首轮用最新画像生成、之后整段复用」，**这是写进 KDoc 的故意取舍**，不是竞态 bug | `core/.../agent/AgentPrompt.kt:9-12`；`AgentViewModel.kt:398-402,416-445,245`；`app/.../agent/AgentTool.kt:148` |
| #24 | 性能/架构 | **部分成立** | 中 | 无前后台感知、进程活着就 10 分钟一轮、默认开 —— 全真；「设置里无开关」也真（`KeepAlivePrefs` 有 enable/interval 字段但**全仓没有 UI 写它**） | `app/.../auth/SessionKeepAlive.kt:23,33-52,66-77`；`MainActivity.kt:77`；`AppLoginState.kt:416-418`；`SessionManager.kt:353-379,562` |
| #25 | 性能 | **成立**（修法依据不成立） | 中 | `courses.map{}` 串行 + 每条未交作业一次 `getUserSubmitCount` 串行，真 N+1；但「上游有聚合接口 `getTodos()`」在仓库里**无任何依据**（`git log -S getTodos` 空） | `app/.../lms/LmsDueCollector.kt:15-21,42`；`HomeStatsRefresher.kt:344,157,328`；`LmsDeadlineWorker.kt:53-55,97-99`；`data/.../lms/LmsApi.kt:146,198-201` |
| #26 | Bug/架构 | **成立**（修法已过期） | 中 | 同进程的兄弟小组件都恢复 `AccountContext`，只有待办小组件不恢复 ⇒ 冷启动读 `default` 命名空间、显示「暂无待办」；但 `InboxStore.init(context)` 在 HEAD **已不存在** | `app/.../widget/TodoWidgetProvider.kt:62-66,107-110` vs `CampusCardWidgetProvider.kt:26-33` / `ScheduleWidgetProvider.kt:503-510`；`core/.../inbox/InboxModels.kt:254-266`；`core/.../account/AccountContext.kt:56-59` |
| #27 | 架构/工程 | **部分成立** | 低 | 「多超大文件」只剩 2 个 >1500 行（2408/1561）；3019 行、1763/1783/1899 行、1472 行、537 文件、8 测试文件、1.5% 覆盖率**全部对不上**；「CAS/WebVPN/解析器 0 单测」被现存测试直接证伪 | §0.3 A–J；`app/src/test/java/com/xjtu/toolbox/library/LibraryPagesTest.kt:1-25`；`nav/UrlCodecParityTest.kt:1-20`；`auth/LoginGateTest.kt` |
| #28 | 性能/UI | **部分成立** | 低 | 首页首屏确实跑 6 秒自定义着色器（真）；但「叠加 11 个站点会话恢复」不实 —— 冷启动只登 `jwxt` 一个站，注释写着「冷启动不做别的主动认证」 | `core/.../home/HomeScreen.kt:272-278`；`core/androidMain/.../MeshBackground.android.kt:60-73`；`app/.../main/AppRoot.kt:301-320`；`AppLoginState.kt:390-407`（18 站点） |
| #29 | 产品/UX | **成立** | 中 | 读帖也要 GitHub 登录是真事（GraphQL 必须带 token，App 不内置 token ⇒ 只能登录）；首页「社区·聊两句」确实占速览格之一 | `core/.../community/CommunityScreen.kt:34,61-66`；`app/.../home/AppHomeHost.kt:311-322` |
| #30 | UX/产品 | **成立** | **高**（阻塞写路径对齐决策） | 事实全对：业务在 `:8080`、支付页在 `:80`、两套是不同部署、`去支付` = 内置浏览器先过 OAuth 再跳支付页；`:core` 的 serve 形态下 `paymentUrl`/`browserLoginUrl` **都是空串**⇒「去支付」点了没动作（契约 §5.3.2 已记缺口） | `data/.../auth/VenueLogin.kt:40,46,48-51`；`data/.../venue/VenueApi.kt:41,380-382`；`core/.../venue/VenueScreen.kt:137-140`；`app/.../nav/AppNavHost.kt:363`；`app/.../browser/BrowserScreen.kt:403-405,419-420,441-444`；`core/.../core/net/CampusVenueApi.kt:137-140,363-365` |
| #31 | UI/UX 无障碍 | **成立** | 低 | 101 处 `contentDescription = null`、0 处 `minimumInteractiveComponentSize()`、`size(15–18.dp)` 图标与 `minHeight = 32.dp` 按钮都是真的；但审计点的 `CommunityScreen.kt:207` 实为 **221** | `core/.../community/DiscussionsScreen.kt:212`；`CommunityScreen.kt:221`；`community/DiscussionReplies.kt:106-110`；§0.3 H |
| #32 | 隐私/产品 | **成立** | 中（比审计高） | 昵称默认填真名 + `Dimensions.identity` 默认 true 把班级/省份/专业一并编进码，都是真的；分享码本就是发给陌生人的，泄露面比审计说的小（无学号），但「静默默认」这点值得改 | `core/.../social/MatchScreen.kt:152-156`；`core/.../social/MatchProfile.kt:119,296-303,347-348` |
| #33 | 工程/安全 | **成立** | **高** | `miuix = "latest.release"` 逐字属实，且 `:app` 依赖全绑在它上面；仓库里**有**两条缓解（拒预发布 + 30 分钟解析缓存），但不解决可重现性 | `gradle/libs.versions.toml:12`；`app/build.gradle.kts:175-188,253-260` |
| #34 | 工程/安全 | **部分成立** | 中 | heredoc 内联 `${{ }}`、token 拼进 remote URL（并且还拼进 curl 的 query 与 JSON body）都真；但「CI 完全不跑单测」「没有 PR 检查工作流」**不成立**：`build.yml:46` 跑 `:app:testDebugUnitTest`，且已有 `core-gate.yml`（`pull_request` 触发） | `.github/workflows/build.yml:46,125-127,186-187,199-202,213,244`；`.github/workflows/core-gate.yml:1-20,60-80` |
| #35 | 服务端 Bug | **无法判定** | — | `server/leaderboard/index.js` 与 `nginx-Snippet.conf` 在本仓库**任何分支都不存在**（`server/` 现在是 Kotlin `:server` 模块）；服务端可能已迁出本仓 | `ls server/` → `build/ build.gradle.kts src/`；`git log --all -S clientIp` 空 |
| #36 | 工程/可持续 | **不成立**（残留半条） | 建议 | `.github/ISSUE_TEMPLATE/{bug,feature,announce,config}.yml` + `pull_request_template.md` + `PULL_REQUEST_TEMPLATE/announce.md` **都在**（`origin/main` 上也在）；真缺的只有 `CONTRIBUTING.md` / `SECURITY.md` | `find .github -type f`；`git ls-tree -r --name-only origin/main \| grep ^.github` |

**「高」条目（我重判）**：#30、#33。次高：#21、#24、#25、#26、#29、#32、#34。

---

## 2. 逐条复核

### #21 `llmHistory` 无限增长 + `ContextBudget.fit` 结果被丢弃

**审计断言**：`AgentRunner` 每轮把 `ContextBudget.fit(history, config.contextTokens)` 算出来的 `fitted` 只用于当次请求体、从不回写历史，历史无界增长；`persist()` 用 `llmHistory.drop(1).map { JSONObject(it.toString()) }` 全量深拷贝且跑在主线程。

**我读到的代码事实**（HEAD）

```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentRunner.kt:47-49
private const val DEFAULT_TOOL_RESULT_CHARS = 4000
private const val SEARCH_TOOL_RESULT_CHARS = 8000
private const val FETCH_TOOL_RESULT_CHARS = 12_000
// :139-141  —— 请求体直接就是「全量 messages」，没有 fit/裁剪这一步
val reqBody = buildJsonObject { … put("messages", messagesForProvider(messages, config)) … }
// :221  —— 本轮工具结果就地追进历史（内容已被 capToolResult 截过）
for (tc in sr.toolCalls) { … messages.add(assistantMsg); toolResults.forEach { messages.add(it) } }
```

```kotlin
// core/.../agent/AgentViewModel.kt:104   private var llmHistory: MutableList<JsonElement> = mutableListOf()
// :284  落盘把**整段**历史序列化进去
store.save(id, StoredConversation(stored, JsonArray(llmHistory).toString(), lastTotalTokens, contextExhausted), title)
// :394  currentJob = viewModelScope.launch { … }   ← viewModelScope = Main，persist 在 :656 的 finally 里
// :63   private const val CONTEXT_TOKEN_LIMIT = 800_000L
// :469  AgentVision.pruneOldImages(llmHistory)     ← 全仓唯一一处对历史的「裁」（只裁图，不裁 tool 文本）
```
```kotlin
// data/.../agent/AgentSessionStore.kt:67-75 —— save() 是同步阻塞 IO：没有 withContext(Dispatchers.IO)
override fun save(id, convo, title) { writeConvo(id, convo); … writeIndex(newIdx) }   // :93-95 writeText(...)
```

**数字复核**

- `ContextBudget.fit` / `buildRequestBody` / `contextTokens`：`git log --all -S` 三处**全空** ⇒ 这两条「已确认」证据不存在。HEAD 的 `ContextBudget` 是**按数据源限长**的 `clip()/payload()`（`ContextBudget.kt:29,58`，全 76 行），压根没有 `fit(history, tokens)` 这个 API。
- `llmHistory.drop(1)…`：`git log --all -S` 空，HEAD/origin-main 的 `persist()` 里也没有。
- 真实存在的：「工具结果入库前被截」是**派生的、每次请求现算**（`capToolResult`），上限 **4,000 / 8,000 / 12,000 字符**（审计说「单次可达 8,000」偏小）；`TOOL_CALL_FUSE = 20`（`AgentRunner.kt:101`）限单问工具次数；`CONTEXT_EXHAUSTED` 达 800k tokens 后**拒绝继续发送**（`AgentViewModel.kt:341-343`）——所以「无限增长」在实践中被这三个上限兜着，但**每轮请求成本仍随轮数线性增长**（这正是审计想说的痛点，结论对、机制错）。

**判定与理由**：**部分成立**。真痛点是「无历史压缩 + 每轮整段重发 + 整段序列化落盘 + 落盘在主线程做 JSON 与文件 IO」；假的是审计给出的机制与代码引文。注意 `persist()` 的深拷贝说法（`JSONObject(it.toString())`）在 HEAD/origin-main 都换成了 `JsonArray(llmHistory).toString()`，**但主线程代价同样存在**（这次是序列化整段历史 + 两次 `writeText`，`index.json` 每次 `save` 还要 `readIndex()` 再写回）。

**真优先级：中**。理由：长会话（十几轮 + 每轮 4–12KB 工具结果）时，每回合请求体可在几十万字符量级、每次落盘同步写两份文件；这是可感知的卡顿 + 用户自费 token 的线性增长，但不会丢数据、不会崩。

**最小修法**：把 `persist()` 的「序列化 + 写盘」移进 `host.io`（`viewModelScope.launch(host.io)` 或让 `AgentSessionStore.save` 内部 `withContext(Dispatchers.IO)`），并给历史里的老 `tool` 消息做**就地摘要替换**（保留最近 N 轮，之前的替换成一句「已完成：查询课表，结果见上文摘要」）。**不要**引入审计建议的 `drop(1)` 语义（会破坏 system 必须待在第 0 位的前缀缓存约定）。

**是否改变 Android 现有可见行为**：改调度与历史压缩 ⇒ **会**（老会话再打开时早期工具结果变成摘要；落盘线程变化不影响 UI，但压缩会改变模型看到的上下文 ⇒ 老会话回答可能略有差异）。建议按「新会话生效、老会话原样」切。

---

### #22 每轮塞 27/29 个工具的完整 schema；同回合工具串行

**审计断言**：登录后把 29 个工具全量 schema（约 3,500+ tokens）塞给模型；同回合多个 `tool_calls` 用 `for` 串行执行，可改并发。

**我读到的代码事实**

```kotlin
// core/.../agent/AgentToolCatalog.kt:24  fun agentToolDefinitions(honored: Set<AgentCapability>): String
// :114 / :125 / :185  if (AgentCapability.ALARM in honored) { arr.add(agentTool("set_alarm", …)) } …
grep -c 'arr.add(agentTool(' AgentToolCatalog.kt   → 27
// core/.../agent/AgentViewModel.kt:405   val registry = tools ?: host.tools(config.searchEngine).also { tools = it }
// core/.../agent/AgentRunner.kt:131  val toolDefs = AppJson.parseToJsonElement(tools.toolDefinitions).jsonArray
// :138,156  val allowTools = toolCallCount < TOOL_CALL_FUSE … if (allowTools) { put("tools", toolDefs) }
// :221  for (tc in sr.toolCalls) { … }        ← 串行，真
```

**数字复核**
- 工具数：HEAD **27**、`origin/main` **27**（`grep -c 'arr.add(tool(' /tmp/maintool.kt` = 27）——审计的 **29 不成立**。
- 体量：`AgentToolCatalog.kt` 字面量 3,484 字符（CJK 1,458）+ 69 个参数 + 27 行结构 ⇒ 估 JSON ≈ 7.9k 字符；**token 我没有实测**（本机无 tiktoken、禁止跑构建），粗估 3.0k–5.0k —— 审计的「3,500+ tokens」在量级上落在区间内，但**不是它给的数字的来源**。
- 「按登录态挂载」：**不成立**，而且比审计说的更严重 —— 工具表按 `AgentCapability.honored`（端能力）画行，**与登录态无关**：没登录也一样收到 27 个 schema，只是调用时回「未登录」。
- 「`parallel_tool_calls` 优化」：`grep -rn parallel_tool_calls` **0 处**，请求体里没有这个字段（真没做，但 OpenAI-compatible 各家默认是 `auto`，串行执行是**本地**行为）。

**判定与理由**：**部分成立**。成立部分：全量 schema 无条件随每次请求发送（含纯闲聊）、同回合串行执行。不成立部分：29 个工具、「已登录才全量」、「可以 50%~70% 提速」的量化收益（多工具同发的总延迟还受上游站点响应与 `SAME_SITE_MIN_INTERVAL_MS=800ms` 站点限流支配，且并发会改变同一站点两次调用的时序，需要重新论证）。而且工具 schema 位于 prompt 前缀，DeepSeek/OpenAI 的前缀缓存**本就会**覆盖它，边际成本没有审计说的那么高。

**真优先级：低**。理由：这是省 token/降 TTFT 的优化，不是缺陷；改动会碰共享调度循环（三端同一份），收益未被量测。若要做，先量：把 `tools` 从请求里摘掉的 token 差、以及典型 3 工具问句的端到端耗时。

**最小修法（可选）**：只做「工具表 = 当前可用能力 + 用户可在设置里关掉的工具组」这一条轻量路由，别改调度循环；并发化那一半先不做。

**是否改变 Android 现有可见行为**：并发执行 ⇒ **会**（工具气泡顺序、失败语义、站点限流都变）；只做工具表裁剪 ⇒ 也会（模型可能选不到某个工具）。两者都要在本端先验证再对齐其它端。

---

### #23 画像竞态：`loadSession` 传 `null` 导致姓名丢失、system prompt 永不刷新

**审计断言**：`AgentViewModel.kt:228` 里 `AgentPrompt.build(null, …)` 把 `loginState` 硬编码成 `null`，于是所有历史会话的 system prompt 里没有姓名/学号/年级；且 `llmHistory[0]` 永不刷新。

**我读到的代码事实**

```kotlin
// core/.../agent/AgentViewModel.kt:398-402  「系统提示**一段对话只生成一次**：对话里还没有它（新对话，或老数据缺了）才生成，
//   之后原样复用。名字、偏好、画像中途变了，都从下一个新对话起生效——中途改写等于篡改上下文…前缀缓存也整段作废。」
// :416  val hasSystem = llmHistory.size > 0 && runCatching { llmHistory[0].jsonObject.get("role")?.stringValue == "system" }…
// :426-430  val systemPrompt = AgentPrompt.build(assistantName = config.effectiveName,
//              userContext = registry.userContext(allowNetwork = allowProfileNetwork), memoryBlock = registry.memoryBlock())
// :245  切会话时把落盘的 llmHistory 整个读回来（system 也在里面）→ 注释「落盘的 system prompt 原样沿用，缺了才在下一轮生成」
// app/.../agent/AgentTool.kt:148  override suspend fun userContext(allowNetwork: Boolean): String = withContext(Dispatchers.IO) { … }
//   :165-176  allowNetwork 且本地缺姓名/学院时，**当场 await** 一网通办 getUserInfo() 并缓存
// core/.../agent/AgentPrompt.kt:16-20  build(assistantName, userContext = "", memoryBlock = "")  ← 没有 loginState 形参
```

**数字/事实复核**：`git log --all -S "AgentPrompt.build(null"` **空**；`AgentPrompt.build` 的签名从来没有 `loginState` 参数。审计引的 `llmHistory.add(JSONObject().put("role","system")…)`、`loadSession` 这类写法在 HEAD/origin-main 都不存在（`loadSession` 现在是 `switchSession`）。

**判定与理由**：**不成立**（就审计给出的因果与证据）。残留的真实事实是一条**已文档化的设计取舍**：system prompt 一段对话只生成一次 ⇒ 会话进行中补上的画像/新偏好要等**新对话**才生效（审计担心的「永久丢失」在新对话里自愈）。另有一点真实的时序问题：首轮生成时若 `userContext(allowNetwork=true)` 那一趟一网通办超时/未登录失败，`runCatching` 会把 fail 当空画像，本会话就一直缺姓名（窗口存在，但下一次新对话修复）。而审计的建议（每轮重写 `llmHistory[0]`）会**主动打破**前缀缓存与「前后人设一致」，代价大于收益。

**真优先级：建议**（不修）。理由：设计有明确取舍说明，副作用是「改了偏好要新开对话才生效」，这是产品语义问题而不是 bug。

**最小修法（若真要动）**：只在「本轮 `userContext` 比生成时多出了姓名/校区这类**从未有过**的关键字段」时重建一次 system（一次性、带日志），别每轮重写。

**是否改变 Android 现有可见行为**：会（同一会话里模型人设变化）⇒ 不建议改。

---

### #24 `SessionKeepAlive` 后台无限轮询、无开关、不随前后台暂停

**审计断言**：`SessionKeepAlive` 每 10 分钟无限轮询 WebVPN + 最多 4 个常用站点，无视前后台，且设置里没有开关。

**我读到的代码事实**

```kotlin
// app/src/main/java/com/xjtu/toolbox/auth/SessionKeepAlive.kt
:23  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
:27  const val DEFAULT_INTERVAL_MIN = 10L
:33  fun start(context: Context) {
:34      if (loopJob?.isActive == true) return
:36      if (!prefs.isEnabled()) { Log.d(…); return }          ← 有开关字段
:42      while (isActive) { val intervalMs = prefs.intervalMinutes().coerceAtLeast(1) * 60_000L
:44          delay(intervalMs); if (!prefs.isEnabled()) break; runOnce() }   ← 无前后台判断
:70  fun isEnabled(): Boolean = sp.getBoolean(KEY_ENABLED, true /* 上游默认开启 */)
```
```kotlin
// app/.../MainActivity.kt:77   SessionKeepAlive.start(this)      ← 唯一启动点（onCreate）
// app/.../auth/AppLoginState.kt:416-418
com.xjtu.toolbox.auth.SessionKeepAlive.sessionRefresher = {
    sessionManager.warmUp(credentialStore.topSites(com.xjtu.toolbox.auth.SessionManager.WARM_SITES)) }
// data/.../auth/SessionManager.kt:562  const val WARM_SITES = 4 ;  :353-379 warmUp：逐站 ensureLogin(silent=true) + WebVPN 补登
// data/.../auth/SessionManager.kt:359  if (!hasSessionHint(site)) continue   ← 只预热「本来就有会话迹象」的站
```

**复核**
- `grep -rn "ProcessLifecycleOwner\|ON_STOP" --include=*.kt app/src/main` → **0 处** ⇒ 确实没有前后台感知。
- `grep -rn "KeepAlivePrefs" --include=*.kt .` → **只在 SessionKeepAlive.kt 自己**里出现 ⇒ 设置里确实**没有 UI**（开关字段存在但没人写）⇒ 审计这条成立。
- 站点数：`WARM_SITES = 4` + WebVPN ⇒ 审计「最多 4 个常用站点」准确。

**判定与理由**：**部分成立**。「无开关」成立（代码里有 pref 骨架但 UI 没接，等于默认恒开）；「无前后台感知」成立；「无视应用生命周期每 10 分钟无限轮询」成立。不成立的是审计给的引文（`keepAliveJob` / `isEnabled(context)` / `sessionManager.warmUp(topKeys)` 这些符号名在 HEAD 已改）。

**真优先级：中**。理由：它只在 MainActivity 起来过之后才跑（不是开机自启），且只预热已有会话的站；但一旦用户用过 App 又把它丢在后台，保活会以 10 分钟为周期持续向学校 CAS/教务发静默重登请求 —— 对**校方风控**的影响是真实风险面（`CasGate` 虽有熔断/退避兜底），对电量是次要成本。

**最小修法**：给设置页接上已有的 `KeepAlivePrefs`（开关 + 间隔），并在 `AppRoot` 里用 `LifecycleEventObserver`（或 `ProcessLifecycleOwner`，`:app` 已有 lifecycle-runtime-ktx 依赖）在 `ON_STOP` 后暂停循环、`ON_START` 恢复 —— **`:app` 内的改动，不动 `:core`/`:data` 的三端共享面**。

**是否改变 Android 现有可见行为**：会（多一个设置项；后台不再静默保活 ⇒ 久置后再打开某些站点可能要重登一次）。这两项正是审计要求的行为变化，得由产品确认。

---

### #25 思源学堂 N+1 串行拉取

**审计断言**：`HomeStatsRefresher` / `LmsDeadlineWorker` 对每门课串行拉活动、对每条未截止作业再查一次提交态；上游其实自带聚合待办接口 `LmsApi.getTodos()`（`LmsApi.kt:194`）。

**我读到的代码事实**

```kotlin
// app/.../lms/LmsDueCollector.kt:15-21  「逐门课拉活动，串行」
suspend fun activities(api: LmsApi, courses: List<LmsCourseSummary>) = courses.map { c ->
    c to runCatching { api.getCourseActivities(c.id) }…getOrDefault(emptyList()) }
// :42  逐条作业查提交态（串行，且在 collect 里对外层 dueItems 依次调用）
else -> d.copy(submitted = runCatching { api.getUserSubmitCount(d.activityId) > 0 }…getOrDefault(false))
// data/.../lms/LmsApi.kt:146-147  getCourseActivities → GET $baseUrl/api/courses/$courseId/activities
// :198-201  getUserSubmitCount → GET $baseUrl/api/activities/$activityId（读 user_submit_count）
// app/.../home/HomeStatsRefresher.kt:344  val perCourse = LmsDueCollector.activities(api, inTerm)   // inTerm = 最新学期课程
// :157「思源学堂：一天一次。要逐门课查活动，比别的源贵」  :328「成本：该学期每门课一次活动请求（实测 11 门）…」
// app/.../notification/LmsDeadlineWorker.kt:55  LmsDueCollector.activities(api, api.getMyCourses())  ← 全量课程
// :97-99  PeriodicWorkRequestBuilder<LmsDeadlineWorker>(4, TimeUnit.HOURS, 1, TimeUnit.HOURS)
```

**数字复核**
- 请求条数 = 1（`/api/my-courses`）+ N（课程活动）+ M（未截止作业的提交态）。`HomeStatsRefresher` 用**最新学期**课程（注释实测 11 门）⇒ 约 1+11+M ≈ 15–22；`LmsDeadlineWorker` 用**全部**课程，每 **4 小时**一轮。所以审计「一次刷新 20~30 个 HTTP 请求」的量级**基本对**（略偏高）。
- `LmsApi.getTodos()` / `api/todos`：`git log --all -S` 与当前源码**都没有** ⇒ 「上游自带聚合接口、改一次请求就行」这个前提在本仓库**无任何依据**（可能来自其他项目的记忆或臆测）。有一条已知的本地优化是真的、也确实做了：`collect()` 里 `known` 集合让「已查到已交」的作业不再重复查提交态（`:37-41`）。

**判定与理由**：**成立**（N+1 是真的、串行是真的）；但修法的依据不成立 ⇒ 我不接受「优先改用 `getTodos()`」这个建议（会造成「按臆测的接口改核心取数路径」的风险）。

**真优先级：中**。理由：频率有 TTL 兜底（首页 1 天 1 次、Worker 4 小时 1 次），单次总量 ~15–22 个串行请求；真正的问题是**串行**（一次刷新墙钟时间≈  请求数 × RTT）与 Worker 用全量课程两件事，不是「每轮 30 个请求」本身。

**最小修法**：把 `LmsDueCollector.activities` 的 `courses.map{}` 换成**有限并发**（`Semaphore(3)` + `coroutineScope{ async }`，注意 `CasGate` 的站点级串行会自然兜住登录侧），提交态查询同理限并发；Worker 侧把课程收敛到与首页同一口径（最新学期）。**先验证**上游是否真有聚合接口（本仓库无依据），有再考虑合并请求。

**是否改变 Android 现有可见行为**：不改变 UI，只改变请求时序与后台耗时 ⇒ 可见行为基本不变（列表内容一致、顺序仍按原口径排序）；但并发会改变失败发生的先后（`LmsDueCollector.collect` 的「查失败当没交」策略在并发下要重新确认）。

---

### #26 待办小组件冷启动未恢复 `AccountContext`

**审计断言**：`TodoWidgetProvider.update` 既不 `InboxStore.init(context)` 也不像 `CampusCardWidgetUpdater` 那样兜底恢复 `AccountContext.activeAccountId`，冷启动/重启后可能读不到当前账号的待办。

**我读到的代码事实**

```kotlin
// app/.../widget/TodoWidgetProvider.kt:62-66
fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    if (appWidgetIds.isEmpty()) return
    val now = System.currentTimeMillis()
    val todos = InboxRules.todos(InboxStore.load(), now)      // ← 没恢复账号，直接 load()
// :107-110  class TodoWidgetProvider : AppWidgetProvider() { override fun onUpdate(...) = TodoWidgetUpdater.update(...) }
```
```kotlin
// 兄弟小组件都恢复：app/.../widget/CampusCardWidgetProvider.kt:28-33
if (com.xjtu.toolbox.account.AccountContext.activeAccountId == null) {
    runCatching { com.xjtu.toolbox.account.AccountStore().activeAccountId()?.let { AccountContext.activeAccountId = it } } }
// 同 ScheduleWidgetProvider.kt:503-510（注释「从 AccountStore 恢复激活账号，保证 DataCache/Room 命名空间正确」）
// core/.../inbox/InboxModels.kt:254-266  fun load(account: String? = AccountContext.activeAccountId) … prefs(account)…
// core/.../account/AccountContext.kt:56-59  fun suffixFor(accountId: String?): String { val id = accountId ?: return "default"; … "_"+… }
// app/.../XjtuApp.kt:59-90  onCreate 只做 initAndroidPlatform/initDataPlatform/CrashReporter/通道/调度器 —— **不建 AppLoginState**
// app/.../auth/AppLoginState.kt:363  AppLoginState 只在 AppLoginStateViewModel 里构造（= MainActivity 的组合里）
```

**复核**：`grep -rn "InboxStore.init" ` → **0 处**（`:core` 的 `InboxStore` 已经没有 `init`，改用 `keyValueStore` 平台缝 + `XjtuApp.onCreate` 里 `initAndroidPlatform`，见 `KeyValueStore.android.kt:12-19`）。所以审计给的修法**在 HEAD 上已不适用**，但它要修的现象是真的：`AccountContext.activeAccountId` 为 null（未登录 / 冷启动还没建 `AppLoginState`）时，`InboxStore.load()` 会去读 `suffixFor(null) == "default"` 那个命名空间（`keyValueStore("inboxdefault")`）而不是 `inbox_<学号>` ⇒ 小组件画「暂无待办」。`AccountStore()` 无参构造在 HEAD 可用（兄弟组件就这么写的）。

**判定与理由**：**成立**（BUG，不是推测）。触发路径具体是：系统发 `APPWIDGET_UPDATE`（重启后/换包后 `WidgetRefreshReceiver` 的 30 秒延迟重刷）冷启进程 → `Application.onCreate` 只做平台初始化 → 直接进 `TodoWidgetProvider.onUpdate` → 读错命名空间。与 `ScheduleWidgetProvider`/`CampusCardWidgetProvider` 的不一致本身就是证据。

**真优先级：中**。理由：只影响小组件首屏显示（用户点进去 App 后就正常），不丢数据、不影响主流程；但它是**确定可复现**的功能缺陷，修起来是 5 行。

**最小修法**：在 `TodoWidgetUpdater.update()` 开头照抄兄弟组件的三行：

```kotlin
if (com.xjtu.toolbox.account.AccountContext.activeAccountId == null) {
    runCatching { com.xjtu.toolbox.account.AccountStore().activeAccountId()?.let { AccountContext.activeAccountId = it } }
}
```

（`InboxStore.init(context)` 那半条不要抄 —— 该 API 已不存在。）

**是否改变 Android 现有可见行为**：会修好一个**错误**行为（未登录/冷启动下小组件显示错的内容）⇒ 属于「向 Android 应有的现状对齐」，不涉及两端口径分歧。

---

### #27 超大文件与低覆盖率

**审计断言**：`AgentTool.kt` 3,019 行、`VenueScreen` 1,899、`ScheduleScreen` 1,783、`LibraryScreen` 1,763、`CampusCardScreen` 1,531、`LeaveScreen` 1,528、`AppChangelog` 1,472；`app/src/main` 537 个源文件、`app/src/test` **8 个**测试文件、覆盖率约 **1.5%**；CAS 状态机 / WebVPN 编解码 / Jsoup 解析器 **0 单测**。

**我读到的代码事实与数字**（全部自己跑，见 §0.3 A–E）

- 最大文件：`AgentTool.kt` **2408**（HEAD）/ 2907（`origin/main`），最近 30 次提交区间 2907–2954 ⇒ **3019 不存在**；`VenueScreen` **1110**（审计 1899）、`ScheduleScreen` **1380**（1783）、`LibraryScreen` **861**（1763）、`CampusCardScreen` **1561**（1531，接近）、`AppChangelog` **650**（1472）；`LeaveScreen.kt` **在本仓库任何 ref 里都不存在**（`git log --all --diff-filter=A -- '*LeaveScreen.kt'` 空）。
- HEAD 生产 `.kt` 861 个 / 163,798 行；`app/src/main` 138 `.kt`（219 全文件）。`origin/main`：`app/src/main` 511 文件 / 424 `.kt`，全仓 `.kt` 532 个。「537 个源文件」两头都对不上（拿 511 反推 ⇒ 审计基线大概就在 `origin/main` 附近）。
- 测试文件数：`app/src/test` **76** `.kt`（`origin/main` **106**）⇒ 审计的「8 个」不成立；它点名的 8 个里有 **4 个从不存在**：`InboxRulesTest`、`SelfTodoRulesTest`、`NetCodecTest`、`GameTests`（`git log --all --diff-filter=A -- "*<Name>.kt"` 全空）；`ScoreCalculatorTest` 现在就住在 `core/src/commonTest/.../score/ScoreCalculatorTest.kt`；`MatchProfileTest`/`OnlineGameSessionTest`/`BleFramingTest` 仍在 `app/src/test`。
- 覆盖率：见 §0.3 四个口径（22.7% / 30.7% / 52.1% / 20.7%）—— **都不是** 1.5%；1.5% = 8/537，两个因子都取错。
- 「CAS 状态机 / WebVPN URL 编解码 / Jsoup 解析器 0 覆盖」被现存测试直接证伪：
  - `app/src/test/java/com/xjtu/toolbox/auth/`：`LoginGateTest`、`CampusProbeTest`、`JwtTest`、`SiteSessionAccountSwitchTest`、`LoginLandingTest`
  - `app/src/test/java/com/xjtu/toolbox/nav/UrlCodecParityTest.kt:1-20`（注释：「`AppRoute` 的 id 已写进用户数据…编码结果差一个字符老用户就解析不出来」—— 这是**与 java.net 的差分测试**）
  - 解析器：`library/LibraryPagesTest.kt`（内联 HTML 夹具）、`library/LibrarySeatQrTest`、`lms/LmsHtmlTextTest`、`lms/LmsActivity*Test`、`attendance/KqPortalTest`、`data/src/jvmTest/{faculty,notification,coupon,judge,fitness}/*`，共 18 个测试文件含 `<div>/<html>/Jsoup` 夹具。
  - 全仓测试资源里的**非 .kt 夹具只有 1 个**（`app/src/test/resources/qr/seat_damaged_finder.png`）⇒ 审计「没有基于真实 HTML 快照的测试」这半条**方向对**（夹具是内联字符串，不是脱敏快照文件）。

**判定与理由**：**部分成立**。成立：`AgentTool.kt` 2408 行确实是全仓最大的生产文件、且「Schema + 业务调用 + HTML 解析 + 本地设置」仍挤在一个文件里（`grep` 可见 `getSchedule/searchSchoolCourses…` 与 `fixGbk`、`memory.remember` 同文件）—— 拆分建议方向成立。不成立：其余 6 个行数、537/8/1.5%、以及「核心解析器 0 覆盖」，这些数字**没有一个是可复现的**。

**真优先级：低**。理由：KMP 重构已把这些文件按「一屏一提交」搬进 `:core`/`:data` 并各自缩水（ScheduleScreen 1783→1380、LibraryScreen 1763→861），趋势已经在往审计希望的方向走；剩下的 `AgentTool.kt` 拆分有真实收益但要碰三端共享的取数面，收益/风险比一般。

**最小修法**：把 `AgentTool.kt` 里与工具表**无关**的两坨先摘出去（HTML 解析辅助 → `:data` 的解析层；本地设置读写 → 已有的 `AgentCapability.APP_SETTINGS` 实现处），不要一次拆成 5 个 `*Tools.kt`（会把工具注册表打散，`core` 的 `AgentToolCatalogTest` 逐档核对会跟着碎）。

**是否改变 Android 现有可见行为**：纯移动代码 ⇒ **不变**（只要解析与设置语义逐字保留）。

---

### #28 首页 shader 每帧重绘 6 秒 × 冷启动会话恢复

**审计断言**：`HomeTab.kt:126-132` 的 `MeshBackground(runForMillis = 6_000L)` 在前 6 秒每帧驱动重模糊，与冷启动「11 个站点会话恢复」叠加导致掉帧。

**我读到的代码事实**

```kotlin
// core/.../home/HomeScreen.kt:272-278（HEAD；审计引的 app/.../home/HomeTab.kt:126-132 是 origin/main 的位置）
MeshBackground( modifier = Modifier.matchParentSize(), lightVertexColors = heroMesh, darkVertexColors = heroMesh,
    // 卡片在玻璃顶栏/底栏的取样范围里，一直流动会让静止的首页也持续重模糊，流动一小段就停。
    runForMillis = 6_000L, )
// core/androidMain/.../MeshBackground.android.kt:60-73  running = animated && LocalPageVisible.current && lifecycleState.isAtLeast(RESUMED)
//   LaunchedEffect(periodMillis, runForMillis) { while (System.nanoTime() < stopAt) { phaseState.floatValue = …; delay(MESH_FRAME_MS) } }
//   :102  private const val MESH_FRAME_MS = 33L    ← 约 30 帧/秒，不是每 vsync
// app/.../main/AppRoot.kt:301-320  SessionRestore.run()：「有凭据但还没有任何会话时，后台**只登教务一个站点**；
//   其余站点进页面时按需走 SSO，**冷启动不做别的主动认证**」 → ensureSite(LoginType.JWXT)  ← 1 个站
// app/.../auth/AppLoginState.kt:390-407  共注册 18 个站点（不是 11）
```

**判定与理由**：**部分成立**。6 秒流动 + 玻璃实时模糊采样是真的（`MESH_FRAME_MS=33` ⇒ 30fps 相位推进，不是审计说的「每帧」），“冷启动叠加 11 个站点会话恢复”**不成立**（冷启动只登 1 个站）。首屏那 6 秒确实最忙（`HomeStatsRefresher`、缓存读、Splash、`requestUpdate` 四个小组件），但**掉帧**是推断，仓库里无测量记录（未跑性能测试，我也没有设备）。

**真优先级：低**。理由：着色器只驱动一个小图层的相位（`phase = { phaseState.floatValue }` 只在绘制阶段读，注释说明「不重组」），且已经做了两条优化（30fps 定时器代替无限动画、`LocalPageVisible` + RESUMED 门控）；这是「已经在做优化」的项，不是明显缺陷。

**最小修法（若真掉帧）**：`isRestoring` 为 true 时传 `runForMillis = 0L`（一行），或把 6 秒降到 3 秒；**不要**在滚动时暂停（会引入新的状态机）。

**是否改变 Android 现有可见行为**：会（首页卡片动效时长/是否流动）⇒ 视觉变化，需产品确认。

---

### #29 社区强制 GitHub 登录

**审计断言**：`CommunityScreen` 未登录时整屏是登录页；首页 Hero 第三格是「社区 · 聊两句」；即使用户只想看帖也被挡。

**我读到的代码事实**

```kotlin
// core/.../community/CommunityScreen.kt:32-34
 * 社区：本仓库的 GitHub Discussions。必须登录 GitHub 才能看，App 里不内置任何 token。
// :61-66
if (signedIn == null) { CommunityLoginScreen(session, deviceAuth, onBack, onOpenLegacyFeedback) }
else { CommunityForum(session, signedIn, onBack, onOpenLegacyFeedback) }
// app/.../home/AppHomeHost.kt:311-322  HomeGlanceItem(label = "社区", value = "聊两句", caption = "提建议 · 报问题", route = AppRoute.Community)
```

**判定与理由**：**成立**（事实层面全部核实；审计的 `CommunityScreen.kt:207` 行号在 HEAD 是 221，`Icons.Outlined.Forum, null` 那处在 :221）。技术上的根因是 GitHub Discussions 只能走 GraphQL，而 GraphQL **必须**带 token ⇒ 客户端不内置 token 就只能要求用户登录（与「80% 大一新生没有 GitHub 账号」这个**未经验证**的统计无关，我的判断不依赖它）。

**真优先级：中**。理由：这是产品决策（首屏黄金位 + 只读也设门槛），不是代码 bug；改法要去 `:server` 加只读代理（涉及自己拿只读 token、缓存、限流），成本与风险都不小，且与「serve 模式只监听本机」的当前定位冲突。

**最小修法**：决策先行。若要走只读代理：在 `:server` 加一个带 60 秒内存缓存的 `GET /api/community/discussions`，客户端 `signedIn == null` 时调它渲染只读列表，仅在「发帖/回复/点赞」时才引导登录。**不要**在客户端内置 token（审计说得对：App 里不内置任何 token 这条纪律应保持）。

**是否改变 Android 现有可见行为**：会（未登录用户能看到帖子列表）⇒ 明确的可见行为变化，需产品确认后再三端对齐。

---

### #30 场馆下单在 8080、支付跳内置浏览器走 80 端口 OAuth

**审计断言**：原生界面在 8080 端口下单；点「去支付」要切内置浏览器在 80 端口重新走一遍 OAuth；链路割裂、易超时丢单。

**我读到的代码事实**

```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/auth/VenueLogin.kt
:40  const val BASE_URL = "http://202.117.17.144:8080"
:42-46 * 支付页在 80 端口的另一套站点上，且要求浏览器自身的会话，不能把 App 内的 cookie 拼进 URL。
      const val PAY_BASE_URL = "http://202.117.17.144"
:48-51 const val VENUE_OAUTH_URL = "https://org.xjtu.edu.cn/openplatform/oauth/authorize?…appId=1659&redirectUri=http://202.117.17.144:8080/web/cas/oauth2url.html"
// data/.../venue/VenueApi.kt:41  * 支付页仍在 80 端口，要浏览器自身的会话：在内置浏览器里先走一遍 OAuth 登录再进支付页。
// :380-382  fun paymentUrl(orderId: String) = "$PAYMENT_BASE/pay/show.html?id=${URLEncoder.encode(orderId, "UTF-8")}"
// core/.../venue/VenueScreen.kt:137-140
/** 先在内置浏览器里过一遍登录（带着 App 的统一认证 cookie，一般免输），再落到支付页。 */
fun pay(order: OrderInfo) = onOpenBrowser(source.browserLoginUrl, source.paymentUrl(order.orderId))
// app/.../nav/AppNavHost.kt:363  onOpenBrowser = { url, then -> router.open(AppRoute.Browser(url, then)) }
// app/.../browser/BrowserScreen.kt:403-405  private var thenPending = thenUrl.isNotBlank()
// :441-444  if (thenPending && url != null && !isAuthHop(url)) { thenPending = false; view?.loadUrl(normalizeUrl(thenUrl)) }
// :419-420  syncCookiesToWebView(site, listOfNotNull(host))   ← 每次 onPageStarted 按当前 host 注入 App 的 cookie
// core/.../core/net/CampusVenueApi.kt:137-140  serve 形态 browserLoginUrl = ""  ；:363-365 paymentUrl = "" （契约 §5.3.2 末尾记的已知缺口）
```

**逐跳核清（回答「哪一步跳哪个端口、OAuth 在哪里重来」）**

1. 原生：`VenueApi` 全部业务在 `http://202.117.17.144:8080`（`BASE_URL`），会话是 8080 上的 `SESSION` cookie（`VenueSession.kt:15`）。
2. 下单：`order/tobook.html`（8080）。
3. 点「去支付」：`VenueScreen.pay()` → `AppRoute.Browser(url = VENUE_OAUTH_URL, then = "http://202.117.17.144/pay/show.html?id=…")`。
4. 内置 WebView 先载 `org.xjtu.edu.cn` 的 OAuth（appId=1659）→ CAS → `202.117.17.144:8080/web/cas/oauth2url.html`；WebView 的 `onPageStarted` 会把 App 侧该 host 的 cookie 注入（`BrowserScreen.kt:419-420`，cookie **不分端口**）。
5. `onPageFinished` 里第一次「不是登录跳转页」（`isAuthHop`：login/cas/org.xjtu.edu.cn login/webvpn login/带 ticket）就立刻 `loadUrl(thenUrl)`，落到 **80 端口**的支付页。
6. 因此「在 80 端口重新走一遍 OAuth」这件事：代码**刻意让浏览器先去 8080 的 OAuth 入口**再跳 80，注释明说 80 那套「要浏览器自身的会话」。至于 80 端口最终是靠 8080 的 `SESSION` cookie（cookie 不区分端口会被带过去）还是靠 80 那套自己的 CAS 客户端再跳一次，**本仓库无法判定**（没做在线探测）；能确定的是 **App 侧没有任何对 80 端口站点的预热/请求**（全仓只有 `PAY_BASE_URL` 这一处拼串，无 okhttp 调用）。

**判定与理由**：**成立**（技术事实全部核实：端口/部署分离、支付必须先过浏览器、OAuth 入口在 8080、两套是不同部署）。审计的「易超时丢单」是推断，我没有证据支持也没有证据反驳 —— 但有一个**确定**的相邻事实：**`:core` 的 serve 形态下 `paymentUrl` 与 `browserLoginUrl` 都是空串**，屏上「去支付」点了没动作（`CampusVenueApi.kt:137-140,363-365`，契约 §5.3.2 已把这条写成已知缺口）。审计提的修法（用 OkHttp 预热 80 端口 OAuth，再把 `JSESSIONID` 用 `CookieManager.setCookie` 注入）**不可直接采信**：80 那套的会话 cookie 名/是否接受 8080 的 `SESSION` 都没有依据，而且凭空注入会话 cookie 到 WebView 是安全敏感动作。

**真优先级：高**。理由（与审计不同）：它不是「UX 小瑕疵」，而是**写路径对齐目前唯一真正卡住的点** —— 三端要「与 Android 现状对齐」，就必须先决定「支付这一跳」的归属：Android 继续走内置浏览器；`:web`/`:server` 现在如实给空串（按钮点了没动作）；`docs/desktop-port-plan.md` §16 的三端一致决策需要在这里给出答案。这个决定不落地，场馆写路径就是「下单成、支付断」的半条链。

**最小修法（两步，先决策后实现）**：
1. 先把「契约里支付一跳的形态」定下来（`browserLoginUrl` 给客户端 + `paymentUrl` 由谁拼），然后把 `CampusVenueApi` 那两个空串换掉 —— 这是纯契约/接线工作，不需要动 Android；
2. Android 侧本轮**不改**：现状（先 8080 OAuth 再跳 80 支付页）就是基准端行为，任何「预热 + 注入 cookie」的优化都应先做成可回退的开关并实测。

**是否改变 Android 现有可见行为**：本轮不动 ⇒ 不变（这正是「向 Android 现状对齐」的含义）。

---

### #31 无障碍薄弱

**审计断言**：大量自绘 `Canvas` 与自定义色块缺 `contentDescription`，多处小图标/紧凑按钮小于 48dp 最小触控目标；点名 `DiscussionsScreen.kt:212`、`CommunityScreen.kt:207`。

**我读到的代码事实**

```kotlin
// core/.../community/DiscussionsScreen.kt:212   Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))   ← 与审计引文逐字一致
// core/.../community/CommunityScreen.kt:221     Icon(Icons.Outlined.Forum, null, tint = colors.primary, modifier = Modifier.size(56.dp))
// core/.../community/DiscussionReplies.kt:106-110  TextButton(…, minHeight = 32.dp)   （「加载更多回复」等）
```
```bash
grep -rn "contentDescription = null" core/src/commonMain app/src/main | wc -l   # 101
grep -rn "Icon([^,]*, null[,)]" core/src/commonMain app/src/main | wc -l         # 50
grep -rn "Canvas(" … | wc -l                                                     # 19
grep -rn "semantics {" … | wc -l                                                 # 4
grep -rn "minimumInteractiveComponentSize" … | wc -l                             # 0
```

**判定与理由**：**成立**。`Icon(x, null, size(15.dp))`、`minHeight = 32.dp`、0 处 `minimumInteractiveComponentSize()` 都是有据可查的；`Canvas(19 处`/`semantics{ 4 处` 的比例也支持「自绘缺无障碍语义」。唯一驳回的是行号（`CommunityScreen.kt:207` → 实为 221，:`207` 是 `authorization = null`）。

**真优先级：低**。理由：影响面是视障用户与小触控精度用户，是真缺陷但不阻塞任何功能；且 101 处 `contentDescription = null` 里相当一部分是**装饰性图标**（按 Compose/Material 规范，装饰图标就该是 null），逐条判断成本高、收益不均。

**最小修法**：只做两处高价值点 —— (1) 给自绘的**信息性** `Canvas`（图书馆座位图、匹配作息网格、课表网格）各加一个 `Modifier.semantics { contentDescription = "…" }` 汇总播报；(2) 把 `minHeight = 32.dp` 的按钮与 `size(15–18.dp)` 的**可点**图标统一套 `Modifier.minimumInteractiveComponentSize()`。装饰图标保持 null，不要一律加描述。

**是否改变 Android 现有可见行为**：会（TalkBack 朗读内容、部分控件热区变大）⇒ 视觉/交互有可感知变化，属「改善现状」，三端共享屏改动会同时影响桌面/Web。

---

### #32 匹配交友默认把真名编进分享码

**审计断言**：昵称未手改时默认填真名；`Dimensions(identity = true)` 把入学年份/专业/书院/班级/生源地省份一并编进码；二维码常被截图发群，等于公开真名 + 班级 + 每节课的教室。

**我读到的代码事实**

```kotlin
// core/.../social/MatchScreen.kt:152-156
LaunchedEffect(local.identity?.name) {
    if (!nicknameEdited) { local.identity?.name?.takeIf { it.isNotBlank() }?.let { nickname = it } } }
// :120-123  var nickname by remember { mutableStateOf("") } ; var nicknameEdited …   // 注释：名字默认填本机档案里的真名
// core/.../social/MatchProfile.kt:119   val identity: Boolean = true,
// :296-303  grade = if (dims.identity) local.identity?.grade ?: 0 else 0 ; … profession/department/academy/campus/className
// :302-303  province = if (dims.identity) local.identity?.sno?.let { ProvinceCode.of(it) }.orEmpty() else ""
// :347-348  p.className, p.province   ← 确实被编进码
```

**判定与理由**：**成立**。真名默认、`identity` 默认开、班级/省份/专业/校区都进码，逐条属实。补充两点我自己的判断：(1) 泄露面**比审计描述的小一点** —— 学号本身不在码里（省份是从学号**派生**出来的），暴露的是「真名 + 行政班 + 专业 + 省份 + 作息/课程交集」；(2) 分享码本来就是要发给陌生搭子的，问题不在「编了什么」而在**默认全开 + 用户无感知**（没有「本码含真实姓名」的提示）。

**真优先级：中**（比审计的「低」高）。理由：这是**隐私默认值**问题：默认即泄露，且用户唯一的出口是手动进分享设置逐项关；改成默认脱敏的成本极低、可逆。

**最小修法**：把默认改成「昵称 = 随机花名（或提示用户自取）+ `identity = false`」，并在分享/保存二维码按钮旁加一行「本码包含：真名/班级/专业…」的清单式提示（复用 `dims` 已有字段即可，不需要新数据结构）。

**是否改变 Android 现有可见行为**：会（默认昵称与默认分享维度变化；已有用户的旧码仍可解）⇒ 明确的可见行为变化，需产品确认；三端共享屏，改动会同时影响桌面/Web。

---

### #33 `miuix = "latest.release"` 动态版本

**审计断言**：`gradle/libs.versions.toml:12` 用 `latest.release`，`:app` 的核心 UI 依赖全绑在它上 ⇒ 不可重现构建 + 供应链风险。

**我读到的代码事实**

```toml
# gradle/libs.versions.toml:11-12
# 取 Maven Central 最新正式版（预发布版由 app/build.gradle.kts 拒掉，版本号缓存 30 分钟）
miuix = "latest.release"
```
```kotlin
// app/build.gradle.kts:175-188
// miuix 走 latest.release：拒掉预发布版本；解析到的最新版本号缓存 30 分钟，
configurations.configureEach { resolutionStrategy {
    cacheDynamicVersionsFor(30, TimeUnit.MINUTES)
    componentSelection.all { if (candidate.group == "top.yukonga.miuix.kmp" &&
        Regex("(?i)(alpha|beta|rc|dev|snapshot)").containsMatchIn(candidate.version)) reject("miuix 只跟正式版") } } }
// :253-260  implementation(libs.miuix.ui / .preference / .icons / .squircle / .nav)
```

**判定与理由**：**成立**。动态版本号逐字属实，且 `:app`（唯一发版模块）的 UI 层 5 个构件全绑在它上。审计的「无法可重现构建」「F-Droid 类渠道要求固定版本」成立；「上游账号被投毒 ⇒ 下次 push 自动拉下来并用正式签名密钥签名发到全校」这条链路也成立（`build.yml` 就是 push 到 main/dev 即出包，见 #34）。**两条缓解是真实存在的**（拒预发布 + 30 分钟解析缓存）——但缓存只让同一构建里的版本稳定，**不解决跨时间可重现性**（30 分钟后另一次构建可以解析到不同版本）。

**真优先级：高**。理由：这是「可重现性 + 发版供应链」两条硬要求，且修法成本是**一次**版本号改动 + 定期升级流程；审计把它排高是对的（我这一节与审计结论一致，但依据是我自己核到的 `:app` 依赖清单与 CI 发版链路，不是照抄）。

**最小修法**：把 `miuix` 钉到一个验证过的具体版本（如 `"0.8.x"` 的实际版本号），删掉 `componentSelection` 里的动态版本缓存理由（保留拒预发布那半条即可）；由 Renovate/Dependabot 提升级 PR。

**是否改变 Android 现有可见行为**：**会**（钉住的版本可能与今天解析到的最新版不同 ⇒ 主题/控件外观差异）。建议先记录当前解析到的版本（`./gradlew :app:dependencies` 或构建日志里那一行）再钉，确保「钉完 = 今天的样子」。

---

### #34 workflow 内联 `${{ }}` + token 拼 remote URL（+「CI 不跑测试」）

**审计断言**：(a) `build.yml` 在 `<< 'PYEOF'` heredoc 里直接内联 `${{ steps.version.outputs.version }}`；(b) `GITEE_TOKEN` 拼进 git remote URL；(c) 全仓没有 PR/push 检查工作流，`build.yml` 只跑 `assembleRelease`，**完全不跑单元测试**。

**我读到的代码事实**

```yaml
# .github/workflows/build.yml:125-127
python3 - <<'PY' > /tmp/release_body.md
import re, sys, os
ver = "${{ steps.version.outputs.version }}"
# :186-187
git remote add gitee "https://yeliqin666:${GITEE_TOKEN}@gitee.com/${OWNER}/${REPO}.git" 2>/dev/null || \
  git remote set-url gitee "https://yeliqin666:${GITEE_TOKEN}@gitee.com/${OWNER}/${REPO}.git"
# 同一个 token 还出现在 query / JSON body / curl -F：
# :199,:202  curl … "?access_token=${GITEE_TOKEN}" ; :213  \"access_token\": \"${GITEE_TOKEN}\" ; :244  -F "access_token=${GITEE_TOKEN}"
# :44-46
      - name: Unit tests
        # 单测不过就不出包
        run: ./gradlew :app:testDebugUnitTest
```
```yaml
# .github/workflows/core-gate.yml:14-16  on: pull_request / workflow_dispatch
#  :60-80  ./gradlew :core:compileKotlinWasmJs :web:compileKotlinWasmJs ; :core:jvmTest ;
#           :testkit:compileKotlin :data:compileAndroidMain :data:jvmTest :desktop:compileKotlin :desktop:test
```

**判定与理由**：**部分成立**。
- (a) 成立（heredoc 内的 `${{ }}` 会在 bash 执行前被 GHA 文本替换，与 `'PYEOF'` 是否裸引号无关；`:127` 逐字属实）。
- (b) 成立，且比审计说的更广：token 不只拼进 remote URL，还拼进 4 处 **URL query / JSON body / curl 表单**（`:199,:202,:213,:244`）—— 这些更容易被日志、代理、`ps` 抓到。
- (c) **不成立**：`build.yml:46` 明确跑 `:app:testDebugUnitTest` 且注释「单测不过就不出包」（`git log -S testDebugUnitTest` → `b7762420 ci: 出包前跑单测`）；`pull_request` 触发的 `.github/workflows/core-gate.yml` 也在跑 `:core:jvmTest`/`:data:jvmTest`/`:desktop:test`。**残留的真缺口**是：`app/src/test` 的 76 个测试文件里，CI 只跑 `:app:testDebugUnitTest`（发版 workflow 里），PR 门禁 `core-gate.yml` **不跑** `:app` 的测试 —— 拿 app 模块改动的 PR 不会在 PR 阶段被拦。

**真优先级：中**。理由：凭据出现在 URL/表单里是实打实的泄露面（Gitee 侧日志、CI 日志脱敏规则、`ps`），但它是**私有仓库密钥 + 只在 main/dev push 时执行**，暴露窗口有限；「PR 门禁不覆盖 `:app`」是真缺口但影响增量。

**最小修法**：token 一次性从「URL/表单」搬进 `git -c http.extraHeader="Authorization: Basic …"` 与 `curl -H "Authorization: Bearer …"`（Gitee API 支持 header 鉴权）；版本号走 `env: TARGET_VERSION: ${{ steps.version.outputs.version }}` + `os.environ["TARGET_VERSION"]`；在 `core-gate.yml` 里补一行 `./gradlew :app:testDebugUnitTest`（不需要签名密钥）。

**是否改变 Android 现有可见行为**：**不变**（纯 CI）。

---

### #35 leaderboard 反向代理下限流取 `req.socket.remoteAddress`

**审计断言**：`server/leaderboard/index.js` 的 `clientIp()` 取 socket 地址，Nginx 反代后所有人共享 `127.0.0.1` 配额；并引 `nginx-Snippet.conf` 与 `LeaderboardApi.kt:30`。

**我读到的代码事实**

```bash
ls server/            → build  build.gradle.kts  src        # 没有 leaderboard/ 目录
find . -name 'leaderboard*' -not -path './.git/*'        → 空
for b in origin/main origin/dev origin/announce refactor/kmp-multiplatform; do
  git ls-tree -r --name-only $b | grep -c leaderboard; done   → 0 0 0 0
grep -ril "leaderboard" --exclude-dir=.git --exclude-dir=build --exclude-dir=miuix-ref .   → 空
grep -rn "排行榜" --include=*.kt  → 只有 GameStore.kt:12「这些数字只是给自己看的，**没有排行榜**」
```

**判定与理由**：**无法判定**（不是「不成立」）。审计点名的 `server/leaderboard/index.js`、`server/leaderboard/deploy/nginx-Snippet.conf`、`LeaderboardApi.kt`、`zyxf.top` 上的 Node 服务在本仓库的**任何分支都不存在**；`grep` 也没有任何客户端「排行榜」代码。两种可能：(1) 审计基线比 `origin/main` 更早，那套服务后来被移出仓库；(2) 审计把另一个仓库/别的项目的内容混进来了。我**不能**依据这段文字判断那个服务端代码是否有这个 bug。

**真优先级：不适用（本仓无此代码）**。理由：若那套 Node 服务还在线上跑（部署在 `zyxf.top`），这条 bug 有真实代价（全校共享一个令牌桶 ⇒ 集体 429）；但它已不归本仓库维护，应该由该服务的仓库去修。审计的技术推理（反代后 `socket.remoteAddress` 恒为 `127.0.0.1`、应仅在回环时信 `x-real-ip`）**本身是对的**，作为该仓库的修法可以参考。

**最小修法（若那个仓库还在维护）**：按审计给的 `clientIp()`（回环才读 `x-real-ip`）+ Nginx `proxy_set_header X-Real-IP $remote_addr;` 两条都做 —— 只做一条会留下「裸跑时 `X-Forwarded-For` 可伪造」或「仍然全员共享桶」的半截。

**是否改变 Android 现有可见行为**：本仓无对应代码 ⇒ 不适用。（若线上真有该服务，修好它能消除「谁都不能提交成绩」这类跨用户故障 —— 但那是服务端行为，不是 Android 可见行为。）

---

### #36 缺协作基础设施

**审计断言**：`.github/` 只有 `workflows/build.yml` 与 `workflows/sync-announce.yml`，**没有** `ISSUE_TEMPLATE`、`PULL_REQUEST_TEMPLATE.md`、`CONTRIBUTING.md`、`SECURITY.md`；新贡献者面对 545 个文件不知从哪下手。

**我读到的代码事实**

```bash
find .github -type f
# .github/ISSUE_TEMPLATE/{announce,bug,config,feature}.yml
# .github/PULL_REQUEST_TEMPLATE/announce.md
# .github/pull_request_template.md
# .github/workflows/{build,core-gate,desktop-linux,desktop-windows,sync-announce}.yml
git ls-tree -r --name-only origin/main | grep '^\.github'      # 同样有 bug.yml / feature.yml / announce.yml / config.yml / pull_request_template.md
ls CONTRIBUTING.md SECURITY.md  → No such file or directory
ls docs/ → MIUIX-reference.md agent-web-spec.md api-contract.md desktop-port-plan.md linux-delivery.md
           preview-channel.md storage-layout.md windows-delivery.md     # 8 份，无 architecture.md
```

**判定与理由**：**不成立**（主体）。Issue 模板与 PR 模板**都存在**（HEAD 与 `origin/main` 都有），而且 `bug.yml` 里已经写了「不要在 Issue 里粘贴密码/验证码/Cookie/完整学号/付款码/HAR/API Key」这类安全引导；`pull_request_template.md` 也带了「不含密钥、账号材料、HAR、带学号的导出文件」的检查项 —— 审计这条「什么都没有」的结论与事实相反。**残留成立的一半**：`CONTRIBUTING.md` 与 `SECURITY.md` 确实缺（两个分支都缺）；`docs/architecture.md` 也确实没有，不过仓库有 8 份文档，其中 `desktop-port-plan.md` §16 就是决策真源、`storage-layout.md` 是统一存档格式真源、`api-contract.md` 是三端契约真源 —— 「新学弟学妹不知道从哪下手」与这份文档现状不符（可以争「入口不显眼」，不能说「没有」）。

**真优先级：建议**。理由：缺 `SECURITY.md`（安全披露渠道）在开源项目里算是「该有的没有」，`CONTRIBUTING.md` 是便利性；两者都不阻塞任何东西。

**最小修法**：只补一份 `SECURITY.md`（一句话：漏洞请走私密渠道/邮箱，别开 public issue），在 `README.md` 里加一行指向 `docs/desktop-port-plan.md` 与 `docs/api-contract.md` 的「从哪读起」；不必另写 `docs/architecture.md`（会与现有 8 份文档重复）。

**是否改变 Android 现有可见行为**：**不变**（纯仓库文档）。

---

## 3. 本组的置信度与未核对项

**置信度（逐条）**

- 高（代码逐字核过、且有独立复现）：#24、#25、#26、#30、#32、#33、#34、#36 —— 这些结论我不认为还有翻盘空间（除了 #25 的「上游有无聚合接口」这一层我无法证伪）。
- 中：#21、#22、#27、#29、#31 —— 事实核清，但「严重度」与「收益」依赖我没做的测量（token/帧率/token 计费）。
- 低（无法判定）：#35 —— 点名的代码在本仓库不存在，我拒绝给出成立/不成立的结论。
- 明确判定为「审计证据不成立」的：#23（并附上可复核的 `git log --all -S` 空结果）、#22 的「29 个工具」、#27 的「3019 行 / 8 个测试 / 1.5% / 537 文件」、#26 的修法（`InboxStore.init` 已不存在）、#34 的「CI 完全不跑单测」。

**未核对项（我明确没做、因此可能有偏差的地方）**

1. **没有跑构建/测试/jacoco**（本机门禁被别的子代理独占 + 内存 ~7GB）⇒ 覆盖率只能给文件数/行数比（§0.3），**不是**行/分支覆盖率；「哪些解析器真被测到」我只做了文件名与夹具存在性核对，没做逐函数映射。
2. **没有 tokenizer**（`python3 -c "import tiktoken"` → ModuleNotFoundError）⇒ #22 的 token 数是**估算**（3.0k–5.0k），不是实测；schema 的字符量测到的是**源码字面量**，不是运行期 JSON 串（我用 27×结构开销估到 ≈7.9k 字符）。
3. **没有真机/没有跑性能测试** ⇒ #28 的「掉帧」、#24 的「耗电」、#25 的「墙钟耗时」、#30 的「易超时丢单」这四项全是**推断**，我只核实了触发它们的代码路径。同理 #31 的触控体验没有设备侧结论。
4. **没有联网探测上游**（校内外网络都没打）⇒ #25 的 `getTodos` 是否存在、#30 的 80 端口那套到底认不认 8080 的 `SESSION`、#29 的 GitHub 在校园网的可达性，都没验证。
5. **没有读审计原文第 1–2 节**（#1–#20，不属本次范围），所以若 #21–#36 与前面某条共用前提（如审计自称的「逐行通读范围」），我没有交叉检验。
6. **`miuix-ref/`** 目录（仓库内自带的一份 miuix 源码副本）我只用 `grep -v` 排除在统计外，**没有**核它是否参与构建、是否与 #33 的动态版本构成第二份来源（`settings.gradle.kts` 里没有 `includeBuild`/`include(":miuix-ref")`，仅此一条间接证据）。
7. 审计自称基线是「全仓 545 个源文件（12.3 万行）」；我按 HEAD 量到生产 861 文件 / 163,798 行（含 `:core`/`:data`/`:desktop`/`:server`/`:web`），按 `origin/main` 量到 532 个 `.kt` —— **两个基线的分母都换过**，所以凡是「占比」类的数字都要看我给的口径。
