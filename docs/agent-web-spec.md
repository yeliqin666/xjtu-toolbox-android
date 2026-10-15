# 屁岱（Agent）在浏览器上的落法（会议 D10 设计稿）

> 状态：2026-10-14 设计稿（会议 D10 决策见 docs/desktop-port-plan.md §16.1）。本文件是这一族的设计真源，契约小节（候选 §5.9）由它将来的实现提交落进 docs/api-contract.md。

依据：`docs/desktop-port-plan.md` §16.1 **D10**（「key 存 serve 侧（DPAPI），浏览器只发消息，serve 转发 SSE；工具层按白名单开放（先只读口：黄页 / 教师检索 / 课表 / 空教室 / 校历…）；配置 / 会话 / 记忆三份落盘也归 serve，走 D8 的统一存档层」）。验收口径 = **丙（逐像素 / 逐状态全等）**，基准是 **Android 现有行为**。存档形状真源 = `docs/storage-layout.md`。

本文件只做**设计**，不含实现。全部结论都来自只读核对（证据清单见 §10），凡是没核到的地方在 §10 末尾逐条声明为「未核对」。

---

## 0. 先给三条结论 + 两个**既有缺口**

### 三条结论

1. **循环跑在 serve，不跑在浏览器。** 依据是 `AgentHost` 的槽位**是同步的**：`loadConfig` / `saveConfig`（`core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentHost.kt:113,116`）、`sessions()`（`:110`，其接口 `AgentSessionPersistence` 的六个方法全部非 suspend）在 `AgentScreen.kt:88` 的**组合期**被直接调用、`:202` 的 `bind(store)` 也是同步。而浏览器上这三样都要变成 HTTP ⇒ 要么把 `:core` 的三条缝改成 `suspend`（动三端契约），要么在浏览器里做「内存镜像 + write-behind」（立刻出现两份真相）。循环在 serve 侧则**一个同步缝都不用动**。
2. **「serve 转发 SSE」在代码上已经有一半是现成的**：`:core` 的调度循环（`AgentRunner.kt`，commonMain）与 `:data` 的 `SseAgentTransport`（`data/src/commonMain/kotlin/com/xjtu/toolbox/agent/SseAgentTransport.kt`，okhttp）在 serve（jvm）上**可以直接用**——`server/build.gradle.kts` 已经依赖 `:core` + `:data`。缺的只有三件：①白名单工具层 ②把 `AgentRunner` 的回调编成 SSE 帧 ③一套**异步**的浏览器宿主面。
3. **浏览器侧不实现 `AgentHost`**（同形不了，理由逐槽位见 §4.1）。`AgentHost` 的语义是「这一端有什么**端上资源**」，而浏览器这一段**没有资源**——资源全在 serve。这正是 D10 的内容。

### 两个既有缺口（与 D10 直接相关，必须一并修）

- ⚠️ **缺口 A：`agent_config_secure` 在 jvm 上没有 DPAPI。** `AgentConfigStore` 的密钥半是 `secureKeyValueStore(STORE_SECURE + suffix, legacyFallbackName = …)`（`data/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentConfigStore.kt:34-38`），**没有**套 `ProtectedKeyValueStore`。全仓只有 `JvmCredentialStore` 套了（`data/src/jvmMain/kotlin/com/xjtu/toolbox/platform/CredentialStore.jvm.kt:50`：`ProtectedKeyValueStore(secureKeyValueStore(storeName), protector)`）。而 `secureKeyValueStore` 的 jvm actual 是**明文 0600 properties**（`data/src/jvmMain/kotlin/com/xjtu/toolbox/platform/SecureStore.jvm.kt:112-114`）⇒ **Windows 上（桌面与 serve）API Key 今天是明文落盘**，与 D10 的「key 存 serve 侧（DPAPI）」不符，也与 `AgentConfigStore` / `DesktopAgentHost` 的 KDoc 自述（「Windows 上再套一层 DPAPI」）不符。`docs/windows-delivery.md` §5.1–§5.4（105-164 行）那一节只覆盖**凭据**（`JvmCredentialStore`），不覆盖 API Key。
- ⚠️ **缺口 B：账号后缀在匿名态必须是 `default`，不能是空串。** serve 未登录时必须把 `AccountContext.activeAccountId` 置成 `null`（`server/src/main/kotlin/com/xjtu/toolbox/server/ServeSession.kt:271,300`），于是 `safeSuffix()` = `"default"`（**无前导下划线**）；`""` 会得到 `"_"` 这个恶心的形状（`docs/storage-layout.md` §4 逐字钉过）。规格里钉死这一条，因为落盘文件名直接受它影响。

---

## 1. 现状核对与架构决策：谁跑循环

### 1.1 现状（只读核对的结论）

| 事实 | 证据 |
|---|---|
| `:web` 的屁岱那一格现在是**降级页** | `web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/ToolboxWebApp.kt:351-362`（`AppRoute.Agent -> WebUnavailableScreen(...)`，逐条列出缺的 `AgentHost` 三件）；§15.1 的五格表、§15.7 第 3 条 |
| 桌面已点亮 | `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/DesktopAgentHost.kt` + `DesktopAgentTools.kt` + `desktop/src/test/kotlin/com/xjtu/toolbox/desktop/DesktopAgentJvmTest.kt`（4 例夹具端到端） |
| serve 里**一个字都没有** agent | `grep -i agent server/src/main/kotlin/**` 零命中 |
| serve 的闸门是**路由作用域插件**，挂在 `route(API_PREFIX)` 上 ⇒ 新端点自动被罩 | `ServeModule.kt:69`（`install(accessTokenGate(accessToken))`）、`AccessTokenGate.kt:46,56`（`EXEMPT_PATHS` 只有 `/api/status`） |
| serve 的对外默认是回环 + 一枚令牌；现网实跑在 `0.0.0.0:40961`（明文） | `ServeConfig.kt:33,34,30`（`DEFAULT_HOST`/`DEFAULT_PORT`/`exposed`）；`docs/desktop-port-plan.md` §15.6.1 |
| 循环与传输都在共享层，且 serve 能用 | `core/.../agent/AgentRunner.kt`（commonMain）、`data/.../agent/SseAgentTransport.kt`（`:data` 只有 jvm+android 目标，`data/build.gradle.kts:20`）、`server/build.gradle.kts` 依赖 `:core` + `:data` |
| 工具表裁剪的**现成先例**就在桌面 | `desktop/.../DesktopAgentTools.kt:75-81`（`desktopAgentToolDefinitions(honored)` 按名字筛 `:core` 目录） |
| `:web` 的诚实缺口写法有现成两件 | `WebGapNote(text)`（`WebFallbackScreens.kt:222`）、`WebUnavailableScreen(title, why, missing, actions)`（`:58`） |

### 1.2 选 D：serve 跑整个 Agent 循环（推荐）

| 层 | 位置 | 依据 |
|---|---|---|
| 系统提示 / 画像 / 记忆块 | serve | 画像要**学号**（`app/src/main/java/com/xjtu/toolbox/agent/AgentTool.kt:148-238`：学号解析年级/生源地、一网通办姓名学院）——浏览器拿不到（§5.2 红线） |
| 工具循环（保险丝 20 / 800ms 站点限流 / 12000 字截断 / 原子提交 / `length` 续写） | serve，**同一份 `AgentRunner`** | `AgentRunner.kt` 在 `:core` commonMain，一行不改 |
| 模型那一枪 | serve，**同一份 `SseAgentTransport`** | okhttp 只有 jvm/android 变体（`AgentTransport.kt` 的 KDoc 明写这格缝的来由） |
| 会话 / 记忆 / 配置 | serve（`:data` 三个类现成） | D10 |
| SSE 解析 → 气泡 / 工具行 / 卡片 / 跳转建议 | 浏览器 | 屏的状态机 |

### 1.3 被否方案：浏览器跑循环、serve 只做「透传模型 SSE + 代跑工具」

- 优点是 `AgentViewModel` + `AgentRunner` 逐字复用；但**代价是同步缝**：`AgentHost.loadConfig/saveConfig`（`AgentHost.kt:113,116`）、`AgentSessionPersistence` 六个方法（`AgentSessionPersistence.kt`，全同步）、`AgentHost.welcomeFacts`（`:128`）、`imageFileExists`（`:137`）全都要在浏览器上变成「先异步预热的内存镜像」。`AgentScreen.kt:88` 的组合期读 + `:202` 的 `bind()` 决定了没有别的写法。
- 次要代价：**history 有两份真相**（浏览器内存里的 `llmHistory` 与 serve 落盘的 `StoredConversation.llmHistory`），另一台设备/另一个标签页改了会话本页看不见。
- 保留意见：**白名单在浏览器侧不是白名单**（工具名由浏览器发过来）。虽然 serve 的 `/api/agent/tool` 仍能按名字拒，但「谁能调什么」这一条就不再是服务端属性了。

> 补充：`AgentScreen` / `AgentChat` 是共享屏，丙口径要的就是「屏还是那一屏」。方案 D 下**屏一个字不改**（同一份 `AgentScreen`），改的只是「谁来驱动它」——见 §3/§4。

---

## 2. 契约候选小节 §5.9：serve 侧端点清单

口径照 `docs/api-contract.md` §4 通用形状 + §5 既有条目：信封 `{code,data,message}`；`code == HTTP 状态码`；时间 ISO-8601 **带时区**；`null` = 本端明确说没有、字段缺失 = 不投影；能力开关必须出现在响应里；分页 `?page=&size=`（本族暂不用）。**本族一条新形状都不发明。**

前缀：`/api/agent/…`。全部挂在 `route(API_PREFIX){ install(accessTokenGate(...)) }` 里面（`ServeModule.kt:69`），**自动被闸门罩住**。

### 2.1 十四个端点

| # | 端点 | 请求 | 响应 `data`（逐字段） | 错误码 |
|---|---|---|---|---|
| 1 | `GET /api/agent/config` | —— | `{provider:string, model:string, baseUrl:string, assistantName:string, searchEngine:string, thinkingEnabled:bool, reasoningEffort:string, showReasoning:bool, keyConfigured:bool, toolAllowlist:[string], honored:[string], canEditKey:bool}` | `401` 无令牌 · `502`（读配置失败——本端 IO，几乎不会） |
| 2 | `POST /api/agent/config` | `{provider,model,baseUrl,assistantName,searchEngine,thinkingEnabled,reasoningEffort,showReasoning, apiKey?}` | `{ok:bool, message:string}`（照 `/api/library/action` 那类） | `400` 形状/取值不认识 · `401` |
| 3 | `POST /api/agent/models` | `{provider, baseUrl?}` | `{models:[string]}`（已去重升序，照 `AgentModelFetcher`） | `400` provider/baseUrl 形状 · `401` · `502` 服务商/网络（`message` = 服务商原文） |
| 4 | `GET /api/agent/sessions` | —— | `{sessions:[{id,title,createdAt:long,updatedAt:long,locked:bool}]}`，**序 = `AgentSessionStore.list()` = updatedAt 降序** | `401` |
| 5 | `POST /api/agent/sessions` | `{title?}`（缺省 `"新对话"`，`DEFAULT_TITLE`） | `{session:{id,title,createdAt,updatedAt,locked}}` | `400` · `401` |
| 6 | `GET /api/agent/sessions/{id}` | —— | `{conversation:{messages:[StoredMessage…], lastTotalTokens:long\|null, contextExhausted:bool}\|null}`；`null` = 这条会话不在了（§4：明确说没有） | `401` |
| 7 | `POST /api/agent/sessions/{id}/rename` | `{title:string, lock?:bool=true}` | `{ok:true}`（对不存在的 id 是**空操作**——`AgentSessionStore.rename` 就是 `map` 一遍索引，不补登记） | `400` · `401` |
| 8 | `POST /api/agent/sessions/{id}/delete` | —— | `{ok:true}`（幂等，照 `delete()`） | `401` |
| 9 | `GET /api/agent/memory` | —— | `{items:[{key:string,value:string}]}`，序 = `AgentMemory.all()`（最近写入的在后）；**`__order` 不投影** | `401` |
| 10 | `POST /api/agent/memory/forget` | `{key:string}` | `{ok:bool, message:string}`（`AgentMemory.forget` 的原话：没有记过「X」） | `400` · `401` |
| 11 | `POST /api/agent/memory/clear` | —— | `{ok:true}` | `401` |
| 12 | `POST /api/agent/chat` | `{sessionId:string, text:string}` | **`text/event-stream`**（§3） | `400` 形状（`text` 空白且非首轮） · `401` 无令牌 · `404` 会话不在 · `409` 已有一轮在跑 · `502` **只在流开始前**（取数据前置失败）；流开始后的失败走 `error` 事件 |
| 13 | `POST /api/agent/stop` | `{sessionId:string?}` | `{ok:true}`（没有在跑也 200） | `401` |
| 14 | `GET /api/agent/status`（可选，P2） | —— | `{running:bool, sessionId:string\|null}` | `401` |

### 2.2 三条要点（写进契约时必须一起写）

- **红线（照 §5.2 的写法：夹具里明明有那个值，断言响应里没有它）**：`/api/agent/*` 的**任何响应**（含 SSE 每一帧）都不含 API Key——**不是掩码，是字段不存在**。`keyConfigured` 是唯一投影。同一族的红线还包括：不出网学号/姓名/手机号（§5.1 ③、§5.2）；`llmHistory` 不出网（它含工具结果与画像原文，见 §7）。
- **参数形状**照 §5.8 的读法：缺/空白 ⇒ `400` + 中文短句（`ServeRouteSupport.requireIntParam` 那一路）。
- **404 的用法**：本族把 404 从「端点不存在」扩展到「会话不存在」（`chat` 用 `404` + 指名 message）；`GET /api/agent/sessions/{id}` 用 `200 + conversation:null`（照 `/api/library/my` 的 `my:null` 与 `/api/jwapp/score-detail` 的 `detail:null`）。⚠️ 这一处要在契约里显式声明，否则与 §7 的 404 兜底撞车（未决 #6）。
- **不端的**：没有任何「细粒度历史写入口」（`POST /api/agent/messages` 之类）。一轮对话的落盘完全由 serve 在流结束时自己做（与 `AgentViewModel.persist(turnSid)` 同一时机），少一个篡改面。
- **`toolAllowlist` / `honored` 必须如实**（契约 §2 的「能力开关随部署变」）：前者 = serve 侧白名单（§6），后者 = 端能力（浏览器/服务端都是**空集**）。

---

## 3. 流式协议（SSE）

### 3.1 传输形态

- `POST /api/agent/chat`，响应 `Content-Type: text/event-stream`，**一帧一行**（`event:` + `data:` + 空行）。
- 服务端实现建议：`call.respondBytesWriter(contentType = ContentType.Text.EventStream)` 手写帧 —— **不引新依赖**（`ktor-server-sse` 不在 `gradle/libs.versions.toml:123-125` 的那三项里；要引必须与 `ktor = "3.6.0"` 同步升）。⚠️ **未核对**：Ktor 3.6 的服务端 SSE 自动支持范围（我按「手写帧最稳」给的建议）。
- 保活：每 15 秒一个**注释帧** `: ping`（SSE 规范里 `:` 开头的行是注释）。消费方必须忽略注释帧。
- 响应头建议带 `cache-control: no-store` 与 `x-accel-buffering: no`（反代不缓冲）。
- 浏览器读取：**不要用 Ktor 的 Js 引擎**（`core/src/wasmJsMain/kotlin/com/xjtu/toolbox/core/net/ToolboxEngine.wasmJs.kt:15` 就是 `Js.create()`；fetch 引擎的流式行为是引擎实现细节，本仓没有任何流式先例可依）。建议照本仓既有的浏览器互操作习惯（`web/.../Data.kt:36,45,56`、`web/.../BrowserUrl.kt:17` 的 `@JsFun`）写一个 `@JsFun` 的 fetch 桥：`fetch(url, {method:'POST', headers:{Authorization, Content-Type}, body, credentials:'same-origin'})` + `resp.body.getReader()` + `TextDecoder`。
  - ⚠️ **未核对**：`@JsFun` 传 Kotlin lambda（回调）到 JS 在 wasmJs 上的行为，本仓没有先例（现有三处都是无参/纯字符串）。落地时先写一条最小探针。
  - 令牌照既有纪律：`ServeTokenHolder`（`web/.../ServeSession.kt` 末段）**只在内存**，带 `Authorization: Bearer` + cookie 两条路（契约 §3.2）。

### 3.2 十一类事件（与 `:data` Agent 流的**逐字段对应关系**）

| # | 事件名 | `data` 载荷 | 何时发 | 来源（Android 侧逐字对应） | 浏览器落到哪 |
|---|---|---|---|---|---|
| 1 | `delta` | `{text:string}` | 模型正文增量到达时，逐段 | `AgentTransport.stream(onDelta)` → `AgentViewModel.kt:432` 的 `pendingContent.append(frag)` + `scheduleFlush()`（50ms 合流，`:65,376`） | 流式助手气泡（照 `STREAM_FLUSH_MS` 合流——**照抄**，防 O(n²) 重组） |
| 2 | `reasoning` | `{text:string}` | 思维链增量到达时，逐段（仅思考开启时） | `onReasoningDelta` → `pendingReasoning` | 思考折叠栏（受 `config.showReasoning` 控制） |
| 3 | `tool_call` | `{name:string}` | 模型下发一次工具调用的**聚合之后**、开始执行之前 | `AgentRunner.run(onToolCall)` → `AgentViewModel.kt:519` 的 label 气泡（`isToolCall=true`） | 工具行（「查询课表…」这类文案从 `:core` 的 `agentToolLabel(name)` 取，见 §4.4） |
| 4 | `tool_result` | `{name:string, success:bool, error:string\|null}` | 该次工具执行结束（成功或失败）后立刻 | `onToolResult` → `settleToolAt()`（`AgentViewModel.kt:74` 附近） | 工具行收尾（成功加「已」前缀、失败标红显示原因） |
| 5 | `usage` | `{totalTokens:long}` | 模型回报 usage 时（DeepSeek 走 `stream_options.include_usage`） | `onUsage` → `lastTotalTokens`（`AgentViewModel.kt:565`） | **只用来显示/兜底**；`contextExhausted` 由 serve 判（阈值 `800_000`，`:63`） |
| 6 | `mfa` | `{pending:bool, siteName:string\|null}` | 校园会话挂起/结束短信二验时（工具执行期间） | `AgentHost.mfaRequests`（`AgentHost.kt:88`；desktop 是 `sessionManager.activeMfaRequest`，`DesktopAgentHost.kt:128`） | 对话列表里那条「需要登录验证：等待「X」短信验证码…」气泡（`AgentViewModel.kt:544` 附近）。**不写手机号**（§5.1 TODO ① 不投影） |
| 7 | `widget` | `{widgets:[{type:string, json:string}]}` | 一轮的工具执行全部结束后、`done` 之前（一次给全） | `AgentTools.drainWidgets()`（`AgentRunner` 跑完后 ViewModel 取） | `ChatMessage.widgets`（`AgentViewModel.kt:36`）；解析用 `:core` 现成的 `storedToWidget` |
| 8 | `nav` | `{routes:[{label:string, route:string}]}` | 同 `widget`（`done` 之前） | `AgentViewModel.kt:590` 的 `calledTools → navSuggestions` | 回复下方那排跳转按钮（`route` 是 `AppRoute.id`） |
| 9 | `done` | `{finishReason:string, content:string, sessionId:string, title:string, contextExhausted:bool, totalTokens:long\|null}` | 一轮正常结束时（**唯一正常终止事件**） | `AgentRunner.run` 的返回值 + `persist()`（`:266`）+ `maybeAutoTitle()`（`:301`） | 收尾那一句（`reply`）、侧栏标题刷新、上下文耗尽弹窗（**只在 false→true 时弹一次**，照 `:570-580`） |
| 10 | `error` | `{kind:"session_expired"\|"llm"\|"tool"\|"internal", message:string}` | 一轮异常终止时（**唯一异常终止事件**） | ViewModel 两个 catch（`:634-660`）：`SessionExpiredFailure` → 「登录已失效，请返回并重新进入对应功能页面完成认证后再试。」；其余 → `出错了：$detail`；LLM 非 2xx 的中文短句来自 `agentHttpErrorText`（`AgentTransport.kt`） | 同两条文案 |
| 11 | `: ping`（注释帧） | ——（无 `data`） | 每 15 秒一次，直到流关闭 | 无（serve 新增的保活） | 解析时**忽略**（SSE 规范的注释行） |

**能照搬 / 不能照搬（代码级）**

- **能照搬**：`AgentRunner` 整体（`:core`）；`SseAgentTransport`（`:data`，serve = jvm 直接 `new`）；`agentHttpErrorText`（同文件，serve 复用 ⇒ 两端失败文案同源）；`AgentPrompt` / `AgentMemory` / `AgentConfigStore` / `AgentSessionStore` / `AgentTitleGen` / `AgentModelFetcher`（全在 `:core`/`:data`）；工具表裁剪手法照 `desktop/.../DesktopAgentTools.kt:75-81` 的 `desktopAgentToolDefinitions(honored)` 先例（筛子 + `execute` 分派）逐字同形。
- **不能照搬**：① 浏览器**没有** `AgentTransport` 实现（wasm 上 okhttp 不存在；且方案 D 下浏览器连请求体都构造不出来——它没有 key、也没有 `messages`）；② `AgentToolRegistry`（`app/.../AgentTool.kt` 2408 行：Android API + okhttp + Room + `DataCache`）；③ `AgentViewModel` 的驱动（它 `host.tools()` / `host.transport()`）；④ `:data` 的 `SseAgentTransport` 在 wasm 上根本编不出来（`data` 只有 jvm+android 目标，`data/build.gradle.kts:20`）。

### 3.3 结束 / 错误 / 断线重连语义

- **正常结束**：`done` 是最后一个事件，之后服务端关闭流。**不发 `[DONE]`**（那是模型侧的东西，不经这里）。
- **异常结束**：`error` 之后关闭流。**HTTP 状态码在流开始后不能再改** ⇒ 开门阶段失败（`400/401/404/409`）走**信封**（非 SSE），流开始后的失败走 `error` 事件。客户端两种都要处理——这一条写进契约。
- **断线重连：不做，明确写「不支持 `Last-Event-ID`」**。理由：① 一轮生成要么重放（重复上屏）要么续接（历史里缺一段）；② Android 侧同一条流断了就是断了：`SseAgentTransport` 的读失败 → 抛 → ViewModel 落 `出错了：…`，且 `discardUnfinishedTurn()`（`:394` 附近）把这一轮的 user 消息与残体摘掉——为**逐状态全等**，浏览器照抄。用户重发 = 自己再发一次。

### 3.4 并发与取消

- **并发：一个 serve 进程同时只允许一轮**（一个 `Mutex`，与 `ServeSession.loginMutex` 同一条理由：两个并发 run 会互相写同一份 `llmHistory`）。第二发 `POST /api/agent/chat` ⇒ **409** + 「上一轮还在生成，先点停止或等它结束」。
  - 与 Android 的差别要如实写：Android 是 `if (isLoading) return`（**静默忽略**）。这里给 409 是**有意更清楚的一处**（页面上能显示一句话）。
- **客户端断开**（关标签 / 关页面 / `fetch` abort / 网络断）：Ktor 的 call 协程被取消 ⇒ 取消 `AgentRunner` 的那一枪（`SseAgentTransport` 自己 `call.cancel()`，`SseAgentTransport.kt:97-105`）⇒ 服务端走「取消」路径：**历史不留残体**（照 `discardUnfinishedTurn`）、UI 侧不需要知道（流已经断了）。等价于 Android 点「停止生成」（`AgentViewModel.kt:122`）。
  - 兜底判据：**「写帧失败」也算断开**（心跳写 `: ping` 会抛 ⇒ 取消）。写进契约：取消判据 = 客户端断开 **或** 写帧失败。
- **显式停止**：`POST /api/agent/stop`（幂等）。页面上的「停止」按钮走它（比 abort `fetch` 更明确：服务端能立刻回一句 `{ok:true}`）。

### 3.5 分工表（逐条：哪些步骤在服务端、哪些在浏览器）

| 步骤 | Android 现在在哪 | 浏览器上放哪 |
|---|---|---|
| 系统提示（一段对话只生成一次） | `:core` ViewModel（`:398-424`：`hasSystem` 判据 + `userContextProbe` 冷却） | **serve**（画像要学号） |
| 每条 user 消息头 `[现在：…]` | ViewModel `nowTag` | **serve**（时间按学校时区 Asia/Shanghai，与服务端部署时区无关） |
| 工具循环 / 保险丝 / 限流 / 截断 / 原子提交 / `length` 续写 | `:core` `AgentRunner` | serve（同一份代码） |
| 模型那一枪 | `:data` `SseAgentTransport` | serve（同一份） |
| 会话落盘 / 自动标题 | `:data`（`:266` / `:301`） | serve |
| 记忆 / 配置 / key | `:data` 三个类 | serve |
| SSE 解析 → 气泡/工具行/卡片/跳转 | ViewModel 回调 | **浏览器** |
| 50ms 合流 | ViewModel `:65,376` | 浏览器（照抄） |
| 800k 阈值判定 | ViewModel `:63,569` | serve 判、`done` 带出来；浏览器只管「false→true 弹一次」 |
| 停止 | `:122` | `POST /api/agent/stop` |
| 会话列表 / 改名 / 删除 | `AgentSessionPersistence`（同步） | HTTP |
| 思考动画 | `AgentThinkingHost`（`:56-59`） | 浏览器（「流开着」= 在思考） |

---

## 4. 浏览器侧的宿主面（逐槽位对照 `AgentHost`，与 `DesktopAgentHost` 比）

**先给结论**：浏览器侧**不实现** `AgentHost`（4.1），而实现一个新的**异步**面 `WebAgentSession`（4.2）+ 一个只读的配置视图（4.3）。`AgentScreen` 需要一处极小的注入口（4.4）。

### 4.1 为什么不能同形（逐槽位）

| `AgentHost` 槽位（`core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentHost.kt`，行号） | web 落法 | 同形？ | 依据 |
|---|---|---|---|
| `io`（`:44`） | 无此概念 | ✗ | 浏览器单线程，没有阻塞 IO；`Dispatchers.Default` 在 wasm 上就是事件循环 |
| `honored`（`:48`） | **空集** | ✓（同形，值同桌面） | 闹钟 / 应用设置 / 自建日程 / 相册 / 存下载五档浏览器一个都没有 |
| `tools(searchEngine)`（`:58`） | 不实现 | ✗ | 工具层在 serve（白名单），浏览器不建工具表；`toolDefinitions` 必须同步串，浏览器只能拿名字列表来显示「能查什么」 |
| `transport()`（`:61`） | 不实现 | ✗ | 这一格缝的语义是「给我请求体、我把这一轮增量给你」；浏览器没有 key、也没有 messages |
| `accountId`（`:69`） | **不取** | ✗（有意） | §5.1 ③ / §5.2 红线：学号不投影 |
| `greetingName`（`:75`） | **空串** | ✓（同形，同桌面） | 姓名是红线；桌面 `DesktopAgentHost.kt:122` 就是空串「只问候不写名字」 |
| `loginKey`（`:81`） | 可给（`authenticated` 指纹） | ~ | 它只服务「要不要联网补画像」这一处，浏览器不跑那一处 |
| `mfaRequests`（`:88`） | SSE `mfa` 事件（或轮询 `/api/session/mfa`，web 已有 `WebServeSession.mfa()`） | ✓（同形，驱动不同） | 桌面 `DesktopAgentHost.kt:128` 就是 `sessionManager.activeMfaRequest.map{…}` |
| `setAlarm` / `readAppSettings` / `writeAppSetting`（`:96,100,104`） | 不接（工具表不画） | ✓（同形，同桌面） | `honored` 空集 |
| `sessions()`（`:110`） | HTTP（`/api/agent/sessions*`） | ✗ | **`AgentSessionPersistence` 六个方法全是同步的**（`AgentSessionPersistence.kt`） |
| `loadConfig` / `saveConfig`（`:113,116`） | HTTP | ✗ | 同步；`AgentScreen.kt:88` 在组合期读 |
| `fetchModels` / `generateTitle`（`:119,122`） | 在 serve（`/api/agent/models`；标题 serve 自己做） | ✗（位置变） | 两者都在 `:data`，serve 直接可用 |
| `welcomeFacts`（`:128`） | **不接**（v1） | ✗ | 它的原料 `DataCache` + `CampusCardCache.cardPrefs` + `ChatterFactsLoader` 都在 `:app`（`AndroidAgentHost.kt` 的 `AndroidWelcomeFactsLoader`）⇒ 要复刻得先搬进共享层。不接 ⇒ 首屏卡片**直接不出**（`:core` 既有纪律「读不到就不出，不放空壳」，与桌面同形） |
| `images`（`:134`） | **不接** | ✗ | wasm 上 `decodeImage` 恒 null（§15.5 明写「图片解码在 wasm 上恒 null」）⇒ markdown 内联图退成一行可点的链接（`:core` 既有降级路） |
| 附件四件（`:137-146`） | 不接 | ✗ | 无相册 ⇒ `IMAGE_INPUT` 不列 ⇒ 回形针不画 |
| `readPlanImage`（`:152`） | 不接 | ✗ | 图书馆不在白名单 |
| `saveZyxfFile`（`:158`） | 不接 | ✗ | 学辅不在白名单（`FILE_SAVE` 不列） |
| `AgentImagePicker`（`:224`）/ `appearanceSection` / `hero` / `pokeWhisper`（`AgentScreen.kt:56-75`） | 全部默认：`hero` = `agentWelcomePlainHero`（纯文字问候）、`appearanceSection = null`、`pokeWhisper = {}` | ✓（默认值就是「这一端没有」，不用改） | 形象面板在 `:app`（`PidaiAppearancePanel.kt` 341 行 + `PidaiAppearanceStore` 的 `pidai_appearance` prefs） |

### 4.2 `WebAgentSession`（浏览器侧的驱动）—— 逐成员对应

它**不是** `AgentViewModel` 的替代实现（不重写循环），而是「事件 → 状态」的消费者。状态字段与 `AgentViewModel` 逐字同名同型（`ChatMessage` 是 `:core` 的，直接用）：

| `AgentViewModel` 公开成员（行号） | `WebAgentSession` |
|---|---|
| `messages: SnapshotStateList<ChatMessage>`（`:66`） | 同名同型（`delta`/`reasoning`/`tool_call`/`tool_result`/`widget`/`nav` 事件往里写） |
| `isLoading`（`:67`） | 同名（「流开着」）+ 同步 `AgentThinkingHost.isThinking`（照 `applyLoading`，`:71-74`） |
| `errorMessage`（`:77`）/ `contextExhausted`（`:83`）/ `contextExhaustedJustTriggered` + `consumeContextExhaustedTrigger()`（`:86-92`） | 同名同义（由 `error` / `done` 事件驱动；`done.contextExhausted` 走同一套「只在 false→true 弹一次」） |
| `sessions` / `currentSessionId`（`:96,98`） | 同名（HTTP 拉、`done.title` 后刷新） |
| `bind(store)`（`:202`）/ `newSession()`（`:210`）/ `switchSession(id)`（`:220`）/ `deleteSession(id)`（`:250`） | 同名，内部换成 HTTP；`switchSession` 的「切走就取消、防串台」（`:224-226` + `turnSid` 判据）照抄 |
| `sendMessage(text, config, llmAnnex?, images?)`（`:331`） | 只留 `sendMessage(text)`（`llmAnnex` 是提醒快照、`images` 无相册）；开流 + 解析 |
| `stop()`（`:122`） | `POST /api/agent/stop` |
| `replaceLastUserAndSend(text, config)`（`:124`） | 同名（`dropLastUserTurn` 的 UI 部分照抄；LLM 历史那半在 serve：v1 直接砍掉「改上一条」这个入口，页面上如实不画。见未决 #7） |
| `CONTEXT_EXHAUSTED_MESSAGE`（`:64`） | **同一常量**（从 `:core` 取，文案由 serve 用同一份） |

### 4.3 配置视图

`AgentConfig` 需要一处**非密文**的信道来回答「配好了没」——今天靠 `apiKey.isNotBlank()`（`AgentConfig.kt` 的 `isConfigured`），而浏览器永远拿不到 key ⇒ 建议：

```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentConfig.kt
data class AgentConfig(..., val keyConfigured: Boolean = false) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() || keyConfigured
}
```

- 默认 `false` ⇒ Android / 桌面**行为一字不变**；**不要**进 `AgentConfigStore.save()`（否则落盘多一个键，破 `docs/storage-layout.md` §7 的 A1/A2 键集合判据）。
- 面板三处要跟着改（都是「默认 = 现状」的做法，`core/.../agent/AgentConfigPanel.kt`）：
  1. `:194` `enabled = apiKey.isNotBlank()` → `enabled = config.isConfigured`（Android / 桌面同值）；
  2. `:154-180` 的 API Key 那一行抽成一格可注入（照本文件已有的 `appearanceSection: (@Composable () -> Unit)?` 先例，`:44`）——浏览器传「只读徽标 + 一句指路」；
  3. `:349` 的 `val memory = remember { AgentMemory() }` 是**第二个独立实例**（读本端 `keyValueStore`）。浏览器上模型用的记忆在 serve ⇒ 这块必须换成宿主给的记忆视图（HTTP `/api/agent/memory*`）。**这条是必改项**，否则浏览器面板显示的记忆与模型记忆不是一份。
- `AgentChat.kt:241,247` 的提示条文案（「先填一下模型」/「API Key 配好就能聊」）在浏览器上指向不成立：那一行的第二句要能注入（浏览器：「Key 存在运行 serve 的那台机器上」）。⚠️ 逐字全等 vs 如实 —— 建议如实（未决 #8）。

### 4.4 `:core` 需要的三处极小改动（默认参数 = 现状，三端调用点一行不改）

1. **抽 `AgentChatSession` 接口**：把 4.2 那 16 个成员的签名原样抽成 interface，`AgentViewModel : AgentChatSession`（成员名/类型一个字不改），`AgentChat(vm: AgentChatSession, …)`（`AgentChat.kt:59` 换类型）、`AgentScreen(..., session: AgentChatSession? = null)`（`AgentScreen.kt:88`：给了就用，没给还是 `viewModel(key="agent"){ AgentViewModel(host) }`）。
2. **抽两处文案/映射到 `:core` 顶层函数**（两端必须同源，丙口径）：
   - `agentToolLabel(name): String`（现在在 `AgentViewModel.kt:520-548` 的 27 行 `when`）+ `completeToolLabel`（`:81`）；
   - `agentNavSuggestions(calledTools): List<Pair<String,String>>`（现在在 `:590-604`）。
   serve 与浏览器都调它 ⇒ 工具行文案与跳转按钮两端逐字相同。
3. **`AgentConfig` 的 `keyConfigured` + 面板三处槽位**（见 4.3）。

**验证口径**：`:app:testDebugUnitTest` 429 例一条不减、`:core:jvmTest` 439、`:desktop:test` 100（§14.5 基线）——这三处改动都是「加默认参数 + 抽类型」，不碰任何断言。

### 4.5 serve 侧的 `ServeAgentHost`（与 `DesktopAgentHost` 同形）

新类，放 `:server`（或 `:data`，若桌面也要共用）——**逐槽位**对照 `DesktopAgentHost.kt`：

| 槽位 | `DesktopAgentHost`（行号） | `ServeAgentHost` |
|---|---|---|
| `honored` | 空集（`:94`） | 空集（同值） |
| `io` | `Dispatchers.IO` | `Dispatchers.IO`（同） |
| `tools` | `DesktopAgentTools`（`:101`） | **新的白名单工具层**（§6；先放 `:data` 或 `:server`，与桌面将来合并） |
| `transport` | `SseAgentTransport()`（`:112`） | `SseAgentTransport()`（**同一份**） |
| `accountId` | `AccountContext.activeAccountId.orEmpty()`（`:116`） | 同（serve 登录时 `ServeSession.kt:240` 已设） |
| `greetingName` | 空串（`:122`） | 空串（同值；姓名不投影） |
| `loginKey` | `"${auth.loggedIn}|${activeAccountId}"`（`:125`） | `"${session.authenticated}|${activeAccountId}"`（同义） |
| `mfaRequests` | `sessionManager.activeMfaRequest`（`:128`） | 同（`ServeSession.sessionManager`，同一个 Flow） |
| `sessions` / `loadConfig` / `saveConfig` | `:data` 两个 store（`:134,140,145-148`） | `:data` 两个 store + **密钥半套 DPAPI**（§5） |
| `fetchModels` / `generateTitle` | `:data`（`:150+`） | 同（同一份） |
| `welcomeFacts` / `images` / 附件 / 平面图 / 存下载 | 不接 | **不接**（同形） |

---

## 5. API Key 与 DPAPI 的落点

### 5.1 store / 键名（逐字沿用）

理由 = `docs/storage-layout.md` §2.2 的映射规则 + §3.2 第 9 行 + §7 的 A1/A2 判据。

| 项 | 值 |
|---|---|
| store 名 | `agent_config_secure` + `AccountContext.safeSuffix()`（`AgentConfigStore.STORE_SECURE`，`AgentConfigStore.kt:84`） |
| 键名 | `api_key`（`AgentConfigStore.kt:42,75`） |
| 落点 | `<dataRoot>/agent_config_secure<suffix>.properties`（serve 的 dataRoot = `~/.local/share/xjtu-toolbox/`，`data/src/jvmMain/.../platform/SecureStore.jvm.kt:27-33`；`dataFilesDir()` 同根，`DataFiles.jvm.kt`） |
| suffix | 登录后如 `_2253415556`（`ServeSession.kt:240` 设学号 → `AccountContext.suffixFor`）；匿名 = `default`（`:271,300` 置 null，见缺口 B） |
| 保护 | **`ProtectedKeyValueStore(secureKeyValueStore(name, legacyFallbackName), secretProtectorForPlatform())`** —— 这条缝就在 `data/src/jvmMain/kotlin/com/xjtu/toolbox/platform/{ProtectedKeyValueStore.kt, SecretProtector.kt}`：Windows → `DpapiSecretProtector`（`SecretProtector.kt:96+`），Linux / macOS → `null`（明文 0600，逐字保持现状） |
| 迁移 | 老明文文件仍读得回（`ProtectedKeyValueStore.open` 对无 `xjtu-sealed:1:` 前缀的值原样通过）⇒ 读旧写新，安全 |
| 非敏感半 | `agent_config<suffix>`（`provider` / `model` / `base_url` / `assistant_name` / `search_engine` / `thinking_enabled` / `reasoning_effort` / `show_reasoning`）——serve 上必须传**真落盘**的 KV（`secureKeyValueStore`），不能默认的 `keyValueStore`（jvm 上是进程内内存；`DesktopAgentHost.kt` 的 `:137-141` 注释解释了同一条） |

### 5.2 怎么接（两选，推荐第 1 个）

1. 给 `AgentConfigStore` 加一个可选构造参数 `secureStoreOf: (String) -> KeyValueStore = { secureKeyValueStore(it, legacyFallbackName = …) }`（默认 = 现状 ⇒ Android / 桌面一字不变），serve 传 protected 那份。顺手也把**桌面**那一处修掉（同一个缺口 A）。
2. 或者 serve 侧不直接用 `AgentConfigStore`，自己组合一份。缺点：`load` / `save` 的字段与回落判据（`AgentConfigStore.kt:40-79`）要抄第二份 —— 违反「同一个口径不写两处」。

### 5.3 「浏览器侧一个字都不许拿到明文」的断言口径（可自动化）

1. 夹具里放一个已知假 key（如 `sk-fake-agent-…`），对 `/api/agent/*` 的**每一个响应体**（含 SSE 的**每一帧**、含 `GET /api/agent/config`、含错误响应）断言 `body.contains(fakeKey) == false`。写法照 §5.2 红线的先例（「夹具里明明有那个值」的前提下证明它不在响应里 —— `docs/api-contract.md` §6 末那段的做法）。
2. `/api/agent/config` 的 `data` 只有 `keyConfigured: bool` —— **没有掩码**（掩码仍泄露前后缀，且要多养一份口径）。
3. `:web` 产物（`.wasm` / `.js`）里搜不到任何 key 字样（serve 的 key 从不进 `web/` 源码）。
4. serve 侧不打印：启动横幅只打访问令牌（`server/.../Main.kt` 的 `printStartupBanner`），**key 一条都不打**；`:server` 没装 `CallLogging`（`ServeModule.kt` 只装 `ContentNegotiation`）⇒ 没有请求/响应体日志。
5. key 只出现在两处：serve 进程内存里的 `AgentConfig` 对象、以及上面那个 store 文件（Windows = DPAPI 信封）。

### 5.4 D11 之前该不该默认关：建议默认关

`ServeConfig` 加一档 `--enable-agent`（不写 = 屁岱端点不挂/一律 404 + 中文说明）。理由三条：

1. 它是**持续烧钱**的端点：任何拿到访问令牌的进程（默认回环也拦不住同机其它进程）都能用这个 key 花钱；写端点被滥用是「改一次状态」（可发现可撤销），key 被滥用是持续扣费 + 把校园数据持续发给模型服务商，**发现得晚**。
2. 现网形态是 `0.0.0.0:40961` + 明文 HTTP + 一枚令牌（§15.6.1 的实测）；明文信道上令牌可被同网段嗅探 ⇒ 等于把 key 借出去。
3. `docs/desktop-port-plan.md` §16.4 已经把「serve 从只读变可写、再变可付」列为风险；屁岱是其中**唯一会持续外流数据**的一条。D11（HTTPS + 鉴权）落地后再默认开。
   - 若用户坚持默认开：至少保留回环默认，并在启动横幅按 `config.exposed` 那一段（`Main.kt`）加一句专门警告「这一版屁岱端点会用你的 API Key 打外网」。

---

## 6. 工具层白名单

白名单必须落在 **serve 硬编码**，并且**如实出现在 `/api/agent/config` 的 `toolAllowlist`** 里（同一份常量两个消费者：工具表裁剪 + 页面说明）。

### 6.1 第一批开放（8 条：D10 点名的四条 + 3 条纯逻辑 + 1 条近邻）

| 工具 | 本体从哪来 | 理由 |
|---|---|---|
| `calculate` | `:core` `agentCalculate` | 纯逻辑，零端能力、零取数、零隐私 |
| `app_guide` | `:core` `agentGuideText` | 纯逻辑（App 功能引导 / 食堂），与 Android 逐字同源 |
| `preference` | `AgentMemory`（**serve 侧**落盘） | 它写的是「记忆」，不是校园系统；D10 明确把记忆归 serve；语义边界（只影响「怎么说 / 先查哪个」）写在 `AgentMemory` 的 KDoc 里 |
| `get_calendar` | `:data` `SchoolCalendarApi().terms()`（`ServeEndpointRoutes.kt:106` 已在用）+ `:core` 的周次/节次纯逻辑 | **免登录、公开**；D10 点名「校历」；拼装函数在 `app/.../AgentTool.kt:629-670`（约 40 行）可直接搬 |
| `search_yellow_page` | `:core` `YellowPageApi` | D10 点名；公开通讯录（§5.2 红线例外②明确允许机构总机） |
| `find_faculty` | `:data` `FacultyApi` | D10 点名；公开通讯录。⚠️ 见未决 #4（`contacts` 口径要与桌面统一） |
| `get_schedule` | `:data` `JwxtScheduleApi.schedule()`（**已合并调停补课那一份**）+ `:core` `JwxtChanges.apply` | D10 点名「课表」。⚠️ **必须走 `schedule()` 而不是 `rows()`**：`/api/jwxt/schedule` 端的是排课原样（契约 §5.3 / §5.4 明写），Android 的屁岱吃的是合并后那一份 ⇒ 用 `rows()` 会当场破坏丙口径 |
| `get_empty_rooms` | `:data` `EmptyRoomApi` / `LiveRoomApi`（`JsSession` serve 已注册） | D10 点名「空教室」；拼装 `app/.../AgentTool.kt:954-1051`（约 100 行）可搬 |
| `get_exam_schedule`（建议并入第一批） | 端点 `§5.8 /api/jwxt/exams` 已落地；拼装 22 行（`AgentTool.kt:932-953`） | 成本极低、端点已在；它属「课表」那一族。若不想扩第一批，落第二批并在页面上说明 |

### 6.2 不开放（逐条理由，“涉及写 / 涉及钱 / 涉及学号姓名”的重点说清）

| 工具 | 为什么不开 |
|---|---|
| `set_alarm` | `honored` 空集（浏览器没有系统闹钟）⇒ 工具表不画（与桌面同形） |
| `app_setting` | 浏览器没有「应用设置」这一档（`:app` 的 11 项里 web 只有深色模式等 2 项真有；那 2 项走共享设置屏）⇒ 整档不画（与桌面同形） |
| `add_schedule_event` | **写**（写用户日程）；本体还在 `:app`（Room + 与课程/已有日程的冲突判定，`AgentCapability.SCHEDULE_EVENT_WRITE` 的 KDoc 解释了为什么不可分）；浏览器没有自建日程落盘位 ⇒ 不画（与桌面 TODO 1 同一条） |
| `get_grades` | **本人敏感**（成绩）；正确口径要 `ScoreReportSource` + 移动教务两份合并（`JwappScoreViewModel`，那是屏的活）；D10 没点名 ⇒ 第二批 |
| `get_fitness_score` | **本人敏感**（`:core` 系统提示把「成绩、体测、体重」列为敏感话题）⇒ 要用户明确拍板 |
| `get_attendance` | 考勤（记录里带上课教师名 = §5.2 红线不投影那一类）+ 统计那一栏是 ViewModel 的活 ⇒ 第二批 |
| `get_card_info` | **钱的读数**（余额 / 流水）⇒ 第二批，要用户拍板 |
| `get_coupons` | **钱**（加餐券余额 / 有效期），且同族的「领券」是写路径 ⇒ 不开放。一句话：券是钱，不该由浏览器上的模型代读代领 |
| `get_notifications` | 29 源、23 源有动态挑战（契约 §5 那一行的既有事实）+ `:app` 还带「按身份自动选源」（`identityNoticeSources`）⇒ 搬到共享层是独立一块 |
| `search_school_courses` | 100 行拼装 + 13 个参数；`school-courses` 端点虽在，但这一档属「课表族第二批」 |
| `get_library` | 图书馆座位要站点会话，且 `hasSeatPlan` 在 serve 上仍 false（座位平面图卡在浏览器上恒 null）⇒ 会把卡片画成空壳 |
| `list_zyxf` / `read_zyxf_file` | 学辅资料站；`read_zyxf_file` 的「存进下载」在浏览器上没有落点（`FILE_SAVE` 不列）⇒ 一半功能不可达 |
| `get_textbooks` | 要教务 + 帆软报表 + 学号（虽然学号只进请求）；第二批 |
| `get_lms` / `get_lms_activity` | 附件地址要站点会话、学堂教师名不投影、作业提交是写路径 ⇒ 第二批（先把「附件读不了」在页面上说清） |
| `web_search` / `web_fetch` | ① 是 380 行抓取 / 解析（百度 / 360 / 微信 / 百科 + 反爬 + CookieJar），搬进共享层是独立一大块；② 语义会变：**联网的是 serve 那台机器**（用户以为「我的网络」）；③ D12 的搜索 / 抓取边界还没收口。⇒ 不开放，页面如实说「浏览器这一端的屁岱暂不联网搜索网页」 |

> 页面上怎么呈现：`toolAllowlist` 是真源。建议屁岱首屏那段「能做什么」的说明按它生成（Android 现在靠工具表 + `app_guide`，浏览器上必须靠自己那一段），并在那里写清**不开的几档为什么不开**（一句话级）。

---

## 7. 三份落盘形状（与 `docs/storage-layout.md` §2/§3 对齐）

服务端数据根 = `dataFilesDir()` = `~/.local/share/xjtu-toolbox/`（jvm actual）；账号 = serve 当前登录账号；未登录 = `default`（见缺口 B）。

| 族 | store 名（逐字） | 键 / 文件 | 值形状 | TTL（§5.2 的档位） | 档案映射（§2.2 / §2.3） |
|---|---|---|---|---|---|
| 配置（非敏感） | `agent_config` + suffix | `provider` `model` `base_url` `assistant_name` `search_engine` `thinking_enabled` `reasoning_effort` `show_reasoning` | `agent_config<suffix>.properties`（0600） | `forever`（prefs 无 TTL；§5.2 第 7 条的档位表里没有它） | `accounts/<suffix>/stores/agent_config.json` |
| 配置（密钥） | `agent_config_secure` + suffix | `api_key` | DPAPI 信封 / 明文 0600 | `forever` | **不进档案**（A8 的禁止路径清单里就有 `agent_config_secure*`） |
| 迁移残留 | `agent_config_fallback` + suffix | —— | 只读；`legacyFallbackName` 迁完即删 | — | **不进档案**（§2.4） |
| 记忆 | `agent_memory` + suffix | `__order`（`\u0001` 分隔）+ 动态键；≤20 条；key ≤40 字、value ≤120 字 | `AgentMemory.kt:59-70` | `forever` | `accounts/<suffix>/stores/agent_memory.json` |
| 记忆（旧、不分账号） | `agent_memory` | 同上 | 由 `AgentMemory.prefs()` 一次性迁到首个账号并清掉（`AgentMemory.kt:76-90`）——**serve 侧同样会发生** | — | 迁移后不存在 |
| 会话 | 目录 `agent_sessions` + suffix | `index.json`（`AgentSession[]`：`id` / `title` / `createdAt` / `updatedAt` / `locked`）+ `convo_<id>.json`（`StoredConversation`：`messages` / `llmHistory` / `lastTotalTokens` / `contextExhausted`） | `AgentSessionStore.kt:26-31` | 无（会话不过期） | `files/agent_sessions/index.json` + `files/agent_sessions/convo_<id>.json`（**目录名不带 suffix**，§2.3 明文规定） |
| （工具要的）课表缓存 | `schedule_cache` + suffix | §3.3 那批键（`schedule_current_term` / `schedule_<term>` / `start_date_<term>` / `#written_at` …） | `KeyValueScheduleCacheStore` | `term=90d`（§5.2 第 7 条） | `files/schedule/<key>.json`（`#written_at` 折进 `at`，A10） |

### 7.1 必读的三条相互作用

1. **serve 与桌面在同一台机器上共用同一份屁岱数据**（同一个 XDG 根、同一套 suffix 规则）⇒ 同一账号下 `agent_config*` / `agent_memory*` / `agent_sessions*` 是**同一批文件**。这不是 bug（`storage-layout.md` §1.2 就是同一个口径），但必须写进文档：在这台机器上，桌面端的屁岱与浏览器里的屁岱**看到同一份配置、会话、记忆**。
2. **serve 侧必须传 `secureKeyValueStore`**（0600 properties）给非敏感半与课表缓存，否则 `:core` 的 jvm `keyValueStore` 是进程内存（`storage-layout.md` §0 第 3 条 = 该文档的「第 0 号前置条件」）。
3. **TTL 归各端代码判、档案里只存 `at`**（§5.2 第 2 条）⇒ 上面三份都是 `forever`，一个 TTL 判定都不新增。

---

## 8. 与 `:app` / `:desktop` 的行为差异表（逐条：做不到什么 / 为什么 / 页面怎么说）

| 能力 | `:app`（基准） | `:desktop` | 浏览器 | 页面上怎么如实说明 |
|---|---|---|---|---|
| API Key | 本机加密 prefs | 本机 `agent_config_secure*`（**今天明文**，缺口 A） | **serve 侧** | 配置面板 Key 那一行换成只读徽标：「Key 存在运行 serve 的那台机器上」 |
| 模型列表拉取 | 本机直连服务商 | 同 | 走 serve `/api/agent/models` | 无差异 |
| 会话 / 记忆 | 本机文件 | 本机数据根 | serve 数据根（与桌面共根） | 无差异（面板里「记住的偏好」照旧可见可删，数据从 serve 来） |
| 闹钟 / 应用设置 / 自建日程写入 | ✅ | ❌ | ❌ | 工具表不画（`honored` 空集）；「能做什么」按 `toolAllowlist` 列，并说清不开的那几档 |
| 自建日程**读**（`get_schedule` 会并进去） | Room | KV 单键 | 只有 serve 读得到的那些（桌面那份 KV 读得到；**Android 的 Room 读不到**） | 页面上加一句：「你在 App 里加的自建日程，浏览器这一端的屁岱看不到」 |
| 相册挑图 / 内联远程图 / 附件 | ✅ | ❌（TODO 3/4/5） | ❌ | 回形针不画（`IMAGE_INPUT` 不列）；内联图退成一行可点的链接（既有降级路，不必说明） |
| 存进下载 / 图书馆平面图 | ✅ | ❌ | ❌ | 资料行不画下载动作；座位卡退成一行 |
| 首屏卡片（`welcomeFacts`） | ✅ | ❌ | ❌ | 卡片直接不出（与桌面同形，不加说明） |
| 主动气泡（`ProactiveBubble` / `pidai_proactive`） | ✅ | ❌ | ❌ | 差异清单一条；不画任何开关（设置页「通知与提醒」整组本来就不画） |
| 形象（`pidai_appearance`） | ✅ | ❌ | ❌（v1） | 底栏图标仍是静态 `SmartToy`（现状）；差异清单一条 |
| MFA | 弹窗 | `DesktopMfaDialog` | SSE `mfa` 事件（或轮询 `/api/session/mfa`） | 同一条气泡文案；**不写手机号**（§5.1 TODO ① 不投影） |
| 上下文耗尽（800k） | 弹窗 | 同 | 同（serve 判、浏览器弹） | 逐字同一句 `CONTEXT_EXHAUSTED_MESSAGE` |
| 停止生成 | 取消协程 | 同 | `POST /api/agent/stop` / 断开 | 「（已停止）」同一条 |
| 并发发消息 | **静默忽略** | 同 | **409 + 页面提示** | 有意更清楚的一处（要写进差异清单） |
| 一轮失败 | `agentHttpErrorText` 中文短句 / `出错了：$detail` | 同 | 同（serve 用**同一个**函数生成 message） | 逐字相同 |
| 登录失效 | 「登录已失效，请返回并重新进入对应功能页面完成认证后再试。」 | 同 | 同文案，但浏览器上「返回并重新进入对应功能页面」= `?route=serve` 会话屏 | ⚠️ 指向不明（未决 #8） |
| 网页搜索 / 抓取 | ✅ | ❌ | ❌（v1） | 「浏览器这一端的屁岱暂不联网搜索网页」 |
| 「改上一条重发」 | ✅ | ✅ | 不画（v1，见未决 #7） | 不画 = 点不到；如果画了就得实现（「点了会失败的按钮一个都不画」） |

---

## 9. 未决问题（逐条：建议 + 理由）

1. **浏览器上能不能填 API Key？** 建议 **v1 不画**（只在 serve 那台机器设置）。理由：D11 之前是明文 HTTP ⇒ 在浏览器输入等于在局域网上明文传一次 key；而且「输入也是持有」，会破坏「一个字都不拿到」的最强口径。
2. **D11 之前屁岱端点默认关吗？** 建议**默认关**（`--enable-agent`），理由见 §5.4。
3. **`:app` 的工具拼装要不要逐字搬进共享层？** 建议**逐字搬**（`get_calendar` / `get_schedule` / `get_empty_rooms` / `get_exam_schedule` 约 250 行），serve 与**桌面**共用一份，`:app` 原件暂不动（C1），用一条逐字对比测试钉住两份不漂。理由：丙口径要求同一句话；且这本是桌面 TODO 2 的活（`DesktopAgentHost.kt` 的 TODO 2 明写「取数 + 格式化一起搬进共享层，一次点一档」）。
4. **`find_faculty` 的 `contacts` 口径**：桌面直连能拿到邮箱/主页，serve 契约里 `contactsAvailable:false` 固定（`docs/api-contract.md:193`）。三端要一致，建议统一「不传 `contacts=1`」，并把桌面口径一起收（会改桌面行为 ⇒ 要拍板）。
5. **课表缓存的落盘实现**：建议 serve 直接用 `secureKeyValueStore`（能跑、与桌面同形），并把「`:core` 的 jvm `keyValueStore` 不落盘」这条前置条件（`storage-layout.md` §0 第 3 条）登记为未决 #0 的处置之一。
6. **`404` 语义扩展**（会话不存在）vs 用 `200 + conversation:null`（`chat` 没法用后者）。建议 `chat` 用 `404` + 指名 message，并在契约里显式声明；备选是 `400`。
7. **「改上一条重发」** 在浏览器上要不要（要的话 serve 需要一个 `replaceLast` 语义，或浏览器只改 UI 而服务端删掉那一轮的历史）。
8. **两句文案的逐字全等 vs 如实**：配置面板的「API Key 配好就能聊」（`AgentChat.kt:247`）与登录失效那句里的「返回并重新进入对应功能页面」。建议如实改（但要破一次「逐字全等」，需拍板口径）。
9. **`AgentConfig` 加 `keyConfigured` 这一格**是否接受（默认 `false`、不落盘 ⇒ 三端行为不变）。若拒绝，另一个选择是把「配好了没」做成**宿主能力**（`AgentHost` 上加一个只读槽位），代价是又长一个缝。
10. **hero / 形象**：`:core` 已有 `agentWelcomePlainHero`，`bot/**` 也在 `:core`（可画），把 `PidaiAppearancePanel` 搬进 `:core` 是 D12 之后的事；v1 用默认。
11. **D14 的验收**（`:web` 测试源集）：最少三条 —— ① SSE 帧解析纯函数用例（含注释帧 / 半帧 / 多帧黏包）；② 白名单与页面说明同源断言；③ **`:server:test` 的 `ServeAgentHost` 端到端**（照 `desktop/src/test/.../DesktopAgentJvmTest.kt` 的 `FixtureAgentTransport` 先例：夹具传输 + 真落盘 + 工具表逐档断言）——这一条**不需要浏览器**就能在 Linux 上跑，是性价比最高的一条。
12. **`:core` 抽 `AgentChatSession`** 是否现在做（它是「屏还是那一屏」的前提；不做的话，`:web` 就得自己写一屏，丙口径当场破）。

---

## 10. 证据清单与「未核对」

### 10.1 实际读过的文件

**`:core`（agent 域）**：`AgentHost.kt` · `AgentCapability.kt` · `AgentConfig.kt` · `AgentSessionPersistence.kt` · `AgentToolCatalog.kt` · `AgentToolSchema.kt` · `ToolReply.kt` · `AgentTransport.kt` · `AgentRunner.kt` · `AgentViewModel.kt` · `AgentScreen.kt` · `AgentChat.kt` · `AgentComposer.kt`（grep）· `AgentConfigPanel.kt` · `AgentMemory.kt` · `AgentPrompt.kt` · `AgentWidget.kt`（头部）· `AgentStaleReply.kt` · `AgentPendingPrompt.kt` · `AgentWelcome.kt`（grep）· `AgentVision.kt`（grep）

**`:data`（agent / 平台）**：`agent/{AgentConfigStore.kt, AgentSessionStore.kt, SseAgentTransport.kt, AgentModelFetcher.kt, AgentTitleGen.kt}` · `platform/{SecureStore.kt, DataFiles.kt, DataFiles.jvm.kt}` · `jvmMain/platform/{SecureStore.jvm.kt, ProtectedKeyValueStore.kt, SecretProtector.kt, CredentialStore.jvm.kt}` · `network/HttpClients.kt`（头部）· `data/build.gradle.kts`（目标与依赖）

**`:app`**：`agent/{AgentTool.kt（前 500 行 + 629-672）, AndroidAgentHost.kt, PidaiAppearance.kt, ProactiveBubble.kt（grep + 100-180）}`

**`:desktop`**：`DesktopAgentHost.kt` · `DesktopAgentTools.kt`（前 120 行）· `test/DesktopAgentJvmTest.kt`（前 120 行）

**`:server`**：`ServeModule.kt` · `AccessTokenGate.kt` · `ServeRouteSupport.kt` · `ApiEnvelope.kt` · `ServeConfig.kt` · `Main.kt` · `ServeSession.kt`（前 200 行）· `JwxtRoutes.kt`（前 120 行）· `ServeEndpointRoutes.kt`（grep）· `server/build.gradle.kts`

**`:web` / `:core/net`**：`web/…/{ToolboxWebApp.kt(grep), WebSupport.kt(grep), WebShell.kt(grep), WebFallbackScreens.kt, WebSettingsHost.kt, Data.kt, ServeSession.kt, BrowserUrl.kt, WebScheduleHost.kt(grep)}` · `core/net/{ApiMode.kt, CampusApi.kt(头部), ToolboxHttp.kt(grep), ToolboxEngine.wasmJs.kt}` · `core/build.gradle.kts`（grep）

**文档**：`docs/api-contract.md` §1–§5.4、§5.8、§6–§8 · `docs/storage-layout.md` 全文 · `docs/desktop-port-plan.md` §15.1–§15.7、§16 · `docs/windows-delivery.md` §5.1–§5.5 · `gradle/libs.versions.toml`（ktor）

### 10.2 未核对（不许当结论用）

1. Ktor 3.6 的 `ktor-server-sse` 与 `respondBytesWriter` 的具体 API 面（本文件按「手写帧最稳」给的建议，没有验证）。
2. Ktor **Js 引擎**在 wasmJs 上是否支持流式响应阅读（本仓零先例；建议直接用 `@JsFun` fetch 桥，先写探针）。
3. `@JsFun` 把 Kotlin lambda 当回调传给 JS 在 wasmJs 上是否可直接用（本仓三处 `@JsFun` 都无回调参数）。
4. `FacultyApi` 是否默认带 `contacts=1`（只读到契约 §5.2 的 `contactsAvailable:false` 与桌面能取到更多这一层，没有逐行读 `data/.../FacultyApi.kt` 的默认）。
5. serve 侧工具层要的 `DataCache` 等价物在 serve 上怎么落（Android 是 `cache/data_cache<suffix>/`；本文件按 `secureKeyValueStore` 推，没有验证 `KeyValueScheduleCacheStore` 的构造参数）。
6. `:web` 侧没有任何测试源集（§15.6.1 明写），所以本设计的浏览器侧没有任何现成验收网可复用。

---

## 11. 给主代理的行动清单（建议序位）

1. **先定口径**（回用户）：未决 #1 / #2 / #9（浏览器不填 key、D11 前端点默认关、`AgentConfig` 加 `keyConfigured`）。这三条决定后面所有形状。
2. **补契约 §5.9**：§2 的 14 个端点 + §3 的 11 类事件 + 红线条目 + 404 / `conversation:null` 的声明。照 `docs/api-contract.md` §7 第 1 条，一次提交里本文档 + `:testkit` 夹具 + 实现一起动。
3. **`:core` 三处小改**（§4.4：`AgentChatSession` 抽口 + `agentToolLabel` / `agentNavSuggestions` 上提 + `AgentConfig.keyConfigured` 与面板三个槽位），跑 `:app` 429 / `:core` 439 / `:desktop` 100 三条基线。
4. **修缺口 A**（`AgentConfigStore` 加 `secureStoreOf`；serve 传 protected；桌面是否同修 = 待拍板），加一条「密文半无信封前缀则明文仍可读」的迁移用例。
5. **白名单工具层**：先搬 `get_calendar` + `get_schedule` + `get_empty_rooms`（+ `get_exam_schedule`），逐字搬 `:app` 的拼装；`calculate` / `app_guide` / `preference` 用现成共享实现；黄页 / 教师检索照 `DesktopAgentTools` 同形。
6. **`ServeAgentHost` + 路由（含 SSE 手写帧）+ `--enable-agent` 开关**，配 `:server:test` 的端到端（照 `DesktopAgentJvmTest` 的夹具传输）。
7. **`:web` 接线**：`WebAgentSession`（事件 → 状态）+ `@JsFun` fetch / SSE 桥 + `AgentScreen(session = …)` + `WebGapNote` 一屏说明 + `WebSupport.openableRouteIds` 把 `Agent` 从差集里去掉。
8. **D14 的 `:web` 测试源集**：SSE 解析纯函数 + 白名单 / 文案同源 + 真站点点位清单（D9 口径）。
