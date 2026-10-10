# serve 模式的 `/api/*` 契约（D5）

> **状态**：草案 v0（2026-10-09）。骨架、通用形状、安全口径、端点清单已经钉下；**每条端点的响应字段由"搬它的那次提交"补齐**
> （谁把某个 `*Api` 搬进 `:data`，谁就顺手把那一行从 `TODO` 改成实测形状）。
> **本文件是 `:server` 的对外接口真源**（D5：文档 + 夹具契约测试都在本仓；campus-api 只作参考，不再是依赖）。

## 1. 这是什么、谁消费

- **接口本体**：`serve` 模式的 `:server`（同一份 `:data` 数据层，无窗口）托管的 `/api/*`。
- **唯一消费者**：`:web`（Kotlin/Wasm，浏览器里）。窗口模式**没有契约**（UI 与数据层同进程 ⇒ 直接函数调用）。
- **为什么必须重新设计而不是照抄 campus-api**（D5 原文）：以**端口/模型的自然形状**为准，逐条与旧形状对照 ——
  旧的合理就沿用（这样 `:web` 对应那些实现可以留着），不合理就改。
- **数据层是同一份**：`:server` 的每个端点都是 `:data` 里某个 `*Api` / `*Source` 的**薄包装**，
  不做二次解析（解析与口径在 `:data`，有测试钉着）。

## 2. 与 campus-api 的三条本质差别（这才是"重新设计"的理由）

| | campus-api（旧） | `/api/*`（新） |
|---|---|---|
| **能力** | **只读**：图书馆/场馆/评教都在自己的 status 里写 `readOnly:true` | **含写路径**：约座 / 换座 / 签到 / 退座 / 下单 / 取消 / 付款码（`:data` 里已经有了，campus-api 没有） |
| **谁能做** | 一刀切只读 | **能力开关随部署与账号变**：`canBook` / `canCancel` / `canSubmit` / `hasSeatPlan` / `availableSources` 必须**如实**出现在响应里，屏按它决定画不画按钮（"点了会失败的按钮一个都不画"） |
| **会话** | 单账号、回环、零鉴权（凭据托管在 campus-api 进程里） | **浏览器不带任何凭据**：登录/登出/短信二验都经 `/api/session*`，会话在 `:server` 进程里（默认只监听 `127.0.0.1` + 访问令牌） |

> 推论（写进 `:web` 的改造）：浏览器端原来那 13 个「只读投影」实现在 serve 模式下**要退休**——
> 它们把能力开关硬编成 `false`（`canBook=false` 那种）。新契约里开关是**响应的一部分**，
> 于是同一份 `:web` 屏在 serve 模式下能看到真实能力（能约座就是能约座）。

## 3. 默认安全口径（D3 定稿，实现时逐条守住）

1. 默认**只监听 `127.0.0.1`**；
2. 首次启动生成并**打印访问令牌**；浏览器侧必须带（`Authorization: Bearer <token>`，或令牌换 cookie 后随请求带）；
3. 对外暴露必须**显式** `--host 0.0.0.0`，文档写明"自己加反代 + 鉴权，风险自负"；
4. `/api/status` 是**唯一免令牌**端点（只报"活着 + 有没有会话"），且**不含身份信息**（不返回学号/姓名）。
5. **`--host 0.0.0.0` + HTTP ⇒ 非安全上下文**（2026-10-10 实测，Web 外壳那边撞到的）：
   `navigator.clipboard` 在非安全上下文里**根本不存在** ⇒ 页面里任何「复制」按钮会**静默失败**
   （点下去没反应，也不报错）。默认的 `127.0.0.1` 不受影响（localhost 属安全上下文）。
   ⇒ 对外暴露时要么自己上 HTTPS（反代 + 证书），要么给复制路径留一条 `document.execCommand('copy')`
   的降级；**不要**默认就走降级 —— 那是把「不安全」常态化。同类受影响的还有 `crypto.subtle`、
   地理位置、Service Worker（`serve` 模式若打算做 PWA，先把这一条想清楚）。

## 4. 通用形状

- **信封**：`{ "code": 0, "data": <T|null>, "message": "<错误文案>" }`（`code != 0` 时 `message` 必须是能直接给用户看的中文短句，口径与 `:core` 的 `FriendlyError` 一致）。
- **例外**：`/api/status` 是**裸对象**（历史遗留）。要么在实现时统一成信封，要么在本文档里明确标注为例外 —— **只能选一个**。
- ⚠️ **时间一律 ISO-8601 带时区的字符串**（`2026-10-09T23:52:00+08:00`）。
  **不要用 epoch 秒/毫秒**：这是"静默漂移"的头号来源（秒 vs 毫秒、本地时区 vs UTC 在两端的表现不同，
  而两端的 UI 都会"看起来对"）。
- **分页**：`?page=<1-based>&size=<n>`，响应带 `{ "page": n, "size": n, "total": n }`；
  **过滤与排序由服务端定，并在本文档写死**（客户端的顺序假设必须能被夹具测试钉住）。
- **空值与缺失**：`null` = 上游明确说"没有"；字段缺失 = 本端不投影。两者**不能混用**（`MyBookingInfo` 的 `total:null ≠ 0` 就是这条的实例）。
- **能力开关**：每条涉及写操作的端点/对象里带上开关字段（见 §2），客户端**不得**靠猜。

## 5. 端点清单

优先级：**P0 = Stage A 只读屏**（验收要求 23~27 条路由可用）· **P1 = 写路径**（Stage B）· **P2 = 宿主能力**（Stage C）。

| 路径 | 对应端口（`:core`） | 屏 | 优先级 | 沿用旧形状？ |
|---|---|---|---|---|
| `/api/status` | ——（探活，裸对象） | （外壳） | P0 | 沿用（但见 §3.4：不含身份） |
| `/api/session` `/api/session/login` `/api/session/logout` `/api/session/mfa` | 新（`:data` 的 `SessionManager`） | 登录屏 / MFA 弹窗 | **P0（新）** | 无旧形状可沿用 |
| `/api/calendar/school` | `SchoolCalendarSource` | 校历 | P0 | 沿用（`UpstreamSchoolCalendar` 已有解析） |
| `/api/jwxt/terms` `/api/jwxt/term` `/api/jwxt/term-start` | `ScheduleSource` 一族 | 课表 | P0 | 沿用 |
| `/api/jwxt/grades` | `ScoreReportSource` | 成绩报表 | P0 | 沿用 |
| `/api/jwxt/school-courses` | `SchoolCourseSource` | 全校课表 | P0 | **要改**：campus-api 的投影少一批字段（人数/学时、`YPSJDD`、开课单位、公选筛选）⇒ 屏把筛不了的控件隐藏了。服务端自己直连教务后**能补齐**，补齐后 `:web` 可以把那两档控件放回来 |
| `/api/jwxt/evaluations` `/api/jwxt/evaluations/status` | `JudgeSource` | 评教 | P0 | **要改**：`canSubmit` 在新契约里取决于实现（只看不提交仍是默认），但**字段必须出现在响应里** |
| `/api/fitness/years` `/api/fitness/score` | `FitnessSource` | 体测 | P0 | 沿用（姓名/学号旧版按隐私口径不投影；新契约里**是本人的数据**，应带上） |
| `/api/info/yellowpage` | `YellowPageSource` | 黄页 | P0 | 沿用 |
| `/api/info/faculty` | `FacultySource` | 教师检索 | P0 | **要改**：旧版刻意不传 `?contacts=1`、不解析主页 ⇒ 新契约按能力开关如实报（`contactsAvailable`） |
| `/api/notification/list` `/api/notification/sources` | `NoticeSource` | 通知 | P0 | 沿用（29 个源） |
| `/api/inbox` | `InboxSource` | 消息收纳 | P0 | 沿用（四路聚合） |
| `/api/emptyroom/cdn` `/api/emptyroom/rooms` | `EmptyRoomSource` | 空闲教室 | P0 | **要改**：旧版只有 CDN 一档（`availableSources` 只报它）。直连教务后"实时状态/直查教务"两档能回来 ⇒ 开关如实报 |
| `/api/library/campus` `/api/library/areas` `/api/library/seats` `/api/library/my` | `LibrarySource` | 图书馆座位 | P0 | **部分沿用 + 补**：`/campus` `/areas` `/seats` `/my` 形状可沿用（逐字段映射见 `CampusLibraryApi` 的 KDoc 表）；**`/api/library/book` `/swap` `/action` 是新增的写端点**（P1），`canBook=true` 时屏上那些按钮才画 |
| `/api/card/balance` `/api/card/transactions` | `CampusCardSource` | 校园卡 | P0 | 沿用（`amount ← signed`、`description ← channel`、`cardType ← cardName`；⚠️ 挂失口径 `lostflag` vs `barflag` 要在本文档里明确选一个） |
| `/api/venue/products` `/api/venue/slots` `/api/venue/orders` `/api/venue/status` | `VenueSource` | 体育场馆 | P0/P1 | **部分沿用 + 补**：`slots` 的 `surplus` **不要用旧字段**（旧口径在 `all == used` 时给 0，而两端一致的读法是 `status == 1` ⇒ 可订）；`/book` `/cancel` 是新增写端点（P1） |
| `/api/coupon/…` `/api/payment-code` | 新 | 加餐券 / 付款码 | P2 | 无旧形状 |
| `/api/v1/personal/me/user` | —— | （身份） | P2 | ⚠️ 待确认：这条出现在 `:core` 现有代码里，实现时先查清它是谁的投影 |

> **TODO（每条一行，由搬它的提交补齐）**：请求参数的确切名字与默认值 · 响应字段的逐条形状 ·
> 与 `:app` 同一条上游样本的逐字段一致性证据（夹具）· 该端点的能力开关取值表。

## 6. 夹具契约测试（D5 要求，不能只写文档）

- 位置：`:server:jvmTest`（新）——起 `:server`，用**同一批上游样本**（已抽成 `:testkit`，隔壁刚建的）灌进 `:data`，
  对 `/api/*` 的响应**逐字段断言**；
- 与既有测试的关系：`:data:jvmTest` 的夹具测的是"数据层"；契约测的是"**同一份数据层 × HTTP 形状**"，
  两者都要在：前者保证解析没漂，后者保证形状没漂；
- 用**上游样本 → 期望 JSON** 的成对文件（放 `:server/src/jvmTest/resources/`），避免断言散在代码里。

## 7. 变更纪律

1. 改契约 = **同时改**：本文档 + 夹具 + `:web` 对应实现（三处都不动就是漂移的开始）；
2. `:web` 侧只认**本文档**，不认 campus-api 的字段名（那条路在 serve 模式下退休）；
3. 与 `:data` 的分工：**`:data` 定义模型与口径，本文档只定义"怎么把它端出去"**——同一个字段名不在这两处各起一个。

---

## 8. 这份草案的依据（谁写的、读了什么）

- 读者：`serve` 模式的实现者（`:server` + `:web` 改造），以及每次把某个 `*Api` 搬进 `:data` 的那次提交。
- 依据（全部是只读核对）：`docs/desktop-port-plan.md` 的 D3/D5/D6 与 §3/§5/§6；
  `:core/core/net/` 那 13 个 campus-api 实现（它们的类 KDoc 里有逐条字段映射与"如实降级"说明）；
  `:core` 里 12 个取数端口的接口 KDoc；`web/tools/serve-same-origin.py`（serve 模式的雏形）。
- 未核实的部分已标 `TODO`/`待确认`，**不要当成结论用**。
