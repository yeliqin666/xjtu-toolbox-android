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
| **能力** | **只读**：图书馆/场馆/评教都在自己的 status 里写 `readOnly:true` | **含写路径**（已落地的两种）：图书馆约座 / 换座 / 签到 / 退座（§5.3.1）与场馆下单 / 取消（§5.3.2）。⚠️ **付款码与加餐券不在其中**：`:data` 侧两份实现都有（`data/src/commonMain/kotlin/com/xjtu/toolbox/pay/PaymentCodeApi.kt`、`data/src/commonMain/kotlin/com/xjtu/toolbox/coupon/CouponApi.kt`），Android 与桌面的窗口模式已在用 ⇒ 2026-10-13 起加餐券的**只读**两发（`/api/coupon/{list,detail}`）已落地（§5.5）；⚠️ 2026-10-14 起**付款码的「拿码」**也落地了（`/api/pay/code` + `/api/pay/vouchers`，§5.7），但**领券**（`activateCoupon`）、**付款码的勾选同步**（`updateVoucherStatus`）这类真改上游状态的写路径仍**没有对应端点**（「支付/扣款」那个动作客户端本来就没有，不是本轮砍的）。⚠️ 2026-10-14 的第二批只读域（§5.6）同一条口径：思源学堂 / 教材中心 / 移动教务成绩 / 人脸识别签到四条链在 `:data` 里都有实现（Android 与桌面端直连在用），本轮只端**只读**那一半 —— 学堂的作业**提交**与附件**下载**、教材的**全文阅读**、成绩的**分项**与成绩**报表**都不端，签到的姓名/学号按红线不投影（逐条见 §5.6）。⚠️ 第三批（§5.7）再补三族同一条口径：反馈只端**读**（提交反馈是写路径 ⇒ 不端）、匹配交友只端**消费统计**（学工档案那一半 `:data` 里没有源 ⇒ 不端），WebVPN 那一族是**纯函数**（不收会话、不碰上游、不改任何状态）。⚠️ 第三批的 `:web` 消费方 2026-10-14 也接上了（§5.7 的消费方一节），但**“端出来了”不等于“这一端真用得上”**：付款码那一族 2026-10-15 起三端**连码阵都画得出来**（D12 ①：二维码与 Code128 的编码器搬进 `:core` 的 commonMain，见 §5.7 的消费方一节）、券只读，反馈只能读不能提；匹配交友那一族 2026-10-15（D12 ②）起**三端都能出码了**（zlib 与 URL-safe Base64 两条缝搬进 `:core` 的 commonMain），浏览器上那张说明页已删、直接挂共享屏 |
| **谁能做** | 一刀切只读 | **能力开关随部署与账号变**：`canBook` / `canCancel` / `canSubmit` / `hasSeatPlan` / `availableSources` 必须**如实**出现在响应里，屏按它决定画不画按钮（"点了会失败的按钮一个都不画"） |
| **会话** | 单账号、回环、零鉴权（凭据托管在 campus-api 进程里） | **浏览器不带任何凭据**：登录/登出/短信二验都经 `/api/session*`，会话在 `:server` 进程里（默认只监听 `127.0.0.1` + 访问令牌）。令牌先换成 cookie `serve_token`（只有它能被浏览器带着走），之后的请求就只靠它 |

> 推论（写进 `:web` 的改造）：浏览器端原来那 13 个「只读投影」实现在 serve 模式下**要退休**——
> 它们把能力开关硬编成 `false`（`canBook=false` 那种）。新契约里开关是**响应的一部分**，
> 于是同一份 `:web` 屏在 serve 模式下能看到真实能力（能约座就是能约座）。

## 3. 默认安全口径（D3 定稿，实现时逐条守住）

1. 默认**只监听 `127.0.0.1`**；
2. 首次启动生成并**打印访问令牌**；浏览器侧必须带（`Authorization: Bearer <token>`，或令牌换 cookie 后随请求带）。
    cookie 名固定 **`serve_token`**，由 `GET /api/session` 发出，属性随形态变（D11 批④，实现：
    `AccessToken.setCookieHeader(token, secure)`）：
    - **明文形态（默认，不给新参数）**：`Path=/; HttpOnly; SameSite=Lax` —— **逐字不变**；
    - **HTTPS 形态（`--https`）**：多一个 `Secure`。
    浏览器在地址栏里贴不了 `Authorization`，所以实际形态就是「先用 Bearer 换 cookie，之后只带 cookie」；
    明文那一档不能带 `Secure`（浏览器会直接丢掉一个来自 http:// 的 Secure cookie）。
3. 对外暴露必须**显式** `--host 0.0.0.0`，文档写明"自己加反代 + 鉴权，风险自负"；
4. `/api/status` 是**唯一免令牌**端点（只报"活着 + 有没有会话"），且**不含身份信息**（不返回学号/姓名）。
5. **`--host 0.0.0.0` + HTTP ⇒ 非安全上下文**（2026-10-10 实测，Web 外壳那边撞到的）：
   `navigator.clipboard` 在非安全上下文里**根本不存在** ⇒ 页面里任何「复制」按钮会**静默失败**
   （点下去没反应，也不报错）。默认的 `127.0.0.1` 不受影响（localhost 属安全上下文）。
   ⇒ 对外暴露时要么自己上 HTTPS（反代 + 证书），要么给复制路径留一条 `document.execCommand('copy')`
   的降级；**不要**默认就走降级 —— 那是把「不安全」常态化。同类受影响的还有 `crypto.subtle`、
   地理位置、Service Worker（`serve` 模式若打算做 PWA，先把这一条想清楚）。
   **2026-10-15（D12 ③ 查清 + D11 批④）**：“对外暴露就得自己上 HTTPS”这条**已经能自己给**了（`--https`，§3 第 6 条）；而共享屏里那几处复制走的是 CMP 在 wasm 上的 `WasmPlatformClipboardManager`，它只调 `navigator.clipboard.writeText`，非安全上下文时**只 `console.warn`**（点下去没反应、也不报错 —— 上面那句“静默失败”实测就是这一条）。所以「这一份部署到底有没有」做成了 `?route=probe` 上的「浏览器能力」表（`web/.../WebBrowserCapabilities.kt`），用户拿自己的地址当场就能核。
6. **serve 内置 HTTPS（D11 批④，2026-10-15 落地）**：`--https` 把 serve 切成 HTTPS 形态 ——
   `--host/--port` 就是 HTTPS 的地址与端口，证书默认 `<数据根>/tls/serve.p12`（`--cert` / `--cert-alias`
   可指别处，证书目录与访问令牌同一个数据根），没给 `--cert` 时**缺证书就现生成一张自签**
   （JDK 的 keytool，SAN 含 `localhost` 与 `127.0.0.1`）。**三点口径**：
   - 不给 `--https` ⇒ 明文 HTTP + 令牌闸门，**与今天逐字相同**（默认行为不动）；
   - HTTPS 形态下明文监听只绑 `127.0.0.1`（本进程内部用），令牌闸门与 `/api/session` 换 cookie 的语义
     一概不变，只多一个 `Secure`；
   - 证书缺了 / 路径不存在 / 密码不对 / 文件不是证书库 ⇒ **一律明确报错、退出码 2**，
     **绝不静默回落成明文**（“我以为开了 HTTPS”比“起不来”糟得多）。
   怎么开（自签一条命令 + 自带证书库 + 证书放哪儿 + 浏览器怎么信任）：
   `docs/linux-delivery.md` / `docs/windows-delivery.md` 的「怎么开 HTTPS」那一节。
7. **在 HTTPS 落地用起来之前，写 / 支付端点不要挂在公网口上**（§16.4 第二条风险，P1 的判据）：
   `0.0.0.0` + 明文 + 一枚令牌的那个口子只能自用；对外要么 `--https`，要么自己加反代。
   今天已落地的写路径（图书馆约座 / 场馆下单，§5.3.1 / §5.3.2）与将来 D7 那一批（含真支付）都适用这一条。

## 4. 通用形状

- **信封**：`{ "code": 0, "data": <T|null>, "message": "<错误文案>" }`（`code != 0` 时 `message` 必须是能直接给用户看的中文短句，口径与 `:core` 的 `FriendlyError` 一致）。
- **`code` 就是 HTTP 状态码**（2026-10-11 落地 `/api/session*` 时定下，`:server` 的 `ApiErrors` 是这一条的实现）：
  成功 `code == 0` 且 HTTP 200；失败时 `code` 与 HTTP 状态码**逐位相同** —— 一条信息不出现两种写法，
  客户端读哪一个都一样。已用的码：`400` 请求体形状不对 · `401` 无令牌 / 登录被拒 / 学校会话过期（重新登录） · `404` 端点不存在 ·
  `409` 另一次登录正在进行 / 上游明确拒绝一次写动作（§5.3.1） · `502` 上游或网络的故障（稍后重试可能就成）。
- **例外（现在是四条）**：① `/api/status` 是**裸对象**（历史遗留）；② `GET /api/venue/pay`
  **不当信封答** —— 它是给浏览器点的**跳转**（`302` + `Location`，没有 `code`/`data`/`message`，见 §5.3.2 第 6 条）；
  ③ `GET /api/dzpz/transcript/download?ticket=` 回 `application/pdf` 字节（2026-10-20 落地，见 §5.12）；
  ④ `GET /api/jiaocai1/page` 与 `GET /api/jiaocai1/cover` 回 `image/jpeg` 字节（2026-10-21 落地，见 §5.14）。
  ③④ 是同一种东西（**二进制载荷**）：成功那一半不是信封，**失败那一半仍是信封**（错误码与中文短句照旧），
  而且都挂在令牌闸门内。这两条之外，`/api/…` 的每个响应都是信封；要开新例外就写进这一条（那是契约变更，
  不是实现细节）。
  ⚠️ 这一行原本写的是「只有两条，不许再开第三条」—— 那是**过期描述**：③ 在 §5.12 落地时就已开了，
  但这一行没同步。2026-10-21 一并更正（顺带把 ④ 记进来）。
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
| `/api/session` `/api/session/login` `/api/session/logout` `/api/session/mfa` | 新（`:data` 的 `SessionManager`） | 登录屏 / MFA 弹窗 | **P0（新）** | 无旧形状可沿用（逐条形状见 §5.1） |
| `/api/calendar/school` | `SchoolCalendarSource` | 校历 | P0 | 沿用（`UpstreamSchoolCalendar` 已有解析） |
| `/api/jwxt/terms` `/api/jwxt/term` `/api/jwxt/term-start` `/api/jwxt/schedule`（**`?merged=1` 那一档 2026-10-14 批①收尾新开**，见 §5.9） | `ScheduleSource` 一族（`:data` 侧：`AppSchoolCourseSource` + `JwxtScheduleApi`） | 课表 | P0 | 沿用（`?merged=1` 是**新形状**：直接端 `:data` 已合并的那一份） |
| `/api/jwxt/changes` `/api/jwxt/exams` `/api/jwxt/textbooks` | `JwxtScheduleSource`（`:data` 侧 `JwxtScheduleApi`） | 课表（调停补课 / 考试 / 教材） | **P0 · 已落地 2026-10-14** | **新形状**：三条都是**只读**；`changes` 只是记录、**不掺课表**（合并那一层在 `:core` 的 `JwxtChanges.apply`），`textbooks` 要学号但**学号只进请求不进响应**；逐字段见 §5.8 |
| `/api/jwxt/grades` | `ScoreReportSource` | 成绩报表 | P0 | 沿用 |
| `/api/jwxt/school-courses` | `SchoolCourseSource` | 全校课表 | P0 | **要改**：campus-api 的投影少一批字段（人数/学时、`YPSJDD`、开课单位、公选筛选）⇒ 屏把筛不了的控件隐藏了。服务端自己直连教务后**能补齐**，补齐后 `:web` 可以把那两档控件放回来 |
| `/api/jwxt/evaluations` `/api/jwxt/evaluations/status` | `JudgeSource` | 评教 | P0 | **要改**：`canSubmit` 在新契约里取决于实现（只看不提交仍是默认），但**字段必须出现在响应里** |
| `/api/fitness/years` `/api/fitness/score` | `FitnessSource` | 体测 | P0 | 沿用（姓名/学号旧版按隐私口径不投影；新契约里**是本人的数据**，应带上） |
| `/api/info/yellowpage` | `YellowPageSource` | 黄页 | P0 | 沿用 |
| `/api/info/faculty` | `FacultySource` | 教师检索 | P0 | **要改**：旧版刻意不传 `?contacts=1`、不解析主页 ⇒ 新契约按能力开关如实报（`contactsAvailable`） |
| `/api/notification/list` `/api/notification/sources` | `NoticeSource` | 通知 | P0 | 沿用（29 个源） |
| `/api/inbox` | `InboxSource` | 消息收纳 | P0 | 沿用（四路聚合） |
| `/api/emptyroom/cdn` `/api/emptyroom/rooms` | `EmptyRoomSource` | 空闲教室 | P0 | **要改**：旧版只有 CDN 一档（`availableSources` 只报它）。直连教务后"实时状态/直查教务"两档能回来 ⇒ 开关如实报 |
| `/api/emptyroom/seat-count` | `EmptyRoomSource.seatCount`（`:data` 侧 `AppEmptyRoomSource` + `EmptyRoomApi`） | 空闲教室（座位数） | **P0 · 已落地 2026-10-14** | **新形状**：`?room=` 必填（教室名）；取数与 `/cdn` **同一档**（免登录，不套会话判据）；**拿不到就是 `null`**（不是 0）。见 §5.8 |
| `/api/library/campus` `/api/library/areas` `/api/library/seats` `/api/library/my` `/api/library/action` | `LibrarySource` | 图书馆座位 | P0 | **部分沿用 + 补**：`/campus` `/areas` `/seats` `/my` 形状可沿用（逐字段映射见 `CampusLibraryApi` 的 KDoc 表）；**写端点 `/api/library/action` 已落地**（§5.3.1），`canBook=true` 时屏上那些按钮才画 |
| `/api/card/balance` `/api/card/transactions` | `CampusCardSource` | 校园卡 | P0 | 沿用（`amount ← signed`、`description ← channel`、`cardType ← cardName`；⚠️ 挂失口径 `lostflag` vs `barflag` 要在本文档里明确选一个） |
| `/api/venue/products` `/api/venue/slots` `/api/venue/orders` `/api/venue/status` | `VenueSource` | 体育场馆 | P0 | **部分沿用 + 补**：`slots` 的 `surplus` **不要用旧字段**（旧口径在 `all == used` 时给 0，而两端一致的读法是 `status == 1` ⇒ 可订） |
| `/api/venue/captcha` `/api/venue/book` `/api/venue/cancel` `/api/venue/pay` | `VenueSource` | 体育场馆（写 + 支付那一跳） | **P1（写路径）· 已落地 2026-10-12 · `pay` 2026-10-15（D11b）** | 无旧形状：serve 侧新开的写路径（§5.3.2 逐字段；落地提交 `45a0e50`）＋支付那一跳 `pay`（**302 不当信封**，§4 的第二条例外；逐字段见 §5.3.2 第 6 条） |
| `/api/dzpz/transcript` | `TranscriptSource`（`:data` 侧 `AppTranscriptSource` + `TranscriptApi`） | 电子成绩单 | **P0 · 已落地 2026-10-13** | **新形状**：只端七步里的**第一步**（建单表单）—— 出 PDF 那半条链 2026-10-22 起走 §5.12 的两段式 |
| `/api/attendance/terms` `/api/attendance/records` `/api/attendance/leave/{window,preview,records}` `POST /api/attendance/leave{,/withdraw,/cancel}` | `AttendanceSource`（`AppAttendanceSource` + `AttendanceApi` / `LeaveApi`） | 考勤（**流水 + 请假 / 销假**） | **P0 读 · 2026-10-13 已落地**；**请假那一族（三读 + 三写）于 2026-10-15 批③-b / D7 ② 落地** | **要改**：`startSection ← startTime`、`endSection ← endTime`、`term ← termString` 三处改名（`:core` 那两个 `Time` 字段其实是节次）；**一处姓名不投影**（考勤流水里的 `teacher`）—— 请假单里的 `courses[].teacherNames` 与审批人的 `name` 于 2026-10-15 按 §5.2 例外 ④ **真投影**（见下与 §7 的 12g）；附件上传没有端点，见 §5.5.1 |
| `/api/coupon/list` `/api/coupon/detail` `/api/coupon/activate` | `CouponSource`（`AppCouponSource` + `CouponApi`） | 加餐券（**读 + 领券**） | **P0 读 · 2026-10-13 已落地**；**领券（P1 写）于 2026-10-15 批③-b / D7 ③ 落地** | **新形状**（原来这一行是 P2 = Stage C 宿主能力）。金额单位是**分**；领券成功回 `{ok, message}`，上游拒收落 `502`（`:data` 那一层分不出业务拒绝，见 §5.5），会话失效落 `401` |
| `/api/dorm/rooms` `/api/dorm/reading` | `DormPowerSource`（`:data` 的 `DormPowerApi` 本身就是端口实现） | 宿舍电费 | **P0 · 已落地 2026-10-13** | **要改**：旧的 `GET /api/dorm/kwh?roomId=` 一个端点把列表与读数混在一处 ⇒ 拆成两个；`reading` 不带时间戳，见 §5.5 |
| `/api/lms/courses` `/api/lms/activities` `/api/lms/activity` | `LmsSource`（`:data` 侧 `AppLmsSource` + `LmsApi`） | 思源学堂 | **P0 · 已落地 2026-10-14** | **要改**：`instructors` / `liveInstructorNames`（**教师姓名**）按红线不投影；附件的三个下载/预览地址与回放、直播地址**不端**（要站点会话）；`type` 是 `LmsActivityType.value` 那个小写字符串，见 §5.6 |
| `/api/jiaocai/search` `/api/jiaocai/ssno` | `JiaocaiSource`（`AppJiaocaiSource` + `JiaocaiApi`） | 教材查询 | **P0 · 已落地 2026-10-14** | **新形状**：`search` 只收一个 `keyword`（这一屏没有院系/教师那种筛选控件）、**没有翻页**；`ssno` 是「列表里拿不到、进详情才有的全文库书目号」那一枪，见 §5.6 |
| `/api/jwapp/scores` `/api/jwapp/precise-scores` | `JwappScoreSource` + `JwxtScoreSupplement`（`:data` 侧 `AppJwappScoreSource` + `JwappApi` / `CjcxApi`） | 成绩 | **P0 · 已落地 2026-10-14** | **新形状**：**一族两个站点**（`scores` 打移动教务、`precise-scores` 打**教务** `xscjcx.do`），服务端**不合并**两份（合并口径在 `:core` 的 `JwappScoreViewModel`，有测试钉着），见 §5.6 |
| `/api/jwapp/score-detail` | `JwappScoreSource`（`:data` 侧 `AppJwappScoreSource` + `JwappApi`） | 成绩（**分项**） | **P0 · 已落地 2026-10-14** | **新形状**：`?courseId=` 必填（取自 `scores` 那一行的 `id`）；「这门课没有分项」**如实回 `detail:null`**（不是 502/404）—— ⚠️ 这一条在 §5.6 里当年被写成「刻意不端」，本批端了、那一行已改写，见 §5.8 |
| `/api/iclassface/records` | `IclassfaceSource`（`AppIclassfaceSource` + `IclassfaceApi`） | 快速考勤流水 | **P0 · 已落地 2026-10-14** | **要改**：`name` / `studentNo`（姓名与学号）按红线不投影（屏上本来也不画那两格）；`time` **保留上游原文、不转 ISO-8601**，见 §5.6 |
| `/api/pay/code` `/api/pay/vouchers` `/api/pay/vouchers/select` | `PaymentCodeSource`（`:data` 侧 `PaymentCodeApi`）—— **借校园卡站点会话** | 付款码（**拿码 + 勾选券**） | **P2 读 · 2026-10-14 已落地**；**勾选同步（写）于 2026-10-15 批③-b / D7 ④ 落地** | **要改**：旧那行写的 `/api/payment-code` 是建议路径、从未实现 ⇒ 现在写死成 `/api/pay/{code,vouchers,vouchers/select}`（与其余各族同一条段名规矩）；⚠️ **「支付 / 扣款」这一档客户端本来就没有**（人只显示码，钱是商户扫了那串号之后在学校那边扣的）⇒ 不是本轮砍的，**没东西可端**；勾选落盘不端（serve 这一层没有宿主磁盘），见 §5.7 |
| `/api/feedback/replies` `/api/feedback/public-qa` `/api/feedback/submit` | `FeedbackSource`（`:data` 侧 `AppFeedbackSource` + `FeedbackApi`） | 反馈（**读 + 提交**） | **P0 读 · 2026-10-14 已落地**；**提交（P1 写）于 2026-10-15 批③-b / D7 落地** | **新形状**：这一族**没有校园站点会话**（凭据是 `app_id` + `app_secret` 换租户令牌 ⇒ serve 端按**环境变量**在启动时注入，见 §5.7）⇒ 三条都只认令牌闸门；提交是**写**（真往多维表格里加一行），工单号由**调用方生成**，逐字段见 §5.7 |
| `/api/match/dining` | `CampusCardSource`（流水） + `:core` 的 `DiningHabit` / `MatchProfile` | 匹配交友（消费统计） | **P0 · 已落地 2026-10-14** | **新形状**：只端「常在几点吃饭 / 常去哪个食堂」那两维（不含金额 / 商户全称 / 明细）；⚠️ `/api/match/profile`（**学工档案**）**不端** —— `:data` 里没有 `hello` 取数（那条链在 `:app`，桌面端也如实没有），见 §5.7 |
| `POST /api/webvpn/convert` | `WebVpnCodec`（`:data` 侧 `WebVpnUtil`） | WebVPN 转换器 | **P0 · 已落地 2026-10-14** | **新形状**：纯转换（两个方向、无会话、不碰上游、`400` 只有参数那一档）。**为什么不做成浏览器本地**：HTTP + 非 localhost ⇒ 非安全上下文 ⇒ `crypto.subtle` 被禁，见 §5.7 |
| `/api/v1/personal/me/user` | —— | （身份） | P2 | ⚠️ 待确认：这条出现在 `:core` 现有代码里，实现时先查清它是谁的投影 |
| `/api/accounts` | `:core` 的 `CampusAccountsApi`（serve 支）读 `:data` 的 `AccountStore` | 账号管理（多账号，D9） | **P2 · 已落地 2026-10-15**（批② 工单 S7a） | **新形状**：只读一条。`id` 是**打码后**的学号（`2023****0001`，既当显示名也当句柄），完整学号/姓名/密码/指纹/公钥一律**不投影**（红线 §3.4）；`readOnly:true` 是能力开关（切换/新增/删除/改密这一批**没有**端点，如实降级）；它**不打上游** —— 读的是本机账号表与 serve 自己的登录态，见 §5.10 |
| `/api/agent/{config,models,sessions*,memory*,chat,stop,status}` | `:core` 的 `AgentRunner` / `AgentTools` / `AgentPrompt` 等 + `:data` 的 `AgentConfigStore` / `AgentSessionStore` / `SseAgentTransport` / `AgentModelFetcher` / `AgentTitleGen`（循环与落盘都在 **serve** 这一侧） | 屁岱（Agent） | **P2 · 已落地 2026-10-15**（批⑤：工单 **S-a** 端点 → **S-b** `:core` 三处小改 → **S-c** 白名单工具层 → **S-d** `:web` 接线，**四件全部落地**） | **新形状**：十四端点（含一轮对话）；`POST /api/agent/chat` 成功时是 **SSE**（`text/event-stream`），开门阶段的失败仍是信封。⚠️ **key 只在 serve 侧**（浏览器一个字都不拿到，`keyConfigured` 是唯一投影）；**白名单工具层（S-c）已落地** —— `toolAllowlist` 现在是那九件（仍是从真下发的工具定义里读出来的）；**`:web` 接线（S-d）也已落地** —— 浏览器里那一格是 `:core` 的 `AgentScreen` 本身（`WebAgentSession` + `@JsFun` fetch/SSE 桥），它不再在路由差集里（5 → 4），逐条见 §5.11 |


> **状态收口（2026-10-13 核账，纯文档轮次）**：`:server` 里**已实现**的端点族 =
> `status` · `session{,/login,/logout,/mfa}` · `calendar/school` · `info/{yellowpage,faculty}` ·
> `fitness/{years,score}` · `notification/{sources,list}` · `inbox` · `emptyroom/{cdn,rooms}` ·
> `jwxt/{terms,term,term-start,schedule,grades,school-courses,evaluations,evaluations/status}` ·
> `library/{campus,areas,seats,my,action}` · `venue/{products,slots,orders,status,captcha,book,cancel,pay}` ·
> `card/{balance,transactions}` ·
> **只读四域（2026-10-13 收尾轮新增）**：`dzpz/transcript` ·
> `attendance/{terms,records}` · `coupon/{list,detail}` · `dorm/{rooms,reading}` ——
> 与本文档 §5.1–§5.3.2 与 §5.5 / §5.6 逐条对得上（核法：`:server` 源码里那些
> `*_SEGMENT` 常量与 `get()`/`post()` 注册点逐个列过一遍）。
> **第二批只读域（2026-10-14 新增）**：`lms/{courses,activities,activity}`、`jiaocai/{search,ssno}`、
> `jwapp/{scores,precise-scores}`、`iclassface/records` —— 逐字段与「不端什么」见 §5.6；
> 会话侧把 `lms` / `jwapp` / `iclassface` / `jiaocai` 四个站点补进了登录预热清单
> （与 `:desktop` 的 `DesktopAuth.SESSION_SITE_KEYS` 同一份清单）。
> **第三批（2026-10-14 新增）**：`pay/{code,vouchers}`、`feedback/{replies,public-qa}`、`match/dining`、
> `webvpn/convert` —— 逐字段与「不端什么」见 §5.7；会话侧**一个新站点都没注册**（付款码与消费统计
> 借的是**校园卡**那条会话；反馈与 WebVPN 两族没有站点、只认令牌闸门）。
> **第四批（2026-10-14 新增）**：`jwxt/{changes,exams,textbooks}`、`jwapp/score-detail`、
> `emptyroom/seat-count` —— 逐字段与「不端什么」见 §5.8；会话侧**一个新站点都没注册**（三条打教务、
> 一条打移动教务，两个站点本就就在清单里；座位数那一条读的是免登录的 CDN 档）。
> **批② 工单 S7a（2026-10-15 新增）**：`accounts`（多账号页那一族的**只读**一条）—— 逐字段与
> 「不端什么」见 §5.10；它**不打任何上游**（读的是本机账号表 `xjtu_accounts` 与 serve 自己的登录态）
> ⇒ 会话侧一个新站点都没注册。
> **批③-a（2026-10-15 新增）**：`venue/pay` —— 场馆「去支付」那一跳（D11b）。它**不打上游、不发起支付**：
> 只把浏览器 302 送到 80 端口那台站点上的**支付页地址**（`:data` 的 `VenueApi.paymentUrl` 拼的）。
> ⚠️ **那一页要不要再登录、能不能直接付，本端不担保** —— 浏览器这一侧没有场馆站的会话，判据与逐条依据
> 见 §5.3.2 第 6 条。它是本文档里**唯一**一条不当信封答的端点（§4 的第二条例外）。
> **批③-b / D7（2026-10-15 新增）**：D7 写的「剩下的写路径」逐条落地：
> **①反馈提交**（`POST /api/feedback/submit`，§5.7）· **②考勤请假 / 销假**（§5.5.1，三读 + 三写）·
> **③领券**（`POST /api/coupon/activate`，§5.5）· **④加餐券勾选同步**（`POST /api/pay/vouchers/select`，§5.7）——
> 每一族的消费方都跟着翻了（`canSubmit` / `canActivate` / `canSelectVouchers` 从 `false` 回到默认的
> `true`，`AttendanceCapabilities.leave` 从 `false` 翻 `true`）。⚠️ **D7 ⑤（成绩单 PDF 落盘）本轮
> 没有端**：`:data` 侧确实有 PDF 能力（`generatePreviewPdf` / `downloadPdf`），但第 2~7 步是**真的
> 往学校提交一份申请**（签章 + 审批）、第 2 步的返回值里有学号、而且「把字节落成文件」在契约的
> 信封形状里没有位置（§4 只有两条例外）—— 结论与最小可行方案写在 §5.5 的成绩单那一段末尾。
> ⚠️ **D7 ④ 的结论是「做不到」那一档**：付款码的「支付 / 扣款」**上游客户端本来就没有**（`grep`
> `:data` 的 `PaymentCodeApi` 可证）⇒ 没东西可端；这一族唯一的写是加餐券勾选同步
> （`POST /api/pay/vouchers/select`），本轮端了它、并把「为什么没有支付」写进 §5.7。
> **仍未实现**的只剩一行 + 一处如实缺口：`/api/v1/personal/me/user`（P2：来源仍待确认）、
> `/api/match/profile`（P2：**学工档案**在 `:data` 里没有源 —— `hello` 那条链只在 `:app`，桌面端也
> 如实没有 ⇒ 本轮不端，判据与 `DesktopMatch.kt` 的 TODO 见 §5.7）。
> （`/api/payment-code` 那一行已于 2026-10-14 从「未实现」里出来 —— 拿码 + 可用券两发落地，路径同时
> 写死成 `/api/pay/{code,vouchers}`，见 §5.7；**支付 / 扣款**那个动作客户端本来就没有，**勾选同步**仍未端。）
> ⚠️ **表里那个 `P0`/`P1`/`P2` 是优先级，不是状态** —— `P1` 那两行（写路径）都已落地（见各自那一格）。
> **批⑤（2026-10-15 新增）**：`/api/agent/*` —— 屁岱（Agent）在浏览器上的那一族（D10；**S-a** 端点 → **S-b**
> `:core` 三处小改 → **S-c** 白名单工具层 → **S-d** `:web` 接线，**四件全部落地**）。
> 十四条端点 + **SSE**（`POST /api/agent/chat` 成功时是 `text/event-stream`，不是信封；开门阶段的失败仍是
> 信封，见 §5.11）—— 逐字段、十一类事件、红线、白名单那九件、`:web` 那一屏接上之后**还剩什么没做**
>（逐条：campus-api 形态下这一族没有端点、`suspend` 测试函数在 wasmJs 上不支持 ⇒ 异步那一支探针验不了）
> 都写在 **§5.11**。绝不把「端出来了」当成「这一端真用得上」。
>
> **C-2 端点批（2026-10-20 新增）**：`attendance/{stats,streams}` · `POST jwxt/evaluations/submit` ·
> `POST dzpz/transcript/{submit,preview}` + `GET dzpz/transcript/download?ticket=` · `lms/{due,course-match}`
> —— 逐条见 **§5.12**（含契约第三条例外 `application/pdf`）。
>
> **下载那一族（2026-10-20 落地实现 · 2026-10-21 补测试与契约）**：`downloads{,/progress,/{id}}` —— 见 **§5.13**
> （含本批顺手修的三处：路由挂成 `/api/api/downloads`、回裸对象、删下载中的静默空操作）。
>
> **教材全文库（2026-10-21 新增）**：`jiaocai1/{classify,search,book,page,cover,shelf…}` —— 见 **§5.14**
> （含契约第四条例外 `image/jpeg`，会话借 `jiaocai` 站点，书架落在 serve 侧）。

### 5.1 `/api/session*`（P0「新」那一行的实测形状，2026-10-11 落地）

会话本体在 `:server` 进程里（`ServeSession`：`:data` 的 `SessionManager` + 落盘凭据），
浏览器**不带任何凭据**地登进来。四个端点全部挂在令牌闸门**里面**（`/api/status` 的免令牌是唯一例外，见 §3.4）。

| 端点 | 请求 | 响应 `data` |
|---|---|---|
| `GET /api/session` | —— | `{ "authenticated": true\|false }`，并带 `Set-Cookie`（见下） |
| `POST /api/session/login` | `{"username":"…","password":"…"}` | `{ "authenticated": true }` |
| `POST /api/session/logout` | ——（不读 body，`{}` 也行） | `{ "authenticated": false }` |
| `GET /api/session/mfa` | ——（轮询） | `{ "pending": …, "siteName": …, "rejections": …, "attemptsLeft": … }` |
| `POST /api/session/mfa` | `{"code":"…"}` 或 `{"cancel":true}` | 同上（**动作之后那一刻**的快照） |

四条口径（实现见 `server/src/main/kotlin/com/xjtu/toolbox/server/SessionRoutes.kt` / `ServeSession.kt`）：

1. **令牌换 cookie 只在 `GET /api/session` 发**：响应带
   `Set-Cookie: serve_token=<令牌>; Path=/; HttpOnly; SameSite=Lax`（**HTTPS 形态时末尾多一个 `; Secure`**
   —— D11 批④：`AccessToken.setCookieHeader(token, secure)`，形态由 `ServeConfig.tls` 定）。
   浏览器先用 `Authorization: Bearer` 打这一枪，之后**只带 cookie**（闸门两种形态都认，名字固定 `serve_token`）。
   `HttpOnly` 是重点：页面脚本读不到令牌。其余三个端点**不发**这行 —— 客户端启动时必然要问一次
   「有没有会话」，cookie 顺手就拿到了。
2. **`authenticated` 的含义是「登录跑完了」**（或冷启动从落盘凭据静默恢复过），**不是**
   「内核里有凭据」：登录挂在短信二验上时它仍是 `false`（那一发随时可能失败），`/api/status` 也照此报。
   恢复**不联网验证**（第一次真取数时站点会话自己探活）—— 打开页面这个最该快的动作不该等一次网络。
3. **MFA 是轮询**（不是 SSE）：`GET` 报挂起的询问，`POST` 交验证码或取消。
   - 没有挂起时 `{"pending":false}`（其余三个字段是 `null`，不是字段缺失）；有挂起时 `pending:true` +
     `siteName`（站点显示名，如「图书馆」）+ `rejections`（服务端拒绝了几次）+ `attemptsLeft`
     （= `3 - rejections`；3 是 `:data` 的 `SessionManager.MFA_MAX_ATTEMPTS`）。
   - `POST` 是**动作**不是结果：验证码交给内核那条登录流程去校验，结论从下一次轮询（`rejections` 涨没涨）
     或那一发登录请求的响应读；取消之后那一发登录以 4xx 收尾（这就是 `cancel` 的语义）。
   - 拿手机号是一个**副作用**：轮询到 `pending:true` 那一刻 `:server` 会调 `MFAContext.getPhoneNumber()`
     —— 不只是为了显示，`verifyCode` 要拿它设下的 `gid` 才能校验验证码（两个窗口端在弹窗里做的是同一件事）。
4. **`POST /api/session/login` 同时只允许一个流程**：第二发并发请求拿 `409`（中文短句），
   不排队等一个可能 150 秒的 MFA（并存的两个登录会互相 `reconfigureForAccount`，把命名空间搅成半新半旧）。
   失败码：`401` = 凭据/验证被拒（重发同样的请求没有意义）· `502` = 上游或网络的故障（稍后重试可能就成）·
   `400` = 请求体形状不对。登录成功后凭据落盘（与桌面端同一份存储，`0600`），登出时凭据 / cookie /
   站点快照**一起删**。

> **未投影的东西（TODO）**：① MFA 的绑定手机号（弹窗里那句「验证码已发送到 138\*\*\*\*0000」要它；
> 多一个字段 = 多一处口径，留到 `:web` 那个弹窗真开始画时一起加 —— 2026-10-11 弹窗**已经画了**，
> 在 `WebServeSessionScreen` 里，这句如实没写，正是差这个字段）；② `attemptsLeft` 依赖
> `SessionManager.MFA_MAX_ATTEMPTS`（那边是 `private`，`:server` 侧是同一数字的第二份写法，
> 改内核那个值要连它一起改）；③ `/api/session*` 的响应**只有**上面那些字段 —— 学号 / 姓名 /
> 手机号 / 账号类型一律**不投影**（与 `/api/status` 那条红线同一口径）；
> ④ **轮询间隔**：契约只写「轮询」不写间隔，消费方现在取 **2 秒**（`WebServeSessionScreen` 的
> `MFA_POLL_INTERVAL_MS` 与它的 TODO）—— 要写死就两处一起写死，并考虑退避。

> **TODO（每条一行，由搬它的提交补齐）**：请求参数的确切名字与默认值 · 响应字段的逐条形状 ·
> 与 `:app` 同一条上游样本的逐字段一致性证据（夹具）· 该端点的能力开关取值表。
> **已补齐的那一行**：`/api/session*` —— 逐条请求/响应形状、错误码、Set-Cookie 归属与两条 TODO
> 都写在 §5.1（由 `2026-10-11` 那次「serve 第二步」的提交补齐）。

### 5.2 P0 取数端点（2026-10-11 落地，`:server` 实现，测试 35 例钉住）

端点全部挂在令牌闸门内（`/api/status` 是唯一免令牌端点）。统一口径：信封 `{code,data,message}`；
`code == HTTP 状态码`（成功 `0` + 200）；时间一律 ISO-8601 **服务端系统时区**（跨界部署时区差异由部署方负责）；
错误码：`400` 参数形状 · `401` 无令牌/错了，或令牌对但**没登录**（「请先登录：POST /api/session/login」）·
`404` 未实现 · `502` 上游/网络故障（`FriendlyError` 中文短句）。学校那边会话过期（`SessionExpiredFailure`）不算上游故障，
`respondUpstreamFailure` 统一改回 `401`「请先登录」。
能力开关一律**如实**：`canBook` / `canCancel` / `canSubmit` / `availableSources` / `contactsAvailable` 出现在各自响应里。

**红线（五处刻意例外，均为公开数据或登录者本人数据，测试用编造夹具值钉住）**：
「响应不含学号/姓名/手机号」对登录者本人成立；例外的五处是 ① `fitness/score` 的姓名/学号（本人数据），
② `yellowpage`/`faculty` 的教师名与部门电话（公开通讯录），③ `emptyroom/rooms` 实时档的上课教师名，
④ **请假这一族的两个姓名**（2026-10-15 上一级拍板）：`/api/attendance/leave/preview` 的
`nextApprovers[].name` 与 `nodes[].approvers[].name`（**我自己的申请上的审批人姓名**，学校工作人员），
以及 `/api/attendance/leave/records` 的 `courses[].teacherNames`（**我自己的课的任课教师姓名**）。
⑤ **考勤流水那一格的 `teacher`**（**当堂上课的教师名**，2026-10-19 会议批准）：与 ③④ 同性质 ——
都是「**我自己的那条记录**」上的真人名；不加它，流水那一栏就只剩一节节次。
④ 的理由与 ③ 同性质；不加 ④ 的代价是屏上那个审批人下拉框会退化成一行 `userId`
（`:core` 写的是 `it.name.ifBlank { it.userId }` ⇒ 留空不丢功能，但少了一个人名）。
⚠️ **仍然一律不端**：学生本人姓名/学号/手机号、通讯录（除 ② 已列的公开项）。
拍板与落地记录见 §7 的 12g。

| 端点 | 请求 | 响应 `data`（逐字段） | 备注 |
|---|---|---|---|
| `GET /api/calendar/school` | —— | `{terms:[{id,startDate,endDate,termName,yearName,totalWeeks,workDays,events:[{id,startDate,endDate,name,remark,days,colorHex}]}]}` | 开学日起序；`:web` 消费方 2026-10-11 落地（§5.4） |
| `GET /api/info/yellowpage` | —— | `{updateTime,categories:[{id,name}],departments:[{id,categoryId,name,phone}]}` | 已滤 `status!=1`、不投影 `sort` |
| `GET /api/info/faculty` | `?name=&dept=&page=&size=` | `{contactsAvailable:false,total,totalPage,pageIndex,members:[…]}` | 不解析主页、不传 `contacts=1`（serve 只读）；`contactsAvailable:false` 固定 |
| `GET /api/fitness/years` | —— | `{years:[{yearNum,name,checked}]}` | `FitnessSource` |
| `GET /api/fitness/score` | `?year=` | `{studentNumber,studentName,totalScore,totalGrade,reportType,reportStatus,sex,grade,items:[…]}` | **本人数据**（例外①）：姓名/学号可投影，消费方 2026-10-11 带上 |
| `GET /api/notification/list` | `?page=&size=&source=&all=1` | `{page,size,total,items:[…],skipped:[…],hasMore}` | `size`=本次响应条数（`NoticeSource` 无页大小概念，非服务端分页窗） | 
| `GET /api/notification/sources` | —— | `{total,sources:[{code,displayName,category}]}` | 29 个源 |
| `GET /api/inbox` | —— | `{messages,todos:{分类:[…]},finished,readAt,seenTodos,ignored,off,schoolFetchedAt,bubbled}` | `:data` 的 `InboxData` 投影（读侧；`markRead` 等写路径属 Stage B） |
| `GET /api/emptyroom/cdn` | `?campus=&date=&from=&to=` | `{availableSources:[{key,name}],source,campus,buildings,date,noData,note,rooms:[…]}` | CDN 档（`noData:true` + `note` = 那天没数据，不是错误） |
| `GET /api/emptyroom/rooms` | `?campus=&date=&source=direct\|live` | 直查：同 CDN 档；实时：`{availableSources,…,fetchedAt,buildings,rooms:[{name,building,status,people,seats,course?,teacher?}]}` | `availableSources`：未登录 `[cdn]`；登录后 `[live,cdn,direct]` |

**TODO（拍板过，未做）**：`emptyroom/rooms` 的 `from/to`（节次过滤）——`:data` `EmptyRoomDirectQuery` 只给全天，留待上游加维度；
`faculty` 的 `?filters=1`（筛选项表四张，`:data` 能解析但契约未定）与 `fitness/score` 的 `?year=all`（全学期聚合）同理；
时间戳一律服务端时区。

### 5.3 P0 需登录六域（2026-10-11 落地）

| 端点 | 请求 | 响应 `data`（逐字段，节选） | 备注 |
|---|---|---|---|
| `GET /api/jwxt/terms` | —— | `[{name,startDate,endDate,current?}]` | 学期名兜底 = `DM` 字段 |
| `GET /api/jwxt/term` | —— | 当前学期对象（形状同上） | `ScheduleSource` 一族 |
| `GET /api/jwxt/term-start` | `?term=`（省略 = 当前学期） | `{term,startDate,totalWeeks}` | 2026-10-11 落地：`startDate` = 上游 `XQKSRQ` 的**日期那一段**（第 1 周周一）、`totalWeeks` = `ZZC`（只在 `1..30` 里算数，否则 `0`）。`:data` 侧 `JwxtScheduleApi.termStart()`。**没有**「今天是第几周」这种服务端算出来的字段（那是消费方拿 `startDate` 自己算的）；`?term=` 形状不对 ⇒ `400` |
| `GET /api/jwxt/schedule` | `?term=`（省略 = 当前学期）+ 可选 `?merged=1` | 不带 `merged`：`{term,count,rows:[…]}`；行的键名 = 上游列名，只投影 `KCM`/`SKJS`/`JASMC`/`SKXQ`/`KSJC`/`JSJC`/`ZCMC`/`JXBID` 八列，值一律是**字符串**（上游给数字的三格也按文本给）、缺键是**空串**。带 `merged=1`：`{term,count,courses,changes}`（见 §5.9） | 2026-10-11 落地：`:data` 侧 `JwxtScheduleApi.rows()`（`wdkb` 的 `xskcb.do`），**这一档至今一字未改**。周次看 `ZCMC` 文本；`?term=` 形状不对 ⇒ `400`。⚠️ **调停补课没有合进这些行**，而且这仍然是**有意为之、不是缺口**（2026-10-13 核）：`:data` 的 `JwxtScheduleApi` 两条都在 —— `rows()` = 排课原样（本档端出去的就是它，契约面逐字冻结），`schedule()` = **已合并**调停补课（合并那一层由 `7a5590da` 落地，Android 与桌面端吃的就是它）；两条路「别混用」写在 `rows` 的 KDoc 里。 **那一档已于 2026-10-14 拍板并落地（§5.9）**：`?merged=1` 端 `schedule()` 那一份，本档（不带 `merged`）仍一字未改 —— 要合并后的课表请用 §5.9 |
| `GET /api/jwxt/grades` | `?term=&all=1` | `{page,size,total,grades:[…]}` | 默认全量；`:data` `ScoreReportSource` |
| `GET /api/jwxt/school-courses` | `?course=&code=&teacher=&campus=&weekday=&from=&to=&page=&size=` | 见下节 | **要改**（契约§5）：补人数/学时、`YPSJDD`、开课单位、公选筛选（`:data` `AppSchoolCourseSource` 已能取的投影，取不到标 TODO）。消费方 2026-10-11 把 `supportsDepartmentFilter` / `supportsElectiveFilter` 读进去了（§5.4 的两个「不画」） |
| `GET /api/jwxt/evaluations` | `?terms=&type=&finished=` | `{canSubmit:false,items:[…]}` | `canSubmit` 必须出现、如实 `false`（本实现只看不认不投） |
| `GET /api/jwxt/evaluations/status` | —— | `{canSubmit:false}` | 提交/撤销（P1）、研究生 gste/gmis 路（P1） |
| `GET /api/library/campus` | —— | `{current:{code,name}|null, campuses:[{code,name}], queryableFloors:[…]|null, canBook:true, hasSeatPlan:true}` | `current:null`=认不出不猜；`canBook:true` 如实（:data 写路径真） |
| `GET /api/library/areas` | `?campus=&floor=` | `{floor,areaCount,areas:[{code,name,floor,available,total}],totals:{…}}` | `available/total:null`=scount 无此区域（不拿 0 冒充「满了」） |
| `GET /api/library/seats` | `?area=&time=` | `{area,total,available,seats:[{seatId,available}]}` | 排序与 `:app` 同；**`time` 收了但上游无时间维**（TODO） |
| `GET /api/library/my` | —— | `{my:{seatId,area,statusText,actionUrls}|null}` | `my:null`=页面明确没预约（§4 null≠空对象）；`actionUrls` = 页面上那几个动作 **→ 上游地址**（label→url，见 §5.3.1 —— 客户端只当不透明句柄） |
| `POST /api/library/action` | body `{action,…}`（动作表见 §5.3.1） | `{ok:true,message}` | 约座/换座/签到/中途离开/中途返回/退座/切校区···一个端点一条动作轴；失败码（400/401/409/502）与例子见 §5.3.1 |
| `GET /api/card/balance` | —— | `{balance,pendingAmount,lostFlag,frozenFlag,expireDate,cardType,department}` | 红线：`account/name/studentNo` **不投影**；挂失口径选 **`barflag`**（`:data`/`:app` 读法，不选 campus-api 的 `lostflag`） |
| `GET /api/card/transactions` | `?from=&to=`（必填 YYYY-MM-DD，反序 400）`&page=&size=` | `{page,size,total,transactions:[{time,merchant,amount,balance,type,description}]}` | `amount` 带符号（负=支出）、`description`←resume |
| `GET /api/venue/products` | —— | `{venues:[{id,name,address?,iconType?,advanceDay,advanceNum}]}` | 拉页到不足为止 |
| `GET /api/venue/slots` | `?serviceId=&date=`（必填 400） | `{serviceId,date,slots:[{areaDetailId,areaName,stockId,timeSlot,price,date,allCount,usingNum,surplus,serviceid,isAvailable}]}` | **`surplus` 读法 = `(all-used).coerceAtLeast(1)`、`all==used` 给 1 不给 0**（旧字段废弃） |
| `GET /api/venue/orders` | `?page=&size=` | `{page,size,total|null,orders:[{orderId,status,statusText,createdAt,price,venueName,canPay,canCancel,details:[…]}],hasMore}` | |
| `GET /api/venue/status` | —— | `{canBook:true, canCancel:true, browserLoginUrl}` | `canBook:true` 如实（写端点 `/captcha` `/book` 已落地，滑块由浏览器端宿主画，见 §5.3.2）；`canCancel:true` 不需要滑块 |
| `GET /api/venue/captcha` | `?serviceId=`（必填 400） | `{captchaId,backgroundImage,sliderImage,bgWidth,bgHeight,sliderWidth,sliderHeight}` | 写路径的第一步：把上游那张滑块图与它发的 `captchaId` **按值**端出来（§5.3.2 第 1 条） |
| `POST /api/venue/book` | body `{serviceId,selections:[{stockId,areaDetailId}],captchaId,sliderTrack}` | `{ok:true,orderId|null,price,message}` | 下单：轨迹（**字符串**原文）带回来交给 `:data`；失败码（400/401/409/502）见 §5.3.2 |
| `POST /api/venue/cancel` | body `{orderId}` | `{ok:true,message}` | 取消：一次普通 POST，不需要滑块 |

**TODO（拍板过，未做）**：`library/seats?time`（上游无时间维）、
`card` 挂失（P1 写）、`evaluations` 提交/撤销（P1）。
（`venue/book` `/cancel` 与 `canBook:true` 已于 2026-10-12 落地，见 §5.3.2。）
⚠️ 上面那三件事**仍未实现**（2026-10-13 核：`:server` 的 `CardRoutes` 只有 `balance`/`transactions`，`JwxtRoutes` 的评教只有只读那两发 —— 没有挂失与提交端点）。

### 5.3.1 library 写路径（2026-10-11 落地）：`POST /api/library/action`

一个端点、一条动作轴（`data.action`），不是七个路径：约座 / 换座 / 签到 / 中途离开 / 中途返回 / 退座 / 切校区
共用同一条会话、同一套失败码、同一份信封，拆开只会多七处要同步的形状。

**`actionUrls` 现在投影了**：`GET /api/library/my` 的 `my.actionUrls` = `` {label: 上游地址} ``
（例如 `{"取消预约":"http://rg.lib.xjtu.edu.cn:8086/my/?cancel=1&ri=88"}`）—— 客户端只把它当
**不透明句柄**（认得出自己刚拿到的那一条）；「要执行哪个动作」说 `action` 码，**地址由服务端自己从当前
预约页上取**，本契约不允许客户端指定「拿我的会话去打哪个地址」。

| 动作 | 中文 | 请求里的字段 | `:data` 的调用 |
|---|---|---|---|
| `seat` | 约座 | `seatId` + `area`（区域码）必填；`autoSwap` 可选（默认 `true` = 已有预约时自动改走换座） | `bookSeat(seatId, area, autoSwap)` |
| `change` | 换座 | `seatId` + `area` 必填 | `swapSeat(seatId, area)` |
| `signin` | 入馆签到 | ——（地址服务端从当前预约页取） | `executeAction(「入馆签到」的地址)` |
| `leave` | 中途离开 | 同 `signin` | `executeAction(「中途离开」的地址)` |
| `back` | 中途返回 | 同 `signin` | `executeAction(「中途返回」的地址)` |
| `cancel` | 退座（取消预约） | 同 `signin` | `executeAction(「取消预约」的地址)` |
| `switch_campus` | 切换账号校区 | `campus` 必填（`east`/`west`/`inno`） | `switchCampus(LibraryCampus.byId(campus))` |

请求例子（约座）：

```json
POST /api/library/action
{"action":"seat","seatId":"A01","area":"north2east","autoSwap":true}
```

签到/中途离开/中途返回/退座只发动作码（`{"action":"signin"}`）—— 地址不在请求里（见上）。

响应 `data`（成功）：`{ "ok": true, "message": "✓ 座位 A01 预约成功！" }` —— `ok` 恒为 `true`（失败走信封，
见下），`message` 是 `:data` 的 `BookResult.message` **原样转发**（已是中文短句）。失败时 `data` 为 `null`、
`code` 与 HTTP 码逐位相同（§4）。

错误码（§4 那一套的延伸）：

- **400** 未知的 `action` / 缺了这个动作要的字段 / `campus` 不认 —— 文案把合法取值列出来；
- **401** 未登录（与会话闸门同一个码，`message` 指路先登录）；
- **409** 上游**明确说这次动作不成**（座位已被他人预约、已有别的预约、当前预约上没有这个操作、不在开放
  时间…）—— 与「另一次登录在进行」同一个码、同一种意思（「现在不能」），区别只在谁在说不行；
  重发同样的请求没有意义，所以**不是** 502；会话失效的 `message`（「登录状态已失效…」）也在这档上，但
  会先被翻成 401（同一句话的语义就是要你去重新登录）；
- **502** 上游/网络的故障（稍后重试可能就成），或「我的预约」页认不出来、取不到按钮地址。

⚠️ 切校区是**改账号资料**的写（`rplace`）：浏览器端离开本页时会把校区**真切回去**（`restoreCampus` 走
同一个端点，`:core` 的 `CampusLibraryApi` 带了页面之外的作用域）—— 与 `:app` / 桌面端同一条约定。

### 5.3.2 venue 写路径（2026-10-12 落地）：`POST /api/venue/book` / `POST /api/venue/cancel`

场馆的下单比图书馆多一样东西：**一张滑块图**。这一节把「挑战怎么暴露」「两个端点逐字段」
「失败码」写死。

#### 1. 滑块挑战的暴露方式：**服务端按值端出，浏览器端自己画**（本轮拍板）

这两条路里选的是**后者**：

| 方案 | 为什么不选 |
|---|---|
| ① 服务端自己解（`:server` 用 `:data` 的 `VenueCaptchaSolver` 在进程内识别，浏览器不用画滑块） | 它能把「下单」做成一个原子调用，但**把滑块这一步变成了黑盒**：`:app` / 桌面都是宿主画的，serve 选它就意味着这条路在三种端上是三套不同的东西；而且它把一个「人机验证」变成服务端自动过，与各端已经存在的手动那条路也不一致 |
| ② **按值端出去**（`GET /api/venue/captcha` + `POST /api/venue/book` 带轨迹） | —— **选它**：与 Android/桌面**同一条协议**（轨迹的 JSON 原文一字不改地往上传），且浏览器里真的能画、能拖、能提交（`:web` 的 `WebSlideCaptchaHost`） |

为什么不能「浏览器直接用 `:data` 的求解器」：**`:data` 只有 jvm/android 两个目标**，
`SliderCaptchaView` 与 `VenueCaptchaSolver` 都依赖 okhttp/jsoup 那一档（wasm 上引用不到）⇒
浏览器里能用的只有 `:core` 的图片缝（skiko 解码）+ 自己写的拖动采集。

**生命周期**：本层**不发行自己的挑战号、也不常驻状态** —— `captchaId` 就是上游发的那个
（`CaptchaData.id` 原名改名，与 `orderId` 一样是**要原样带回来的句柄**），「用掉了没 / 过期没」
由上游判。所以没有需要清理的内存表。TODO（真站未验）：若实测发现上游还要求「服务端必须记住
图与 id 的对应关系」之类的事，再在这一层加一张一次性挑战表（内存 + TTL，写在文件头）。

#### 2. `GET /api/venue/captcha`

```json
GET /api/venue/captcha?serviceId=101
{"code":0,"data":{"captchaId":"cap-1",
  "backgroundImage":"data:image/jpeg;base64,…","sliderImage":"data:image/png;base64,…",
  "bgWidth":260,"bgHeight":160,"sliderWidth":50,"sliderHeight":50}}
```

七格就是全部（`captchaId ← CaptchaData.id`，其余原样转发）：两张图都是 data URI 原文，
服务端不重编码、不验尺寸。`?serviceId=` 缺或不是数字 ⇒ **400**。

#### 3. `POST /api/venue/book`

```json
POST /api/venue/book
{"serviceId":101,
 "selections":[{"stockId":9101,"areaDetailId":5101}],
 "captchaId":"cap-1",
 "sliderTrack":"{\"bgImageWidth\":260,…}"}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `serviceId` | ✓ | 场馆号（`/api/venue/products` 的 `id`），正整数 |
| `selections[]` | ✓（至少一行） | 选中的场地明细：**只要两个数**（`stockId` + `areaDetailId`，来自 `/api/venue/slots`）。上游 `stockdetail` 那个信封（`:data` 的 `VenueApi.buildBookingParam`）只用到它们 |
| `captchaId` | ✓ | `/api/venue/captcha` 给的那个，原样带回 |
| `sliderTrack` | ✓ | 滑块轨迹的 JSON **原文**（`:core` 的 `SliderResult.toJson()`，与 Android/桌面同一协议：260 坐标系 + down/move/up + 两个 ISO 时刻）。**当字符串传**，服务端不解析它 |

响应 `data`（成功）：`{ "ok": true, "orderId": "VT20261013001", "price": 20.0, "message": "预订成功" }`
—— `ok` 恒为 `true`（失败走信封），`orderId` 上游没给就是 `null`（§4 不编），`message` / `price`
是 `:data` 的 `BookingResult` 原样（即上游那一句）。服务端不再算一份 `param`（那是 `:data` 的事，
客户端也拼不了），也不走那条「先拿 `PendingOrder` 再提交」的两次请求（`:data` 的 `prepareOrder` 是纯计算）。

#### 4. `POST /api/venue/cancel`

```json
POST /api/venue/cancel
{"orderId":"VT20261013001"}
```

响应 `data`：`{ "ok": true, "message": "取消成功" }` —— 与 `/api/library/action` 同一套（`ok` 恒 `true`，
`message` 是 `:data` 的 `OrderActionResult.message` 原样）。取消不需要滑块（一次普通 POST）。

#### 5. 错误码（两个写端点共用）

- **400** 参数形状不对：请求体不是 JSON / `serviceId` 不是正整数 / `selections` 空或某行少了
  `stockId`/`areaDetailId` / 缺 `captchaId` / 缺 `sliderTrack` / 缺 `orderId`。文案一律说出缺什么；
- **401** 未登录（与会话闸门同一个码，`message` 指路先登录）——包括取数/提交时发现**会话已失效**那一档；
- **409** 上游**明确说这次动作不成**：验证码有误、场地已被抢、订单不存在……都是业务结果不是故障，
  文案是 `:data` 的结果对象里那句原样中文（与图书馆那张同一个码、同一种意思：「现在不能」）；
- **502** 上游/网络的故障（稍后重试可能就成）：挑战取不回来、提交那一枪没打通。

#### 6. `GET /api/venue/pay` —— 支付那一跳（**D11b**，2026-10-15 落地）

屏上那个「去支付」要的是**一个能在浏览器里打开的地址**，而支付页在 80 端口那台站点的另一套部署上
（`VenueApi.paymentUrl` 拼的 `/pay/show.html?id=…`）。那个地址只有 `:data` 知道，浏览器也没别的办法拿到它
（页面不能替别的域名写 cookie，`:core` 也不该复制一份站点知识）⇒ **serve 把这一跳代过去**。

```
GET /api/venue/pay?orderId=VT20261013001
HTTP/1.1 302 Found
Location: http://202.117.17.144/pay/show.html?id=VT20261013001
```

| 项 | 形状 |
|---|---|
| 请求 | `?orderId=` 必填，**原样带回**（`/api/venue/orders` 的 `orderId`；下单成功那个也用同一个） |
| 成功 | **302** + `Location`，**没有信封**（§4 的第二条例外）。`Location` 逐字等于 `:data` 的 `VenueApi.paymentUrl(orderId)` |
| `401` | 没令牌（闸门）／令牌对但**没登录**（与场馆这一族其余端点同一道门）—— 文案都指路先登录 |
| `400` | `orderId` 缺或空白，文案说出缺什么 |

消费方（`:web`）怎么用：`CampusVenueApi.paymentUrl(orderId)` 在 serve 形态**给的就是这条端点的同源地址**
（`/api/venue/pay?orderId=…`，`baseUrl` 留空即同源），宿主（`WebVenueHost` 的 `venuePayTarget`）把它交给
浏览器新标签打开。Android / 桌面那一侧一字未改（它们拿到的是 `:data` 拼的绝对地址）。

##### ⚠️ 这条链在浏览器里**能不能真的走完**？（本批必须回答的那个问题，分两截答）

**① 302 这一跳：一定走完。** 它是顶层跳转（新标签直接打开这条端点），浏览器一律跟随；没有任何一手会拦它
（`Location` 指向的那一页是明文 http 也一样 —— 顶层导航不属于混合内容拦截的范围）。

**② 落在那一页上能不能付款：本端不担保** —— 代码侧的判据就是「担保不了」：**支付页要的是它自己那个站点在
浏览器这一侧的会话，而浏览器里没有**。三条依据：

- **依据一（仓内原文）**：`data/…/auth/VenueLogin.kt` 的 `PAY_BASE_URL` KDoc 写着支付页「要求浏览器自身的
  会话，不能把 App 内的 cookie 拼进 URL」—— 这是 Android 时代就有的口径，不是本批新加的顾虑；
- **依据二（为什么 Android 能、浏览器不能）**：`:app` 的内置浏览器是**进程内**的 WebView，
  `BrowserScreen.syncCookiesToWebView` 把 App 的 cookie 罐（含场馆站的 `SESSION`）灌进 `CookieManager`
  ⇒ 它那两跳（登录页 → `onPageFinished` 判 `!isAuthHop` → `loadUrl(thenUrl)`）**都带着会话**。
  浏览器里没有这一手：页面不能替别的域名写 cookie，serve 的会话也只在 serve 进程的 cookie 罐里，不在页面上；
- **依据三（「先登录一次」也不保证够）**：cookie 还按 **`Path`** 存。8080 那套若把 `SESSION` 限在业务
  路径下，浏览器即便在 8080 登录过，`/pay/…` 那一页也未必带得上（夹具假设的是 `Path=/`，真站点未验）。

⇒ 所以本端**只能说**「点下去会到支付页那个地址；若它要求登录，按那一页自己的流程走；实在不行改用 App /
桌面端支付」——页面上就是这么写的（`WebVenueHost` 的 `PAY_HOP_NOTE`，其中一句逐字是
**跳过去后若要求登录，请用 App / 桌面端支付**）。⚠️ **不许**把它读成／写成「本端已支持真支付」：
本端既没有「代扣」这个动作，也不掌握那一页要的会话。

**这一条没有在线验证**：不许真发起支付，也没去探测那台支付站；夹具刻意不扮演它（`VenueFakeUpstream`
里没有 `/pay/…` 路由，打过去 404 —— 那一页是浏览器那边的事）。要真做到「浏览器里就能付」，得让 serve
**代理**那一页（带着 serve 自己的会话取回 HTML、同源发出去，连它的表单与 JS 一起转）—— 那是另一个量级的
新暴露面（等于把一条写/支付路径开给浏览器），本批没做。

**三条如实说明**（写在这里，免得被读成「serve 能替你付」）：

1. **这一跳不发起支付**：本层只回一个 302，扣款/确认在站点那一页上由人点 —— 与 `:app` 的内置浏览器
   做的是同一件事（客户端本来就没有「代扣」这个动作，不是本批砍的）；
2. **serve 的会话带不过去**：那条会话是场馆站 8080 上的 `SESSION` cookie；cookie 按**域名**存（端口不
   参与作用域，所以 8080 发的 cookie 对 80 也是同一个），而浏览器不允许一个页面给别的域名写 cookie
   ⇒ **浏览器这一侧的场馆站会话只能由站点自己那次登录给**。这与 `:app` 同一条路：登录过一次，之后两个
   端口带的是同一个 cookie；
3. **因此第一次点很可能先落在该站点自己的登录页**（由它自己的跳转去要，统一认证过完一般回到这一页）；

**为什么不是「serve 把会话塞给浏览器」**：那是跨域写 cookie，浏览器一律拒绝。`/api/venue/status` 里那个
`browserLoginUrl` 也帮不上：浏览器里「先过统一认证、再落支付页」是**两跳**，而跨源跳转看不见、也接不住
（`:app` 的 WebView 能串是因为它有 `onPageFinished` + 那手 cookie 注入）⇒ `browserLoginUrl` 在 serve 形态**仍是空串**，
理由就写在 `CampusVenueApi` 那一格的 KDoc 与 `WebVenueHost` 的文件头里。

> **收口记录**：本节原有一段「⚠️ 已知缺口（如实记下，不在本轮）」——写的是「屏上那个「去支付」**点了不会有动作**：
> `paymentUrl` 与 `browserLoginUrl` **都是空串**」。它由上面这条端点于 2026-10-15（批③-a / D11b）收口：
> `paymentUrl` 不再空（serve 的代跳地址），`browserLoginUrl` 仍是空串 + 上一段那两条理由。
> 那一格审记（`docs/audit-response.md` #30 / `docs/desktop-port-plan.md` §13.2 第 3 条）本批已更新成
> 「只剩落点没在线验证过」。**仍然没做的一件事**：上面那第 ② 截只到「代码侧判据 + 依据一/二/三」，
> 没有在线走过一次
> （不许打支付站）⇒ 「第一次点到底落在哪一页」以用户照单点出来的现象为准；点位清单
> `docs/serve-visual-checklist.md` ⑨ 那一行就是照这个口径写的。

### 5.4 `:web` 消费方（2026-10-11 落地，`ApiMode` 那一次提交）

消费方（`core/net` 的 17 个 `Campus*Api` + `:web` 外壳）按**本文件**接线，不认 campus-api 的字段名（§7.2）。
这条只记**消费方自己的行为**，不改任何端点的形状 —— 改形状仍然要走 §7 的三处同改。

- **模式开关**：`?backend=serve` 钉死 serve 模式（书签/排障用）；否则探一次 `GET /api/status` —— serve 的那份是
  **裸对象且不含身份**（§3.4），campus-api 的同名端点带 `username`，这是两条路在同一个路径上唯一稳定的差别。
  探不出结果按 campus-api 处理（默认形态一字不改）。实现：`web/…/ServeSession.kt` 的 `detectApiMode`。
- **登录**：`POST /api/session/login`；挂起时**并发轮询** `GET /api/session/mfa` —— 间隔由消费方定 **2 秒**
  （`MFA_POLL_INTERVAL_MS`，TODO 见 §5.1 的 ④）。MFA 弹窗显示 `siteName` / `rejections` / `attemptsLeft`
  （`WebServeSessionScreen`）；绑定手机号不投影（§5.1 TODO ①），弹窗如实不写「已发送到 138\*\*\*\*0000」。
- **访问令牌只在浏览器内存里**：不落 `localStorage`（XSS 拿到令牌就等于拿到这道门）；`GET /api/session`
  那一发顺手把令牌换成 `serve_token` cookie（§3.2），之后每次请求同时带 Bearer 与 cookie ——
  「Ktor 的 fetch 引擎会不会自动带 cookie」是引擎实现细节，不作为契约依赖。
- **消费方侧的「不画」**（都是「点了会失败的按钮一个都不画」这一条的实现，服务端别当成 bug）：
  1. ~~library~~（已翻转，2026-10-11）：写端点 `/api/library/action` 落地（§5.3.1）⇒ `CampusLibraryApi` 的
     `SERVE_WRITE_ENDPOINTS_READY=true`，`canBook` 随服务端如实报的 `canBook:true` 翻 true —— 座位格、
     「我的预约」按钮、校区切换全部真的能点；但 `hasSeatPlan` 仍报 `false`（§5 的 library 没有布局/底图
     端点 ⇒ 平面图那一档不画）。
  2. ~~venue~~（已翻转，2026-10-12）：写端点 `/api/venue/captcha` + `/book` + `/cancel` 落地（§5.3.2），
     滑块由 `:web` 的 `WebSlideCaptchaHost` 在浏览器里画 ⇒ `CampusVenueApi` 的 `SERVE_WRITE_ENDPOINTS_READY=true`，
     `canBook`/`canCancel` 翻 true（与 `/api/venue/status` 报的两个开关一致）。那一批留下的「去支付」缺口
     已于 2026-10-15（批③-a / D11b）收口：`paymentUrl` 在 serve 形态给的是 `GET /api/venue/pay`
     （302 到 80 端口那台站上的支付页地址；⚠️ **落成能付款的一页不担保**，依据见 §5.3.2 第 6 条），
     宿主 `WebVenueHost` 只开**一个**标签、**优先支付页**；
     `browserLoginUrl` **仍是空串**（浏览器里两跳串不成一条链，理由见那一节）。这一端也不再「点了没动作」：
     连地址都拿不到时会在页顶如实写一句（`venuePayTarget` 有 3 例 wasm 测试钉住）；
  3. school-courses：响应报 `supportsDepartmentFilter` / `supportsElectiveFilter` 是 `true`，但没有返回那两张
     id 表的端点 ⇒ 消费方报 `false`（下拉出现了却没有选项，比不出现更糟）。
  两处的 TODO 都写在对应 `Campus*Api` 的 KDoc 里，端点落地的提交把它们一起翻成 true ——
  library 那一条已由 2026-10-11 的提交翻成 true，venue 那一条已由 2026-10-12 的提交翻成 true，
  school-courses 的那处照旧。
- **课表缺口已补齐**（2026-10-11）：`/api/jwxt/schedule` 与 `/api/jwxt/term-start` 两个端点落地，
  serve 模式的课表屏（含默认落地页）正常出数据 —— 消费侧是 `CampusScheduleApi` 的 `ApiMode.SERVE` 那一支
  （`CampusApi.schedule` / `.termStart`，`:web` 的 `ScheduleScreen` 把外壳探到的 mode 传下去）。
  留下的那一处**如实差异**：这些行是**排课原样**（`:data` 的 `JwxtScheduleApi.rows()`），调停补课没合进去
  （合并后的那一份是同一个类的 `schedule()`，`7a5590da` 落地，Android 与桌面端吃的就是它）——
  所以同一份课表在 serve 模式与 `:app` / 桌面端之间对调课的表现不同。**这是有意为之，不是缺口**（§5.3 那一格）。

**消费方现状收口（2026-10-13 核账）**：

- 上面那三处「不画」里：**library 已翻 true**（`4abd1ae` 那次提交）、**venue 已翻 true**（`45a0e50` 那次提交，
  `canBook`/`canCancel` 与服务端报的两个开关一致）、**school-courses 照旧**（缺那两张 id 表的端点）；
- 课表那两个端点落地的提交是 `0022296`（它同一次里改了本文档 §5.3 两行 + 这一节 + `:testkit` 夹具，
  也就是 §7 的「三处同改」）；
- 本节**只记消费方自己的行为**，端点形状的改动仍要走 §7；
- 本节**没有**覆盖 Stage C 那批宿主能力（加餐券 / 付款码）。付款码在 `:server` 侧仍是 P2 未实现（§5 表那一行），
  所以 `:web` 这边也还没有可谈的消费方行为（⚠️ 别读成「这两样没实现」：`:data` 侧的实现都在（
  `data/src/commonMain/kotlin/com/xjtu/toolbox/pay/PaymentCodeApi.kt`、`data/src/commonMain/kotlin/com/xjtu/toolbox/coupon/CouponApi.kt`），
  Android 与桌面都在用（`docs/desktop-port-plan.md` §12.2 / §12.3）；缺的只是 `:server` 把它们端出去）；
- **只读四域的消费方已落地（2026-10-13 接线）**：成绩单 / 考勤 / 加餐券 / 宿舍电费四屏在 `:web` 上
  真能打开了 —— 取数是 `:core/net` 的四个新 `Campus*Api`（`ApiMode.SERVE` 那一支，逐屏见 §5.5 末尾那张表），
  屏是 `:core` 的共享屏（宿舍电费那一屏是本端自己的读数页，理由同样在 §5.5）。每屏顶部都画一条「如实缺口」
  说明，拿不到的字段一个都不画。这一批同时把 §7 第 1 条那个「三处同改」的第三处（`:web`）补上了：
 上一次（七个端点落地那批）那一处是有意留空的，登记在 §7 第 5 条。
- **第二批只读域的消费方也已落地（2026-10-14 接线）**：思源学堂 / 教材查询 / 成绩 / 快速考勤流水
  四屏在 `:web` 上真能打开了 —— 取数是 `:core/net` 的四个新 `Campus*Api`，屏是 `:core` 的共享屏
  （逐屏见 §5.6 末尾那张表）。两屏在接线时多开了几个能力开关（见 §5.6 那一节的三条细节），
  拿不到的字段与新入口一样：不画就不画、也不拿一个像真的默认值顶上。

### 5.5 只读四域（2026-10-13 收尾轮落地）：成绩单 / 考勤 / 加餐券 / 宿舍电费

七个端点，四个 `:server` 路由文件（`DzpzRoutes.kt` / `AttendanceRoutes.kt` / `CouponRoutes.kt` /
`DormPowerRoutes.kt`），全部是「`:data` 里已有的实现在取数」的**薄包装**（解析口径一行不重做）。
统一口径照 §5.2：信封 `{code,data,message}`、`code == HTTP 状态码`、`401` =「请先登录：POST /api/session/login」、
`400` 参数形状、`404` 未实现、`502` 上游/网络故障、时间 ISO-8601（服务端时区）。

**会话**：四个域各自一个站点（`dzpz` / `coupon` / `ssn` / `new_attendance`），四个都已进
`ServeSession.SESSION_SITE_KEYS` 的登录预热清单 —— 它们的域名互相独立，没有哪个站点能替另一个建会话。
⚠️ `ssn` 不只是「预热更好」：`cid` 是**登录**时从缴费页 HTML 里取出来写进会话的（`SsnLogin.parseCid`），
没有它 `DormPowerApi` 直接抛「未登录」。

| 端点 | 请求 | 响应 `data`（逐字段） |
|---|---|---|
| `GET /api/dzpz/transcript` | ——（不收参数） | `{workflowId:int, typeOptions:[{name:string,value:int}], defaultDate:string, defaultRequestName:string}` |
| `GET /api/attendance/terms` | —— | `{terms:[{bh:string,name:string,code:string,startDate:string,endDate:string}]}` |
| `GET /api/attendance/records` | `?term=<学期号 bh>&from=<YYYY-MM-DD>&to=<YYYY-MM-DD>` | `{term:string, count:int, records:[{id,courseName,courseCode,term,date,week,startSection,endSection,location,status,statusText}]}` |
| `GET /api/coupon/list` | `?filter=<档>&page=<n>&size=<n>` | `{filter:string, page:int, size:int, total:int, records:[{sendId,showCardId,voucherName,typeName,amountFen,leftAmountFen,leftCount,startDate,endDate,imageUrl}]}` |
| `GET /api/coupon/detail` | `?showCardId=<必填>` | `{showCardId,voucherName,title,description,amountFen,leftAmountFen,startDate,endDate,batchId,imageUrl,closedPacketImageUrl,openPacketImageUrl}` |
| `POST /api/coupon/activate` | body `{showCardId:<必填>}` | `{ok:bool, message:string}` |
| `GET /api/dorm/rooms` | ——（不收参数） | `{rooms:[{id:string,name:string}]}` |
| `GET /api/dorm/reading` | `?roomId=<必填>` | `{roomId:string, kwh:number\|null}` |

**`GET /api/dzpz/transcript`（成绩单，只端七步里的第一步）**

- 端的是 `TranscriptSource.loadCreateForm(workflowId)` —— 七步里**唯一一步取数**。`workflowId` **不是请求参数**：
  本端点只端成绩单，取 `DzpzDocuments.TRANSCRIPT.workflowId`（= `29`）；
- `typeOptions` ← `FormContext.typeOptions`；`cancel=1` 的选项在 `:data` 的 `parseTypeOptions` 里已经滤掉了
  ⇒ **没有 `cancelled` 字段**（投影出来恒 `false`，多一格只会多一处口径）；
- `defaultDate` / `defaultRequestName` ← `FormContext` 同名字段（上游 `field7249` / `field-1`）；
- **不投影** `params` / `submitParams` / `maindata` / `linkageUUID` / `signatureAttributesStr` / `signatureSecretKey`：
  那是提交表单体与**签章密钥**，属于写路径的原料 —— 端出去等于把密钥交给浏览器；
- ⚠️ **第 2~7 步没有端点**（联动 / 预览 PDF / 两次提交 / 取下载链接 / 下载 PDF 字节）：第 2 步的返回值里有
  **学号**（红线），第 3~7 步是写路径 ⇒ serve 模式下这张屏**现在只能画「选类型」那一步**，
  出 PDF 那半条链是**如实缺口**（不是 bug）；
- 错误码：`401` 令牌对但没会话 · `502` 上游/网络故障。**没有 `400`**：本端点不收参数（URL 写错只能是 `404`）。

**D7 ⑤「成绩单 PDF 落盘」的结论（2026-10-15 批③-b，如实报告：本轮不做）**

先回答“`:data` 侧到底有没有 PDF 能力”这个问题：**有**。但 ⚠️ **下面这段写的是 `994e9c3b`（2026-10-17）之前的旧形状**（契约写于 10-16）：旧签名 `generatePreviewPdf(workflowId, typeValue): String`（第 3 步、不建单也能出预览、旧请求体 `reqid=-1&sfybc=0`）。**今天代码里不是这样**：预览是 **Step 4**、**必须传 Step 3（`submitCreate`，`src=save`，就是创建申请单）拿到的 `requestId`**、返回 `Unit`（`TranscriptModels.kt:70-74`、`TranscriptApi.kt:218-237`、`TranscriptViewModel.kt:68-69`）⇒ **「预览不产生申请记录」今天不成立**（2026-10-20 核实，把握高）。
与 `downloadPdf(url): ByteArray`（第 7 步，下载字节）。**所以不是“做不到”，是“不能这么做”**，三条理由：

1. **那是真的往学校提交一份申请，不是下载一个文件**：七步链的第 4、5 步是两次提交（`submitCreate`
   → `checksubmit` → `reloadAndForward`）、第 4 步还要签章（`FormContext` 里有 `signatureAttributesStr`
   / `signatureSecretKey`）。它会在学校那边留下一条**真的申请记录**（要审批）。而契约 §2 对写路径
   的要求是「能力开关 + 用户可见的二次确认」—— 服务端拿不到「用户按了确认」这个信号，浏览器点一下
   就等于替他交了。这不是加一个端点能解决的事，是**两段式流程**（先生成预览、用户看完再确认提交）；
2. **第 2 步的返回值里有学号**（§5.5 开头就写了）—— 整条编排必须在服务端跑（`:data` 的表单字段名
   就是 `"${userId}_${ctx.workflowId}_addrequest_submit_token"` 拼的），而**响应里一个字段都不能带它**。
   技术上做得到（本层只把最终字节端出去），但这是一条**只在服务端存在**的链：
   与其余二十多族「`:data` 已有实现、本层只做投影」的形状不同；
3. **「把字节落成文件」在契约的响应形状里没有位置**：§4 说每一个 `/api/…` 响应都是信封
   （`{code,data,message}`），两条例外（`/api/status` 裸对象、`venue/pay` 302）都不是这种。一份成绩单
   PDF 几百 KB～MB，塞进 `data` 只能 base64（体积乘 1.33、整体进内存），而开一条
   `application/pdf` 的**第三条例外**是契约变更（§4 明文写的“要开新例外就写进这一条”）。
   而浏览器侧的**落盘**（`Content-Disposition: attachment`）与 Android 的「保存到下载目录」是两件事。

**方案（已按 2026-10-19 会议改为「显式两段式提交」）**：

- ⚠️ **原「第一步（风险最小）：只端第 1~3 步、不提交不签章、不产生申请记录」已作废** —— 上一条已核实：今天代码里预览必然先建申请单。
- **已拍板（① = b）**：做**显式两段式提交** —— 第一步按钮直接写「**提交成绩单申请**」+ 说明「会在教务留一条待审批记录」；第二步出预览 / 下载；配套**幂等**（重复点不建第二张单）与失败 / 重放处理。它必须**开契约第三条例外**（`application/pdf`，或者用一个临时端点把 docId 换成一次性下载链接），并在 §2 的能力开关里加一格（`canSubmitApplication`）——“点了会失败的按钮一个都不画”那条口径在写路径上同样成立。
- **落地排期**：与 C-2 的端点批一起做（会议记录见 `docs/parity-ledger.md` §6）。
  就是它。没拍这个板之前，`:web` 的成绩单屏**保持现状**（只画「选类型」那一步 + 页顶那句“要出 PDF
  请在 App 或桌面端申请”）。


**`GET /api/attendance/terms` / `GET /api/attendance/records`（考勤）**

- `?term=` 是**学期号 `bh`**（形如 `646`，取自 `/api/attendance/terms` 的 `bh`），**不是**教务风格学期码；
  省略 = 当前学期（`:data` 的 `getTermBh()`：学期列表按 `startDate` 倒序取第一个，再按今天夹一次）。
  给了但不是纯数字 ⇒ `400`；
- `?from=&to=` 是 `YYYY-MM-DD`；省略时用 `:data` 的兜底（`from` = 该学期起、`to` = 今天）。
  给了但不成形 ⇒ `400`。**响应不回这两格**（兜底口径在 `:data`，本层不复制一份；要范围就看行的 `date`）；
- `terms` 的顺序 = `:data` 的顺序（`sortedByDescending { startDate }`，当前学期在第一个）；`name` 是 `:data`
  拼好的人类可读名（`"2026-2027 第一学期"` 那一套）、`code` 是它算的教务风格学期码（`TermCodeMapper`），
  本层不再拼第二份；
- `records` 命中 `AttendanceWaterRecord` 的第一批字段，**三处刻意的改名**（与 §5 表里 `amount ← signed`
  同一种做法）：`startSection ← startTime`、`endSection ← endTime`（`:core` 那两个字段名里的 `Time`
  其实是**节次**（`Int`，`1..11`），照抄会让消费方以为是时刻）、`term ← termString`（学期码，
  取不到码时 `:data` 兜底成学期名）。行按日期降序；
- `week` = 第几周（`0` = 算不出来，`:data` 不编）；`id ← sbh`（`resultId`，取不到时由 `:data` 按明细拼一个）；
- `status` = `WaterType.value`：`1` 正常 / `2` 迟到 / `3` 缺勤 / `5` 请假 / **`-1` = 未识别的状态码**；
  `statusText` 是配套中文（`-1` ⇒「未知」）。⚠️ **`-1` 必须按 `statusText` 显示** —— `:data` 的 `WaterType`
  KDoc 把这条写死了（不许悄悄算成正常，那会把统计数字做成好看的假数字）；
- ⚠️ **`teacher` 不投影**：考勤记录里的上课教师名是**姓名**，而 §5.2 的例外清单里没有它（例外只有
  ① `fitness/score` 本人姓名/学号 ② 黄页/教师检索的通讯录 ③ `emptyroom/rooms` 实时档的教师名
  ④ 请假那一族的审批人姓名 / 任课教师姓名）。⚠️ ④ **管不到这一格**：它只覆盖「我自己的申请 / 我自己
  的课」上那两个名字，而这一格是**当堂上课的教师名**（批次③-b 的遗留件 12g 拍板时也没有松这一条）。
  `:web` 若要显示这一格的教师名，得先把它加进例外清单（那是改契约，不是改这一层）；
- ⚠️ **还没端的三块**（serve 模式下考勤屏目前只有「学期选择 + 流水表」）：`getStudentInfo`（姓名那格过不了
  红线）、统计栏（`getKqtjCurrentWeek` / `getKqtjByTime` / `computeCourseStatsFromRecords`）、
  打卡原始流水（`getStreams`）；
- **请假那一半一律不端**（`getSemesterWindow` / `getFlowPreview` / `getLeavePage` / `uploadEvidence` /
  `createLeave` / `withdrawLeave` / `cancelLeave`）：请假 / 销假是写路径；
- 错误码：`400` 上面那三格形状不对 · `401` 没会话 · `502` 上游/网络故障。

**`GET /api/coupon/list` / `GET /api/coupon/detail`（加餐券，只读）；`POST /api/coupon/activate`（领券，写）**

- `?filter=` 取四档的**小写枚举名**：`available`（可领取，status=0）· `usable`（可使用，status=1）·
  `used_up`（已用完）· `expired`（已过期）。**省略 = `usable`** —— `:core` 的 `CouponViewModel` 初始档
  就是它，本层不另定一个默认档。中文名（`CouponFilter.label` =「可领取」那些）**不当参数收**：参数只认
  一套 ASCII 写法，响应里的 `filter` 也是同一个值；
- `?page=`（省略 `1`）`&size=`（省略 `20`，照 `:core` 的 `CouponViewModel` 拉列表用的页大小）；
  不是正整数 ⇒ `400`；
- 金额一律**单位是分**（`amountFen` / `leftAmountFen`，`:core` 模型的原字段名）。`CouponRecord` 上那两个
  `amountYuan` 属性是**换算便利属性**、不是模型字段 ⇒ 不进线上形状（除 100 是消费方的事，本层不做第二次舍入）；
- `imageUrl` 是 `:data` 的 `normalizeImageUrl` 拼好的**绝对地址**（`https://egc.xjtu.edu.cn` + 相对路径；
  上游 `pic` 为空 ⇒ 空串，不编地址）。⚠️ **已知缺口**：那张图要带 `egc` 的 `Authorization` 才取得到 ⇒
  浏览器**直接打开这个 URL 会拿到登录页**；本层没有图片代理端点（要封面图就得下一批开一个）；
- `detail` 的十二格原样投影（`title ← destitle`、`description ← describes`、`leftAmountFen ← ltranamt`
  那些映射在 `:data` 的 `CouponJsonParser` 已经做掉）；`showCardId` 是请求给的那个原样回；
- ✅ **领券已端（2026-10-15，批③-b / D7 ③）：`POST /api/coupon/activate`** —— body 只有一格
  `{showCardId}`（取自 `/api/coupon/list`），成功回 `{ok:true, message}`（与 §5.3.1 / §5.3.2 两个写端点
  同一个形状）。`:core` 的编排一字未改：`CouponViewModel.receive` 仍是**先查详情再领、领完重拉那一档**；
- ⚠️ **领券区分不了「上游说不能领」与「上游坏了」**：`:data` 的 `CouponApi.activateCoupon` 返回 `Unit`，
  失败时只抛一句上游原文 ⇒ 与只读那两条同一条口径，一律 `502`（就连上游那句「该券已领完」也是）。
  要分出 `409` 得先把 `:data` 那一层的失败细分（与 `/api/library/action` 的 `OrderActionResult` 同一个
  形状）—— 本轮不编一个判据出来；
- 错误码：`400` `filter` 不认识 / `page`、`size` 不是正整数 / `showCardId` 缺失 / `activate` 的 body 不是那一格 · `401` 没会话 ·
  `502` 上游/网络故障（`:data` 认出的会话失效那一档回 `401`，见 §4）。

**`GET /api/dorm/rooms` / `GET /api/dorm/reading`（宿舍电费，只读）**

- 与 campus-api 的同名能力对照：旧的是 `GET /api/dorm/kwh?roomId=`（一个端点把列表与读数混在一处）；
  本契约拆成 `rooms` / `reading` 两个（列表一次、读数一间一次），**旧的路径不在本契约里**；
- `rooms` 不收参数；`id ← roomId` 是**要原样带回**给 `reading` 的句柄，`name ← roomName`
  （「园区/楼/单元/层/房间」拼好的全名）。上游缺 `roomId` 的行由 `:data` 丢掉（不是投影成空 id）；
- `?roomId=`（`reading` 必填，取自 `rooms` 的 `id`）；缺或空白 ⇒ `400`；
- `kwh` 是**剩余电量（度）**，读不到就是 **`null`**（§4：`null` = 上游明确说没有 —— 那间房没装表，
  **不是 0**）；
- ⚠️ **没有时间戳**：`:core` 的 `DormReading.at` 是 `DormPowerStore` 在**本机**记的「什么时候查到的」，
  上游不给 ⇒ 本端点不编（§4：时间一律 ISO-8601，编一个反而是第二个口径）。要「这张读数是什么时候的」
  由消费方在**自己拿到响应的那一刻**记；
- **绑定 / 解绑 / 充值 / `freshPayUrl` 都不端**：那几件事发生在缴费页里（浏览器端的事）；`freshPayUrl()`
  只是「换一张能直接登录缴费页的入口地址」，而且它**顺手记下「用户进过这一页」**（`DormPowerStore.markUsed`）
  —— 那是宿主侧状态，serve 这一层不该替浏览器记 ⇒ `:web` 的「去缴费」那一跳在 serve 模式下没有端点；
- 错误码：`400` `roomId` 缺/空白 · `401` 没会话或会话失效（上游的
  `401001` 被 `:data` 认成登录过期 ⇒ `401`，见 §4） · `502` 上游/网络故障。

#### 消费方（`:web`）：**已落地**（2026-10-13 接线）

七个端点由 `:core/net` 的四个新 `Campus*Api` 消费（`CampusTranscriptApi` / `CampusAttendanceApi` /
`CampusCouponApi` / `CampusDormPowerApi`）—— 它们与既有那 13 个同一族（`ApiMode` 双形态），只有一个差别：
**默认形态（campus-api）那一支不猜也不编空数据，直接抛错**（这四个域在 campus-api 里根本没有端点：
`:app` / 桌面是走 `:data` 的 okhttp 抓的）。`:web` 因此把这四屏写成「serve 形态才画真屏，否则画一张
「这一屏要 serve 模式」的如实页」，并把拿不到的东西画在屏幕上，而不是编出来：

| 屏 | 端点 | `:web` 上真画了什么 | 如实不画（缺口） |
|---|---|---|---|
| 电子成绩单 | `GET /api/dzpz/transcript` + `POST /transcript/{submit,preview}` + `GET /transcript/download?ticket=` | 类型选择 + 申请信息（「申请日期」就是 `defaultDate`）；**serve 形态下「一键申请」与那五步步骤预览都真能走**（两段式，见 §5.12）—— 真的会在教务留一条待审批记录、出完 PDF 还能存到本机 | `params` / `submitParams` / `maindata` / `linkageUUID` / `signatureAttributesStr` / `signatureSecretKey` 六格**留空**（刻意不投影）；⚠️ **campus-api 形态**仍只有第一步（那条老路没有写端点，`canRunWorkflow = false`）；学籍联动那一格在 serve 下是**空壳**（服务端在建单那一发里自己做，返回值里有学号 = 红线） |
| 考勤 | `GET /api/attendance/{terms,records,stats,streams}` + `/leave/{window,preview,records}` + `POST /leave{,/withdraw,/cancel}` | 学期选择器 + 流水表（节次 / 状态 / 地点 / 日期）+ **按课统计那一栏（八格）** + **打卡原始流水那一栏（四格）**；**「请假」那一栏真能用**（窗口上限与日期范围 / 审批人下拉（**画的是人名**）/ 记录卡 / 提交表单 / 撤回与销假两个确认弹窗） | 上课教师名（**流水那一栏**，不投影 —— 红线）；**请假那一族的两类姓名（审批人姓名、请假单里那节课的任课教师姓名）自 2026-10-15 起按 §5.2 例外 ④ 投影**（下拉框不再退化成一行 id）；姓名那一格（`getStudentInfo` 不端）、请假附件（本端没有文件选择宿主 ⇒ 与桌面同一条，不发附件）。⚠️ **2026-10-22（批5）起三栏全在**（`AttendanceCapabilities` 三项都 `true`）：统计那一栏的数字是**服务端按同一算路算的**（算路在 `:data`，只有 JVM/Android 变体，浏览器里拿不到它 ⇒ 端口那一格改成 `suspend`，见 `AttendanceSource`） |
| 加餐券 | `GET /api/coupon/list` + `/detail` + `POST /activate` | 四档 + 翻页 + 金额（分）+ 有效期；**「领取」按钮真画、真能领**（领完重拉当前那一档，与两端同一条编排） | 封面图（要 egc 会话 ⇒ `loadImage` 给 `null`、券卡退化成图标）；金额不换算成元（除 100 是屏的事）；领券**分不出「上游说不能领」与「上游坏了」**（都落 502，见上） |
| 宿舍电费 | `GET /api/dorm/rooms` + `/reading` | 房间列表 + 剩余电量（`kwh: null` 单独说「没有电表」）+ **本机**查询时刻 | 「去缴费」/ 绑定 / 充值那一跳（`freshPayUrl` 不端）：本端画的是 `:web` 自己的读数页（`WebDormPowerScreen`），**不是** `:core` 那张只做缴费页浏览器的 `DormPowerScreen` |

三处要点（写在这里，免得下一个读的人当成 bug 或当成漏）：

1. **入口**：这四条路由同时进了 `:web` 的 `WebSupport.openableRouteIds`（首页宫格 / 学辅目录的唯一真源）
   与 `AppPage` 的 `when (route)` —— 缺任一处就是「屏在、门不在」那一类漏（上一轮踩过的就是它）；
2. **campus-api 形态**：那四个 `Campus*Api` 的非 serve 分支一律 `serveOnly(...)` 抛错，`:web` 在那形态下
   画的是「这一屏要 serve 模式」那张页 —— 不是「还没搬到 Web」（屏就在 `:core`）；
3. **本机时刻与 `null` 的区别**：宿舍电费的读数时间戳由消费方在**自己拿到响应的那一刻**记（端点不端
   时间戳，见上）；`kwh` 的 `null`（上游明确说「没有表」）与**字段缺失**（形状没对上）在客户端是两件事，
   后者抛错（不拿 `null` 冒充「没有表」）。

夹具与用例：`:server:test` 的 `ServeReadonlyFourDomainsTest` 8 例（形状那一侧）之外，本轮补了
`:core:jvmTest` 的 `ServeReadonlyFourDomainsApiTest` **6** 例 —— 逐字段、错误信封、`status=-1` → 「未知」、
缺字段不编造、`kwh` 缺失 vs `null`、以及「campus-api 分支抛错而不是返回空数据」。


### 5.5.1 考勤请假 / 销假（2026-10-15 落地，批③-b / D7 ②）：三读 + 三写

六个端点（`:server` 的 `AttendanceRoutes.kt` 里 `attendance/leave*` 那一段），全是「`:data` 里已有
实现在取数 / 写数」的**薄包装**（`AppAttendanceSource` 包住 `LeaveApi`，解析口径一行不重做）。
决策来源：`docs/desktop-port-plan.md` §16.1 的 **D7**（写操作三端全等，Android 现成的写路径就是基准）。
统一口径照 §5.2：信封 `{code,data,message}`、`code == HTTP 状态码`、`401` =「请先登录：POST /api/session/login」、
`400` 参数形状、`404` 未实现、`502` 上游/网络故障。

**会话**：打的是 `new_attendance` 站（`:data` 的 `AttendanceSession`，它早就在登录预热清单里）——
与 §5.5 的 `/terms`、`/records` 同一个站点，**一个新站点都没注册**。

| 端点 | 请求 | 响应 `data`（逐字段） |
|---|---|---|
| `GET /api/attendance/leave/window` | ——（不收参数） | `{semesterId,academicYear,semesterName,startDate,endDate,maxStudentLeaveMinutes:int}` |
| `GET /api/attendance/leave/preview` | `?type=SICK\|PERSONAL`（**必填**） | `{nextNodeNo:int, nextApprovers:[{userId,name}], nodes:[{nodeNo,nodeRole,nodeName,configured,approvers:[…]}]}` |
| `GET /api/attendance/leave/records` | `?page=&size=&status=&type=`（都可省） | `{page:int,size:int,total:int,records:[{…十九格…}]}` |
| `POST /api/attendance/leave` | body `{type,startTime,endTime,reason,nextApproverUserId,requestId}` | `{leaveId:string}` |
| `POST /api/attendance/leave/withdraw` | body `{leaveId,reason,requestId}` | `{ok:bool, message:string}` |
| `POST /api/attendance/leave/cancel` | body `{leaveId,requestId}` | `{ok:bool, message:string}` |

**为什么是这六条**：`:core` 那张请假屏要的就这几样 —— **窗口**（表单上的日期范围与单次上限）、
**流程预览**（那个审批人下拉框）、**列表**（「请假」那一栏的卡）+ 提交 / 撤回 / 销假三个写。
编排一字未改：`AttendanceViewModel` 与 `LeaveFormDialog` 那份编排（窗口 → 审批人 → 表单 → 提交）
在两端是同一条 `:core` 代码路径（`:web` 只换了一个 `AttendanceSource` 实现）。

**逐字段与几处刻意做法**

- `window` 六格原样投影（`:data` 的解析结果，本层不拼第二份）；
- `preview` 的 `?type=` 只认 `SICK` / `PERSONAL`（**大小写不敏感**），**缺了或认不出 ⇒ `400`**：
  `:data` 的 `LeaveType.fromCode` 会把认不出的值静默当成「私事假」，而这是**参数**（不是上游数据）
  ⇒ 本层不沿用它那个降级（拿一个默认值回一份看起来对的流程，用户根本不知道发错了）；
- `records` 的 `page` / `size` 默认与校验与其余各族同一条（`/api/coupon/list` 那一套，`positiveInt`
  已经收进 `ServeRouteSupport.kt`）；**回的是请求给的那两个**（上游回显的 `pageNum`/`pageSize`
  本层不回 —— 消费方一个都不读）；`status` / `type` 是**透传**给 `:data` 的（它那两档的映射
  —— `APPROVED` 要配 `cancelStatus=PENDING_CANCEL` 那一套 —— 在 `:data` 里，本层不重做）；
- `records[].leaveId` 为空、`preview.nextApprovers[].userId` 为空的那几条**丢掉**（它们是屏上
  可点击的句柄，空值等于一个点了没反应的卡）；
- ✅ **两个姓名已按 §5.2 例外 ④ 真投影**（2026-10-15 上一级拍板）：① `preview.nextApprovers[].name`
  与 `nodes[].approvers[].name`（**我自己的申请上的审批人姓名**，学校工作人员）；②
  `records[].courses[].teacherNames`（**我自己的课的任课教师姓名**）。这处原是批③-b 记下的
  「需要上一级拍板的事」：拍板理由与例外 ③ 同性质（都是「我自己的那条记录」上的真人名），
  而且不加就与 Android 屏不一致（`:core` 的屏写的是 `DropdownItem(text = it.name.ifBlank { it.userId })`
  ⇒ 留空不丢功能，但那个下拉框会退化成一行 `userId`，而提交要的就是那个 `userId`）；
- ⚠️ **仍不投影的一处**（不是漏）：考勤流水那一条的 `teacher`（§5.5）—— 例外 ④ **只覆盖上面那两类**，
  不是「一切教师名」。那一格照旧保留**字段**（形状与 `:core` 的模型逐字对齐）、值是**空串**；
- **附件上传不端**：`uploadEvidence`（`multipart/form-data`）**没有**端点，而提交那一发的请求体里也
  **没有** `evidenceFiles` 这一格（`:data` 那一层收下来直接发空表）。理由不是
  「难做」而是**这一端根本没有文件来源**：`:web` 的 `AttendanceScreen` 用的是 `pickEvidenceFiles`
  的默认值（空表），Android 的 SAF 多选与桌面那份宿主都不在浏览器里 ⇒ 与桌面同一条（不发附件）；
- **二次确认在屏上**：撤回 / 销假各有一次 `OverlayDialog`，提交前有一整套表单校验（时间必须晚于
  此刻 / 必须整点 / 单次上限 / 病假超一天要附件……）。本层**没有再加一层** —— 服务端没有「用户按了
  确定」这个信号，编一个只会变成第二个口径；
- **幂等键**：`requestId` 由**调用方**给（与两端接口签名一致）；`:server` 这一层**缺了就 `400`**
  （重发两次不该变成两条请假）。`:core` 的 `CampusAttendanceApi` 在空串时自己补一枚 16 位十六进制
  随机串（`secureRandomInt`，与 `FeedbackStore` 同一个字母表）；
- **两个时间的形状**：`yyyy-MM-dd HH:mm:ss`（`LeaveFormDialog.formatLeaveTime` 拼的就是它），
  不成形 ⇒ `400`。**不转 ISO-8601**：这一格是上游要的表单值，不是契约 §4 那种「时刻」（转了反而
  会被上游当格式错）；
- **错误码**：`400` 上面那几格形状不对 · `401` 令牌对但没会话 · `502` 上游/网络故障（**含「上游拒收
  这次提交」那一档** —— `:data` 的 `KqHttp.execute` 把 JSON 里 `code != 0` 抛成一句
  `RuntimeException(message)`，本层按 502 报并把那句中文交回用户；要分 `409` 得先改 `:data`）。

#### 消费方（`:web`）：**已落地**（2026-10-15，本批）

`:core/net` 的 `CampusAttendanceApi` 补上那六条（三读 + 三写），`AttendanceCapabilities.leave`
从 `false` **翻 `true`** —— 屏上「请假」那一栏（页签 / 列表 / 表单 / 撤回 / 销假）就此真的可用，
不再是「本端拿不到请假记录」。`:web` 那一分支的页顶说明与 `docs/serve-visual-checklist.md`
的考勤那一行同步改了。

| 真画了什么 | 如实不画什么 |
|---|---|
| 学期 + 流水表（原有）；**「请假」页签真能用**：窗口上限与日期范围、审批人下拉（**人名，非 `userId`**）、记录卡、提交表单、撤回 / 销假两个确认弹窗 | 上课教师名（**流水那一栏**，红线 —— 请假单里那两处姓名已按例外 ④ 端出来了）；统计栏；打卡原始流水；**附件**（本端没有文件选择宿主 ⇒ 与桌面同一条，不发附件） |

夹具与用例：`:testkit` 的 `AttendanceFakeUpstream` 新增 `leaveCreateRejected` 开关与两个编造姓名
常量（`LEAVE_APPROVER_NAME` / `LEAVE_COURSE_TEACHER`）、并给夹具那条请假记录补上一节课
（原先 `courses` 是空表），那六条路由**早就在**；`:server:test` 新增 `ServeAttendanceLeaveTest`
**5** 例（含一条专钉红线：只允许这两类姓名，别的一律不许），`:core:jvmTest` 的
`ServeReadonlyFourDomainsApiTest` 那一例同步（三读三写真走 + 两类姓名真投影 + 补幂等键）。


### 5.6 第二批只读域（2026-10-14 落地）：思源学堂 / 教材中心 / 移动教务成绩 / 人脸识别签到

八个端点，四个 `:server` 路由文件（`LmsRoutes.kt` / `JiaocaiRoutes.kt` / `JwappRoutes.kt` /
`IclassfaceRoutes.kt`），全部是「`:data` 里已有实现在取数」的**薄包装**（解析口径一行不重做）。
统一口径照 §5.2：信封 `{code,data,message}`、`code == HTTP 状态码`、`401` =「请先登录：POST /api/session/login」、
`400` 参数形状、`404` 未实现、`502` 上游/网络故障（含会话失效那档）。

**会话**：四个域各自一个站点（`lms` / `jiaocai` / `jwapp` / `iclassface`），四个都已进
`ServeSession.SESSION_SITE_KEYS` 的登录预热清单 —— 与 `:desktop` 的 `DesktopAuth.SESSION_SITE_KEYS`
是**同一份清单**（四个 key 逐字相同）。四个都是独立登录页（`lms` 直连 CAS；`jwapp` 走开放平台
`appId=1370`；`iclassface` 走 CAS OAuth2 `client_id=1543` → 开放平台 `authorizesw`；`jiaocai` 走
`/entry/login` → 超星 SSO），没有哪个站点能替另一个建会话。
⚠️ `jwapp` 那一族的**第二条上游在教务**（`precise-scores`，站点 `jwxt`）—— 那个站点本来就在清单里。

| 端点 | 请求 | 响应 `data`（逐字段） |
|---|---|---|
| `GET /api/lms/courses` | ——（不收参数） | `{courses:[{id,name,courseCode,courseType,credit,compulsory,startDate,endDate,academicYear:{id,code,name,sort},semester:{id,code,name,realName,sort},department:{id,name,code},courseAttributes:{published,studentCount,teachingClassName}}]}` |
| `GET /api/lms/activities` | `?courseId=<正整数，必填>` | `{courseId,count,activities:[{id,courseId,type,title,moduleId,startTime,visibleStartAt,endTime,deadline,submitByGroup,published,createdAt,updatedAt,isClosed}]}` |
| `GET /api/lms/activity` | `?courseId=&activityId=<两个都必填>` | `{id,courseId,type,title,description,moduleId,startTime,visibleStartAt,endTime,deadline,published,createdAt,updatedAt,isClosed,uploads:[{id,name,type,size}],homework:{submitByGroup,groupId,groupSetName,userSubmitCount,averageScore,highestScore,lowestScore,hasScoreCount,submitTimes,nonSubmitTimes}\|null,lesson:{lessonStart,lessonEnd,replayVideoCount}\|null,live:{liveRoomName,liveRoomCode,liveStatus,externalLiveId,viewLive,viewRecord}\|null}` |
| `GET /api/jiaocai/search` | `?keyword=<必填，空白⇒400>` | `{keyword,count,books:[{id,appId,engineInstanceId,title,author,summary,ssno,hasFullText,fields:{<上游原文的键>:<值>}}]}` |
| `GET /api/jiaocai/ssno` | `?id=&appId=&engineInstanceId=<三个都必填>` | `{id,ssno:string\|null}` |
| `GET /api/jwapp/scores` | `?term=<省略 = 全部学期>` | `{term:string\|null,count,terms:[{termCode,termName,scoreList:[{id,termCode,courseName,score,scoreValue,passFlag,specificReason,coursePoint,examType,majorFlag,examProp,replaceFlag,gpa,source,courseCategory,courseCode,courseGroup}]}]}` |
| `GET /api/jwapp/precise-scores` | ——（不收参数） | `{count,scores:[{courseName,termCode,zcj,xfjd,xf,djcjlxdm,djcjmc,pscj,pscjxs,qmcj,qmcjxs,kch,jxbid,passFlag,examProp,kclbdm}]}` |
| `GET /api/iclassface/records` | `?date=<YYYY-MM-DD；省略 = 今天>` | `{date,count,records:[{time,location,type}]}` |

**思源学堂（`lms`）**

- 三层形状**原样沿用 `:core` 的模型**（字段名一个不改：它们是 `LmsCourseSummary` / `LmsActivity`
  的字段）。嵌套的 `academicYear` / `semester` / `department` / `courseAttributes` 直接复用 `:core`
  那四个 `@Serializable` 数据类，本层不重写一份；
- `type` 是 `LmsActivityType.value`（`homework` / `material` / `lesson` / `lecture_live` / `page` /
  `forum` / `questionnaire` / `online_video`，认不出是 `unknown`）—— `:core` 的
  `LmsActivityType.fromString` 吃的就是同一个字符串，消费方**零转换**；
- ⚠️ **`courseId` 在 `activity` 里也是必需的**，不是可选提示：`deadline` **只有列表接口给**
  （详情 79 个键里没有它）⇒ 本端点多打一枪列表、把 `activityId` 那一条当 `brief` 传给
  `activityDetail`（就是 `:core` 的 `LmsApi.mergeBrief` 那条口径）。少了它，`deadline` 会退回
  `endTime`（实测最多差 20 天）—— 屏上那个「截止时间」会悄悄变成另一个值；
- `activity` 的 `data` 里那三个块（`homework` / `lesson` / `live`）**只有与 `type` 对应的那一个是对象**，另两个是 `null`；
- ⚠️ **不投影的字段**（都是**如实缺口**，不是 bug）：
  ① `instructors` / `liveInstructorNames`（**教师姓名**，§5.2 红线的例外清单里没有这一族）；
  ② 附件的 `downloadUrl` / `previewUrl` / `attachmentUrl`（要站点会话，浏览器直接打开只会拿到登录页）
  —— 附件只端元数据 `{id, name, type, size}`；
  ③ `lesson` 的 `replayVideos` / `replayDownloadUrls`、`lecture_live` 的 `liveStreams` / `liveReplayVideos`
  （播放与下载那半条链在 `:web` 上没有端点）⇒ 只端 `replayVideoCount` 那个**条数**；
  ④ 作业的提交记录（`submissionList`，里面有提交人姓名与学号）；
- **刻意不端的写路径 / 宿主能力**：作业提交、附件下载与落盘（`saveDownload`）、回放排队
  （`queueReplayVideos`）、附件图片预览（`uploadBytes`）—— 一条都没有端点；
- 错误码：`400` `courseId` / `activityId` 缺或不是正整数 · `401` 没会话 · `502` 上游/网络故障。

**教材中心（`jiaocai`）**

- `search` 命中 `:core` 的 `JiaocaiBook` 全部九格：`title` 已剥掉 `<b>` 那些标签、`summary` 是
  `<br>` 分行的富文本转成的纯文本、`fields` 是**上游原文**的键值对（课程名称 / 教材名称 / 编者 /
  出版社 / ISBN号…，键名不翻译、不重排）。书目信息是**公开数据**，没有姓名/学号那一类字段；
- ⚠️ **`ssno` 在 `search` 里恒 `null`**：`search-list` 的 `content` 被服务端剥掉了 `<a>`（只剩
  「获取方式一：本地全文」这句文字）⇒ 要全文库书目号就得对那本书再打一次 `ssno`（`fetchSsno`）；
  `ssno` 响应**只回两格**（不回 `hasFullText`：这里没有 `fields`，重算等于给同一个判据起第二个写法）；
- **不收 `page` / `pageSize`**：这一屏没有翻页（`:data` 固定 20 条一页、只取第一页）；
- ⚠️ **「本学期我的教材」那一栏不端**（屏上搜索框为空时那一段）：数据不在教材中心，而在**教务**
  的报表里（`:core` 的 `JiaocaiTermTextbooks` 是个**由宿主实现**的端口），而 `:data` 里真正取它的
  是 `JwxtScheduleApi.textbooks(studentId, term)` —— **要学号**（§5.2 红线）；
- ⚠️ **全文阅读不端**：`jiaocai1.lib` 的页图与阅读器是另一条链（另一个域名、另一套会话），本轮不碰；
- ⚠️ 没有会话时上游返的是一张「JS 跳转登录链」页（`:data` 的 `PortalRedirect` 会自己跟完那几跳），
  跟不通就按 `:data` 的老口径**回空列表** ⇒ 「查不到书」与「会话断了」在 `search` 这一发上长得一样
  （那是 `:data` 既有的口径，本层不改）；
- 错误码：`400` `keyword` 空白 / `id` 缺 / `appId`、`engineInstanceId` 不是整数 · `401` 没会话 · `502` 上游/网络故障。

**移动教务成绩（`jwapp`）—— 一族两个站点**

- `scores` 是对 `JwappApi`（`jwapp.xjtu.edu.cn`）的 `POST /score/termScore`；`precise-scores` 是对
  **教务** `xscjcx.do`（`CjcxApi`，`jwxt.xjtu.edu.cn`）的自动分页拉取。**两条路径各自打一个站点**；
- **服务端不合并两份**：`:core` 的 `JwappScoreViewModel` 是「分别取、在屏上按学期 + 课程名替换」的
  （那份归一化 `normalizeCjcxCourseName` 在 `:core`、有测试钉着）—— 服务端合并等于把同一件事做第二遍；
- `scores` 的 `terms` 直接复用 `:core` 的 `TermScore`（`termCode` / `termName` / `scoreList`），
  `source` = `JWAPP` / `REPORT`、`courseGroup` = `GEN_CORE` / `GEN_ELECTIVE`（枚举的线上形状是**枚举名**）。
  ⚠️ `score` 是**字符串**（上游原文，可能是「优秀」「通过」这种等级制文本），`scoreValue` 才是解析出的数字；
- `precise-scores` 的字段名就是**教务列名**（本层不翻译成「好看的名字」—— §7.3 同一个字段名不在这两处
  各起一个）。缩写对照：`zcj` 总评成绩 · `xfjd` 学分绩点 · `xf` 学分 · `pscj` / `pscjxs` 平时成绩（值 / 显示文本）·
  `qmcj` / `qmcjxs` 期末成绩 · `djcjlxdm` / `djcjmc` 等级成绩类型代码 / 名称 · `kch` 课程号 · `jxbid` 教学班 id ·
  `examProp` 考核性质（`CXCKDM_DISPLAY`）· `kclbdm` 课程类别（`KCLBDM_DISPLAY`）· `passFlag` 是否通过。
  ⚠️ 上游对「没这一格」的写法是 `-1.0`（不是 `null`）—— `:data` 的 `takeIf { it > -100 }` 原样留着，
  消费方要自己认那个哨兵值（与 `:core` 的 VM 同一条口径）；
- ⚠️ `?term=` **不做形状校验**（学期码的合法取值就是响应里的 `termCode`），省略 = 全部学期
  （上游请求体里是 `{"termCode":"*"}`）；给了个空白串是「形状不对」⇒ `400`；
- ⚠️ **两个端点的会话是两个站点**：`jwapp` 没会话时 `scores` 502；`jwxt` 没会话时 `precise-scores` 502
  —— 而 `:core` 的 VM 对后者是**容忍**的（拿不到就只出 jwapp 那一份），消费方照那条规矩处理；
- ✅ **`getScoreDetail`（一门课的**分项**成绩，屏上「点开一门课」那一格）已于 2026-10-14 端出**：
  `GET /api/jwapp/score-detail?courseId=`，见 **§5.8** —— 这一行当时写的是「刻意不端 / 如实缺口」（还带了
  「下一批可以照 `?courseId=` 再开一条」那句 TODO），本批按 `docs/desktop-port-plan.md` §16.1 的 D13
  「一律端」把它改成已端；屏上那个能力开关（`JwappScoreSource.canFetchScoreDetail`）**已随 §5.8 的
  消费方接线翻成 `true`**（serve 形态；campus-api 形态仍是 `false`，那个上游没有这一条）—— 见 §5.8
  的消费方表；
- **刻意不端**：`unevaluatedCourseNames`（评教那一侧的名单）、`reportedGrades(studentId)`（**要学号**，红线）、
  `reAuthenticate`（宿主凭据的写动作）、`JwappScoreCache` 的读写（宿主落盘缓存）；
- 与 `/api/jwxt/grades`（§5.2，`ScoreReportSource` 的**成绩报表**）不是同一份数据，两者互不替代；
- 错误码：`400` `term` 空白 · `401` 没会话 · `502` 上游/网络故障。

**人脸识别签到（`iclassface`）**

- 上游那张表一行是 `<序号, 姓名, 学号, 时间, 地点, 类型>`：⚠️ **`name` / `studentNo` 不投影**
  （§5.2 红线；例外清单里没有这一族）—— `:core` 的 `IclassfaceScreen` 本来也不画那两格，
  所以**整屏可复用**。**照片也没有**：这一族的上游根本没有照片字段（`:data` 只解析那张表）；
- ⚠️ `time` 是**上游原文**的 `2026-06-04 14:14:37`，**本契约唯一一处不按 §4 转 ISO-8601 的时间**：
  它本来就是给人看的刷卡时刻、上游没给时区标记 ⇒ 服务端硬转一次只会多一处可能错的口径。
  消费方按「**服务端时区的墙上时间**」显示，**不要**再 `parse` 成 UTC 再本地化（那会整体平移）；
- `?date=` 省略 = **今天**（`:shared:model` 的 `todayOnCampus`，固定按学校时区 Asia/Shanghai，与服务端部署时区无关）；给了但不成形 ⇒ `400`。
  ⚠️ **没有分页**：上游就是「一天一枪 POST、返回整张表」；
- 空数组 = 那天确实没有记录（含「还没刷上卡」），**不是**错误；响应回 `date`（定下来的那一天）；
- 与 `/api/attendance/{terms,records}`（§5.5）不是同一件事：那条是本科考勤业务站的**课程级**流水，
  这一条是签到平台的「今天刷没刷上卡」——两屏在 `:app` 与桌面上本来就并存、互不替代；
- 错误码：`400` `date` 不成形 · `401` 没会话 · `502` 上游/网络故障。

#### 消费方（`:web`）：**已落地**（2026-10-14 接线）

八个端点由 `:core/net` 的四个新 `Campus*Api` 消费（`CampusLmsApi` / `CampusJiaocaiApi` /
`CampusJwappScoreApi`（一个类实现两个端口）/ `CampusIclassfaceApi`）—— 与上一批那四个同一族
（`ApiMode` 双形态），同样：**默认形态（campus-api）那一支不猜也不编空数据，直接抛错**（这四个域
在 campus-api 里根本没有端点：`:app` / 桌面是走 `:data` 的 okhttp 抓的）。`:web` 因此把这四屏写成
「serve 形态才画真屏，否则画一张「这一屏要 serve 模式」的如实页」，并把拿不到的东西画在屏幕上，
而不是编出来：

| 屏 | 端点 | `:web` 上真画了什么 | 如实不画（缺口） |
|---|---|---|---|
| 思源学堂 `LmsScreen` | `GET /api/lms/courses` + `/activities?courseId=` + `/activity?courseId=&activityId=` | 课程卡（名称 / 课程号 / 学分 / 学期 / 教学班 / 人数）+ 活动列表（按类型筛、勾选批量下载）+ 详情页（正文、附件卡、作业信息与截止时间） | ① 教师姓名：`instructors` / `liveInstructorNames` 不投影 ⇒ `instructorNames` 显示「未知」；② 附件的预览与下载、以及「批量下载」那一整档（`LmsSource.supportsDownload = false`）：附件的三个地址都不投影，浏览器直接打开只会拿到登录页；③ 回放与直播的**播放地址**（`lesson.replayVideos` / `live.liveStreams` 不端）⇒ 屏上没有可点的播放动作；④ 作业的**提交记录**（`LmsSource.supportsSubmissionList = false`，那块里有提交人姓名与学号）—— 别拿「还没有提交记录」那张卡冒充它；⑤ 正文按本端的纯文本近似显示（`htmlToPlainText` 这个纯函数契约不端，`:data` 那份用 jsoup，wasm 上没有） |
| 教材查询 `TextbookScreen` | `GET /api/jiaocai/search?keyword=` + `/ssno?id=&appId=&engineInstanceId=`（+ 「本学期我的教材」那一段走 §5.8 的 `GET /api/jwxt/textbooks`） | 「查教材」那一栏：一个关键词输入 + 结果卡 + 详情卡（字段表 / 书目号补齐），以及【本学期我的教材】那一段（课程 + 书名，无教材的不列） | ① 「本学期我的教材」那一段 —— **2026-10-14 批①收尾已接上**（数据在**教务**的报表里，端点是 §5.8 的 `GET /api/jwxt/textbooks`，学号由服务端从会话里取）：宿主端口 `WebJiaocaiTermTextbooks` 读那一发，中间的映射在 `:core` 的 `toJiaocaiTermTextbooks()`（滤掉「无教材」那些行 + 只搬五格，与 `:app` 的 `AppJiaocaiTermTextbooks` 逐字同口径）—— 接线当时那句「本端还没把它接到这一屏」到此为止；② 「书架」「全文库」两栏与各处的「读全文 / 阅读全文」入口（`jiaocai1` 是另一个域名、另一套会话 ⇒ `TextbookScreen` 的两个 jiaocai1 端口传 `null`，屏只留一栏）；③ 检索结果里 `ssno` 恒 `null`（上游口径，要它进详情再补一枪） |
| 成绩 `JwappScoreScreen` | `GET /api/jwapp/scores?term=` + `/precise-scores` + `/score-detail?courseId=` | 学期成绩 + GPA 卡 + 选课算均分（两份上游在屏上按「学期 + 课程名」合并，与 `:data` / 桌面同一条 VM） | ① 「点开一门课看分项」那一栏：**§5.8 接线后 serve 形态下真能展开**（`/api/jwapp/score-detail`，`canFetchScoreDetail` 报 `true`）；campus-api 形态下仍是 `false`（那个上游没有这一条）⇒ 卡片点了不展开 —— 逐条见 §5.8 的消费方表；② 未评教名单与「按学号补的报表」两路（前者属评教那一侧、后者要学号 ⇒ 本端 `studentId` 传空串，VM 根本不调）；③ 落盘缓存（宿主能力，屏传 `cache = null`）；④ `precise-scores` 的 `502` **可容忍**：拿不到就只出 jwapp 那一份，**不让整屏失败**；⑤ `-1.0` 那个哨兵值在**服务端那一侧**就归一成 `null`（`:data` 的 `takeIf { it > -100 }`），消费方不再判一次、也不拿 `0` 冒充 |
| 快速考勤流水 `IclassfaceScreen` | `GET /api/iclassface/records?date=` | **整屏复用**：日期条 + 一天一张卡（时刻 / 地点 / 类型） | 姓名与学号（`name` / `studentNo` 不投影 ⇒ 空串；屏本来也不画那两格）；照片（上游根本没有这个字段）；⚠️ `time` 是**服务端时区的墙上时间**（上游原文，不是 ISO-8601）⇒ 消费方原样显示，**不** `parse` 成 UTC 再本地化（那会整体平移）；没有分页（上游一天一枪） |

四条相同的接线动作（逐条照 §5.5 那次接线做）：`:core/net` 的 `Campus*Api`（`ApiMode` 双形态，
非 serve 分支 `serveOnly(...)` 抛错而不是编空数据）· `WebSupport.openableRouteIds` ·
`AppPage` 的 `when (route)` · 屏顶部那条「如实缺口」说明。**一处都不能缺**
（缺任一处就是「屏在、门不在」或「拿不到的东西在屏上被编出来」）。

三个接线时定下来的细节（写在这里，免得下一个读的人当成 bug）：

1. **屏上的能力开关**：serve 形态拿不到的那几处不是「本端忘了画」，而是端口上的一格
   （`LmsSource.supportsDownload` / `supportsSubmissionList`、早就在的 `LmsSource.supportsVideoQueue`、
   `JwappScoreSource.canFetchScoreDetail`）；`TextbookScreen` 那边用的是「端口传 `null` = 本端没有这一档」。
   **默认值一律保持各端原行为**（Android / 桌面一行未变）。
2. **学堂的详情要多一个 `courseId`**：屏那一侧把列表里那一条当 `brief` 传下去（服务端就是靠它
   并 `deadline` 的）；拿不到 `brief` 时本端**不猜**一个课程号，直接抛错。
3. **考勤屏上的「快速考勤流水」入口**：那一屏本端接上了 ⇒ 入口照 App / 桌面画出来
   （不再是「本端没有这一档」那个 `null`）。

夹具与用例：`LmsFakeUpstream` / `Jiaocai1FakeUpstream`（它同时扮教材中心与全文库） /
`JwappFakeUpstream` + `JwxtFakeUpstream`（成绩的第二条上游在教务） / `IclassfaceFakeUpstream`
—— 四份都已存在（上一批只往 `LmsFakeUpstream` 提了两个常量 `USER_INDEX_NAME` /
`USER_INDEX_USER_NO`，值没变）。用例见 §6 的 `ServeLmsJiaocaiJwappIclassfaceTest`（8 例），
以及**客户端那一侧**的 `:core:jvmTest` `ServeLmsJiaocaiJwappIclassfaceApiTest`（9 例：逐字段形状、
错误信封只读 `message`、不投影的格如实留空、四个能力开关、非 serve 分支抛错）。


### 5.7 第三批（2026-10-14 落地）：付款码拿码 / 反馈读 / 匹配交友的消费统计 / WebVPN 纯转换

六个端点，四个 `:server` 路由文件（`PayRoutes.kt` / `FeedbackRoutes.kt` / `MatchRoutes.kt` /
`WebVpnRoutes.kt`），全部是「`:data` 里已有实现在取数」的**薄包装**（解析口径一行不重做）。
统一口径照 §5.2：信封 `{code,data,message}`、`code == HTTP 状态码`、
`401` =「请先登录：POST /api/session/login」、`400` 参数形状、`404` 未实现、
`502` 上游/网络故障（含会话失效那档）。

**会话**：这一批**一个新站点都没注册**（`ServeSession.SESSION_SITE_KEYS` 与 `:desktop` 那份清单
依旧逐字相同）——

- 付款码与消费统计两族借的是**校园卡**那个会话（`campus_card`，它早就在登录预热清单里：付款码的业务
  请求带的就是校园卡登录时存下的那张 JWT，`:app` 的 `AppRoute.PaymentCode.loginType = CAMPUS_CARD`
  也是这么定的）；
- 反馈与 WebVPN 两族**没有站点**：它们不认 `SiteSession`，所以**不套** `session.authenticated` ——
  浏览器带上门牌（令牌闸门）就能读 / 转。

| 端点 | 请求 | 响应 `data`（逐字段） |
|---|---|---|
| `GET /api/pay/code` | ——（不收参数） | `{barCode:string}` |
| `GET /api/pay/vouchers` | ——（不收参数） | `{count:int, vouchers:[{showCardId:string, voucherName:string, typeName:string, amountFen:long, endDate:string}]}` |
| `POST /api/pay/vouchers/select` | body `{showCardIds:[string]}`（**必填**，可为空表） | `{ok:bool, message:string}` |
| `GET /api/feedback/replies` | `?tickets=<逗号分隔，必填；一次最多认 20 个>` | `{tickets:[string], count:int, replies:[{ticket:string, text:string, time:string}]}` |
| `GET /api/feedback/public-qa` | ——（不收参数） | `{count:int, items:[{question:string, answer:string, category:string}]}` |
| `POST /api/feedback/submit` | body `{ticket:string, category:string, content:string, contact:string, anonId:string}` | `{ticket:string}` |
| `GET /api/match/dining` | `?from=&to=<YYYY-MM-DD>`（两个都可省） | `{from:string, to:string, scanned:int, truncated:bool, hourCounts:{<"0".."23">:int}, diningHours:string, canteens:[string]}` |
| `POST /api/webvpn/convert` | body `{url:string, direction:"to_vpn"\|"from_vpn"}` | `{direction:string, url:string}` |

**付款码（`pay`）：拿码不是付钱**

- 旧契约里那一行 `/api/payment-code`（P2「仍未实现」）**作废**：路径写死成 `/api/pay/{code,vouchers}`
  （与 §5 表里其余各族的段名规矩一致）；
- `barCode` ← `PaymentCodeApi.getBarCode()` —— 上游 `data.barCodeVo.barcode[0]`（**字符串**，20 位十进制）。
  `:data` 已经把「`success != true`」「少 `data` / `barCodeVo`」「数组为空」三档都判过了；
- 进门前先 `authenticate()`（`:core` 那条端口的「第一枪」）：它只**本地**看一眼会话里有没有那张
  校园卡 JWT，**不发请求、不改状态**；
- ⚠️ **这一串码就是能付钱的东西**（登录者本人的一次性凭据）：它不在 §5.2 那四个红线字段里，
  但 serve 的默认姿势（只监听回环 + 令牌闸门）是它的**前提**而不是可选项 —— 对外暴露
  （`--host 0.0.0.0`）时必须自己加反代 + 鉴权（§3.3 那条同源）；
- `vouchers` 是 ncard 的**可用加餐券**（与 §5.5 的 `/api/coupon*` 不是同一份：那一条打的是 `egc`）：
  五格逐一投影，`amountFen` 单位是**分**（与 §5.5 同一条口径），`endDate` 是上游原文的
  `2026-12-31`（它本来就是「到哪天为止」，不是 ISO-8601 的时刻）。`PaymentVoucher.serverFlag`
  （上游 `flag`）**不投影**：`:core` 的 `PaymentCodeScreen` 一处都没读它，多一格只会多一处要
  跟着上游漂的口径；
- ✅ **勾选同步已端（2026-10-15，批③-b / D7 ④）：`POST /api/pay/vouchers/select`** —— body 只有一格
  `{showCardIds:[string]}`（取自 `/api/pay/vouchers` 的 `showCardId`），成功回 `{ok:true, message}`。
  ⚠️ 那一格**刻意没有默认值**：缺了与「传了个空表」在写端点上不是一回事（后者是「取消全部勾选」）
  ⇒ 缺了、或那一格不是字符串数组，一律 `400` —— 一次笔误不该把用户的勾选全清掉；
  ⚠️ **空表这一档没有夹具用例**：`updateVoucherStatus` 不替上游判空（空表就照发），而夹具把空表
  当 `400`（它按自己读到的上游形状判）⇒ 真实上游认不认空表**本层未核实**，不编一句结论;
  加餐券勾选的**宿主落盘**（`:core` 的 `PaymentSelectionStore`）仍然不端：serve 这一层没有宿主
  磁盘 —— 浏览器自己那一份（localStorage）由 `:web` 接，两边各管各的；
- ⚠️ **「支付 / 扣款」这一档不是「本轮没端」，是上游客户端本来就没有**（D7 ④ 的结论）：
  `:data` 的 `PaymentCodeApi` 上只有 `authenticate` / `getBarCode` / `getVouchers` /
  `updateVoucherStatus` 四个方法，**一个扣款/支付动作都没有** —— 钱是商户扫了那串号之后在学校
  那边扣的，所以「只拿码」不是本轮砍出来的，是这一族**本来的形状**。要端「付款码支付」就得先自己
  造一个：自己实现扣款协议，再把它接上 —— 那是**新功能**（要真实的支付通道与合规），不是这次重构
  （§16）的事；
- 错误码：`401` 没会话 · `502` 上游/网络故障（含「校园卡未登录」那一档 —— `:data` 抛的是
  `RuntimeException("校园卡未登录，无法获取付款码")`，`FriendlyError` 按「应用自己写的中文短句」
  原样交回，与 §5.5 宿舍电费那条同口径）。**读的两条没有 `400`**（都不收参数）；只有 `vouchers/select`
  那一格形状不对才 `400`。

**反馈（`feedback`）：两读 + 一写（提交）**

- 照 `:core` 的 `FeedbackScreen` 实际读的那几样端：**自己提过的工单有没有回复**（`replies`）与
  **「大家在问」**（`publicQa`）；`tickets` 那一串工单号来自 `:core` 的 `FeedbackStore`
  （屏自己的本地账本）；
- `?tickets=` 是逗号分隔、**必填**（至少一个非空白）；**一次最多认 20 个**（= `:data` 里 `take(20)`
  那个上限，本层不另定一个数），多给的不发出去也不报错；空 / 全空白 ⇒ `400`；
- `replies` 是**数组**而不是对象（一个工单号只可能有一条：`:data` 用 `LinkedHashMap` 收敛），
  顺序 = 请求里 `tickets` 的先后；**没人回过的那几个工单不在数组里**（`count` 是「真取到几条」）；
  `text` 是 `:data` 的 `plainText` 把**富文本片段数组**（`[{"type":"text","text":"…"}]`）合成的
  那一串，`time` 是上游原文（那本来就不是 ISO-8601 的时刻）；
- ⚠️ `public-qa` 只出现上游表里**勾了「公开」**的行（`:data` 的判据），而且**绝不**退回整表拉取 ——
  否则未公开的回复会被所有人读到（`:data` 的 KDoc 把这一条写死了）；
- ✅ **提交也端了（2026-10-15，批③-b / D7）：`POST /api/feedback/submit`** —— 请求体就是
  `FeedbackSource.submit` 那五格（`ticket` / `category` / `content` / `contact` / `anonId`），一个不多；
  - **工单号由调用方生成**（`:core` 的 `FeedbackStore.newTicket()`，写进屏自己那份**本地账本**，用户
    回头就靠它查回复）⇒ 端点**收**它、校形状（`T[A-Z0-9]{8,16}`，即 `:data` 那一条正则）、**原样回**；
    不由服务端下发——下行的话屏还得先把服务端的号记进账本，那是另一个语义；
  - `contact` **允许空**（屏上那一格本来就可以不填）；`category` / `content` / `anonId` 空白 ⇒ `400`；
  - **`version` 不进请求体**：`submit` 那一格留空时由 `:data` 用宿主注入的 `Config`
    （`versionLabel` / `deviceLabel` / `osLabel`）顶上。serve 那一份 `Config` 是 `ServeFeedback` 按
    **这台机器**如实填的（版本 = `serve`、机型 = OS + 架构、系统 = OS + Java 版本）。
    ⚠️ **已知缺口**：所以经 serve 提交的那几条反馈，那三格描述的是**服务端那台机器**、不是浏览器 ——
    要让浏览器自报是另一个产品决定（要决定收哪几格、要不要信它），本轮不做；
  - ⚠️ **这一枪真写、而且是匿名可写**：反馈没有站点会话，能挡住滥用的只有令牌闸门 + 飞书那张表的
    写权限范围（§2 的权限设计）。`--host 0.0.0.0` 部署**必须**先加反代 + 鉴权（§3.3），并且**应当**
    在反代上给这个路径加请求体上限与频率限制 —— **本层没有自己的限流**（如实缺口）；
  - 成功：`200` + `{code:0, data:{ticket}, message:null}`；工单号**原样回**（判据前先 `uppercase()` 归一）；
- **凭据是运行期注入的**（这是这一族与别的族最不一样的一处）：反馈没有校园站点会话，「登录」是拿
  `app_id` + `app_secret` 换一张 `tenant_access_token`（见 `:data` 的 `FeedbackApi` KDoc）。两个
  窗口端的凭据是**构建期**烘焙的常量；serve 是部署在那台机器上的常驻进程 ⇒ `:server` 在启动时读
  环境变量 **`FEEDBACK_APP_ID` / `FEEDBACK_APP_SECRET` / `FEEDBACK_BASE_TOKEN`**（可选
  `FEEDBACK_TABLE_SUBMIT` / `FEEDBACK_TABLE_REPLY`；名字与 `desktop/build.gradle.kts` 那段
  `cfg()` 推导出来的逐字相同）注入给 `:data`（实现：`ServeFeedback`，在 `Main` 里调一次）。
  三个必填缺一个 ⇒ **如实「没配」**（`config = null`）⇒ 这三条端点按 `502` + 一句「去哪儿配」的
  中文短句报（**不是 404**：端点**在**，是这个部署没把凭据给它）；
- 错误码：`400` `tickets` 缺 / 空白 · `submit` 的请求体不是 JSON / 缺格 / 空白 / 工单号形状不对 ·
  `401` 无令牌（闸门那一条）· `502` 上游/网络故障，含「凭据没配全」那一档。

**匹配交友（`match`）：只端消费统计这一半（学工档案那一半拿不到）**

- 匹配交友屏要两样：**学工档案**（年级 / 专业 / 学院 / 书院 / 校区 / 班级 / 生源地，`:core` 的
  `MatchIdentity`）与**消费习惯**（「常在几点吃饭 / 常去哪个食堂」）。⚠️ **只有后者有源**：
  学工档案那条链是 `hello.xjtu.edu.cn` 的 `/yingxin/user/afterLogin`，实现
  （`HelloApi` / `HelloLogin` / `HelloProfileStore`）**只在 `:app`**（要 `Context`、要 `hello`
  站点的会话、头像还是 `Bitmap`），`:data` 里**一个 `hello` 字面量都没有**（`grep` 可证）；
  桌面端同一条缺口（`desktop/.../DesktopMatch.kt` 的 `identity()` 如实返回 `null`，并把那件事
  写成了一条 TODO）⇒ **`/api/match/profile` 这一轮不端**（服务端拿不到那份档案，编一份出来
  就是编数据；要端它得先把 `hello` 的取数与档案模型接进 `:data`）；
- `dining` 的取数是 `/api/card/transactions` **同一个源**（校园卡流水的窗口全表，按页拉到
  200 × 5 页封顶）—— 判据一条都不重做：小时 → 笔数、食堂名、阈值全在 `:core`
  （`DiningHabit.hourCounts` / `canteens`），`diningHours` 那一格更是直接由 `MatchProfile.build`
  的同一行式子给（该小时 ≥2 笔才置 1；一个 1 都没有时是**空串** —— 与分享码里那一格同义：
  这一维没数据、不进码）；
- ⚠️ **不含金额、不含商户全称、不含明细**（那是 `DiningHabit` 的 KDoc 写死的口径）：响应里只有
  小时 → 笔数、归并后的食堂名（「学苑食堂」而不是「学苑食堂三楼」）、以及覆盖了几笔；
- `?from=` 省略 = `to` 往前 **90 天**；`?to=` 省略 = **今天**（学校时区 Asia/Shanghai）；给了但不成形 /
  `from` 晚于 `to` ⇒ `400`；`scanned` 是**进统计的笔数**（窗口内的流水全表），`truncated` = 到页
  上限还没拉完（`true` 时那三格只覆盖了前几页，如实说）；
- ✅ **分享码那一半在浏览器端已可用**（2026-10-15，D12 ②）：这一族的终点是 `MatchProfile.encode` /
  `decode`，它要的两条缝（压缩、URL-safe Base64）原先在 wasmJs 上不存在（两个 actual 都如实返回 null）
  ⇒ `MatchProfile.codecAvailable == false`：**生成与解析都不可用**，`:web` 上落的是如实说明页
  （`WebMatchGapScreen`）。本批把两条缝补进 `:core` 的 commonMain（`platform/ZlibCodec.kt` /
  `platform/UrlSafeBase64.kt`，wasmJs 的 actual 转调它们）⇒ 三端 `codecAvailable` 都是 true，
  出码与解码在浏览器里真能跑（`:web:wasmJsBrowserTest` 的 `WebMatchShareCodeTest` 在**真浏览器**里钉住：
  Android 那一端编出来的真码，这一端解得回同一份档案）；
- ⚠️ **格式是 zlib 流 + URL-safe Base64**（本批顺手纠正了旧 KDoc 里「原始 deflate，无 zlib 头」那句错话：
  `new java.util.zip.Deflater(BEST_COMPRESSION)` 产出的是**带 zlib 头**的流 —— `78 DA` + deflate +
  4 字节 Adler-32）。两端的判据是**互相解得开**，**不是压缩字节相同**：浏览器那一份用固定 Huffman +
  贪心 LZ77（`ZlibCodec` 文件头有对照表），Android / 桌面那一份还是 `java.util.zip`（动态 Huffman）；
- 服务端**仍然不**代它生成码：码里含昵称与课表网格，是用户自己决定给谁看的东西（`MatchProfile`
  的类 KDoc 就是为这条写的）；
- 错误码：`400` 日期形状 / `from` 晚于 `to` · `401` 没会话 · `502` 上游/网络故障。

**WebVPN 转换器（`webvpn`）：纯转换，为什么放在服务端**

- 转换本体是 `:data` 的 `WebVpnUtil`（AES-128-CFB，key / iv 都是 `wrdvpnisthebest!`）—— `:core` 的
  `WebVpnConverterScreen` 认的那条缝 `WebVpnCodec` 就是它实现的（Android 与桌面注入的也是同一个
  对象），本层**一行算法都不重做**；
- **为什么不做成浏览器本地转换（这是这一族放在服务端的唯一理由）**：
  1. 浏览器能白拿的对称密码只有 `crypto.subtle`，而它**只在安全上下文里存在**：serve 默认是
     明文 HTTP，`--host 0.0.0.0` 时页面就**不是** localhost 了 ⇒ 非安全上下文 ⇒ `crypto.subtle`
     **直接被禁**（与 §3.5 里 `navigator.clipboard` / Service Worker 那几条同一类失效：
     「这条路根本不存在」而不是「慢一点」）；
  2. 退一步走纯 JS 的 AES：那要引一份第三方密码学实现（几百行，还得逐字节对上网关的 CFB 行为）——
     为一条**纯函数**的转换把一份密码学依赖塞进 wasm 产物，换来的是同一个算法在两处各写一遍；
  3. 而服务端本来就有这份实现（`:data` + JVM），端出去的成本与风险都是零：**纯函数**，不收会话、
     不落盘、**不改任何状态**、不碰任何上游；
- **2026-10-15（D12 ③）按事实收紧第一条**：`--https`（D11 批④，§3 第 6 条）之后「安全上下文」这个前置
  **可以满足**了，但这件事**仍然搬不过来** —— WebCrypto 里**没有 CFB 模式**（只有 CBC / CTR / GCM / KW），
  要用它得拿 `AES-CBC` 单块当 `AES-ECB`（IV 全零时 `E(P)` / `D(C)` 就是单块 ECB）再手写 CFB-128 循环：
  那是一份新的密码学实现，需单独授权。所以第二条与第三条理由照旧成立，**结论（放在服务端）不变**，只是理由更准：
- `direction` 只认 `to_vpn`（普通网址 → 网关地址）与 `from_vpn`（网关地址 → 原网址），大小写不敏感，
  响应里**原样回**（免得消费方猜这一次的语义）。已经是网关地址的输入、或者认不出的输入
  （没有 `://`），`to_vpn` **原样返回**（`:data` 的老口径，本层不改）；`from_vpn` 解不出来
  （不是网关地址 / 路径不成形）⇒ `400`（**不是** `data:null`：这不是「上游说没有」，是
  「你给的不是网关地址」）；
- 错误码：`400` `direction` 不认识 / `url` 空 / `from_vpn` 解不出来 / body 不是 JSON ·
  `401` 无令牌（闸门那一条）。**没有 `502`**：它不碰任何上游。

#### 消费方（`:web`）：**已落地**（2026-10-14 接线）

这一批的第三处（本文档 + `:testkit` 夹具 + `:web` 实现）已补齐：四个域各自有一个 `:core` 的消费方
客户端 + `:web` 的一条 `AppPage` 分支 + `WebSupport.openableRouteIds` 的一条门（**上一轮踩过的漏：屏在、
门不在**）。四条分支**三种落法**，逐条如下 —— 该真的真、该降级的如实降级：

| 屏（`:core`） | 本端真读了什么 | 如实不画什么 |
|---|---|---|
| 付款码 `PaymentCodeScreen`<br>（取数 `CampusPayApi`） | `GET /api/pay/code` + `/api/pay/vouchers` 两条都真读（屏上显示那一串**数字**，加餐券那几行（名称 / 金额分 / 到哪天为止）也真画）；`POST /api/pay/vouchers/select` 真写 ⇒ 加餐券那几行的**勾选框照旧画、勾了真同步**（`canSelectVouchers` 不再覆写） | ① **条码 / 二维码矩阵也真画**（D12 ①，2026-10-15）：`qrCodeModules` / `code128Modules` 的 wasmJs actual 转调 `:core` commonMain 里那份纯 Kotlin 编码器（`QrCodec` / `Code128Codec`）⇒ 屏上画的就是 Android 那一张（**逐模块相同**，由 `:core:jvmTest` 的 `CodeMatrixParityJvmTest` 钉住）；那一句「本端没有条码 / 二维码编码器」的降级文案仍在屏上，但三端都编得出码 ⇒ 走不到它；② **「支付」那一档本端也没有** —— 不是缺口，是上游客户端本来就没有那个动作（见本节）；③ 勾选那一次同步失败时屏上那句是 `:core` 自己的「同步失败」 —— 而本机那份落盘（`PaymentSelectionStore`，wasm 上是 localStorage）**照旧先写**，所以“已勾选”与本机记忆可能不一致（与 Android / 桌面同一条行为，不是本端改的） |
| 反馈 `FeedbackScreen`<br>（取数 `CampusFeedbackApi`） | **三条都真走**：`GET /api/feedback/replies?tickets=` + `/api/feedback/public-qa` 两条真读（「大家在问」真画；工单号来自**屏自己的本地账本**（`:core` 的 `FeedbackStore`，wasm 上是 localStorage —— 这一端没有别的账本）⇒ 账本里有单号时「我提过的」那几张卡与它们的回复也真画）；`POST /api/feedback/submit` 真写 ⇒ 屏上那块表单**照旧画**（`FeedbackSource.canSubmit` 是默认的 `true`，本端不再覆写成 `false`），提交成功那条工单也会记进本地账本 | ① **工单号仍然是屏生成的**（`FeedbackStore.newTicket()`，落在 localStorage）⇒ **换了浏览器 / 清了站点数据，旧工单就找不回了**（服务端不代管这本账，也不下发新号）；② 提交那一枪在服务端会**真写**进多维表格，而“已提交”这一态只能看本地账本的说法 —— 换一台机器看不到自己提过的那几条；③ 部署要配 `FEEDBACK_*` 三个环境变量，否则三条端点一律 `502`（含提交那一枪），而屏把取数失败吞掉 ⇒ 页面呈现为「没有新回复 / 没有问答」—— 这一条写在页顶那句缺口说明里；④ 这一族**不套会话**，本端没有「先登录校园账号」那一步 |
| 匹配交友 `MatchScreen` | **一个端点都没读**（这一屏一条请求都不发，与 Android / 桌面同一形状） | 2026-10-15（D12 ②）起在 `:web` 上是**真屏**（`:core` 的共享屏 + 本端宿主面 `WebMatchHost`）：出码与解码在这一端真能用（两条缝在 `:core` 的 commonMain），课表族缓存从 **localStorage** 来（与日程页同一份）⇒ 「离线算交集」是真算的。`WebMatchGapScreen` 那张说明页已删。两处如实降级（与桌面端**逐条同一个形状**）：① **学工档案**（年级 / 专业 / 学院 / 书院 / 校区 / 班级 / 生源地）本端没有 ⇒ 那一维在「分享哪些」里是禁用态（那条链是 hello.xjtu.edu.cn，实现只在 `:app` ⇒ 服务端也没有 `/api/match/profile`）；② **校园卡消费**（常在几点吃饭 / 常去哪个食堂）本端没有落盘 ⇒ 同样禁用。✅ **扫码那一档已经接上**（2026-10-16，D12 ③ 的收尾）：`scanner` 传的是本端的 `WebMatchScanner`，「从相册选图」（`<input type=file>` + `createImageBitmap`）与「扫一扫」（`getUserMedia` + `<video>`）两档在浏览器里**真能扫** —— 解码链（二值化 / 定位 / 透视 / Reed-Solomon）按 D12 ① 同一套做法搬进了 `:core` 的 commonMain（`platform/qr/`，逐行对照 zxing 3.5.4；那条缝是 `platform/QrDecode.kt` 的 `qrDecodeArgb`），本端只负责「拿到一帧像素」。**三处如实 + 一条已补齐**：① 「补定位块」那一档**没搬**（`:app` 为磨掉一角的桌贴留的）⇒ 那种码在这一端扫不出来；② **中日文那一档已经能解**（2026-10-18 补齐：码表进共享层 `platform/qr/QrCjkTables.kt`，**KANJI / HANZI 段**与 ECI 20/21/22/23/24/26/28/29/30 都通、与 zxing 逐字相同；仍不能解的只剩 ECI 12/16，那两档 zxing 也不认）；③ 非安全上下文时「扫一扫」会**如实说**为什么开不了（不画假画面）；④ 取景覆盖层是 DOM 画的（真 `<video>`），不是 Compose。⚠️ 本仓**没验过**的两件：`getUserMedia` 真流进来逐帧扫到（本机测试浏览器没有摄像头）与系统文件选择框那一条 —— 见 `docs/desktop-port-plan.md` §16.12。另：那几颗**复制按钮只在安全上下文里真能用**（不是这一族的缺口，是部署形态：见 §3 第 5 条）。⚠️ **`GET /api/match/dining` 仍然没有消费方**：它是聚合好的一发，本端读的是**本机缓存**（`MatchHost.transactions()` 空表），要接它得改宿主面的形状（不在本批） |
| WebVPN 转换器 `WebVpnConverterScreen`<br>（取数 `CampusWebVpnApi`） | `POST /api/webvpn/convert` 真能转（两个方向都真能）：输入一个网址、点「转换」拿到服务端那份 `WebVpnUtil` 算出来的地址；`from_vpn` 的 `400` 如实翻成 `null`（屏上那句「无法解析此 WebVPN 网址」），`401` 抛错（不把「没登录」说成「网址格式不对」） | ① 转换是**一发 HTTP**（要跨网络），而 Android / 桌面是本地纯函数 —— 屏上如实写这一条；② 「用 WebVPN 打开」那一跳只做浏览器做得到的那一件：新标签打开那个网关地址（网关自己的会话不在这个浏览器里，所以要过它自己的登录页）；③ 这一族**不收会话**，本端没有「先登录」那一步 |

接线的三处实现细节（下一轮照这个看）：

1. **webvpn 那条缝是新加的一条**：`:core` 的 `WebVpnCodec` 是**同步**的，而它还有第二个消费方 ——
   `:core` 的 `shareableUrl`（`:app` 的浏览器顶栏与桌面的外链中转页都在用，改成 `suspend` 会一路改到那
   两端的调用点）⇒ 本轮加 `WebVpnAsyncCodec` + `WebVpnCodec.asAsyncCodec()` 适配层，`WebVpnConverterScreen`
   出两个重载（Android / 桌面那两个调用点**一个字都没改**），`:web` 注入 `CampusWebVpnApi`；
2. **两个能力开关**（`PaymentCodeSource.canSelectVouchers` / `FeedbackSource.canSubmit`）默认值 = 各端原行为，
   形制与 §5.5 / §5.6 那几次接线一致（屏据开关不画那半，而不是画一个点了会报错的控件）；
3. **`AppRoute.Match` 也在 `openableRouteIds` 里**：清单管的是「点了会有个说法」，那一屏到底真能用还是
   如实说明，由 `AppPage` 那条分支自己说 —— 把门藏起来反而是上一轮那个「屏在、门不在」的老毛病。
### 5.8 第四批（2026-10-14 落地）：我课表三端点 / 成绩分项 / 教室座位数

五个端点，三个 `:server` 路由文件（`JwxtRoutes.kt` / `JwappRoutes.kt` / `ServeEndpointRoutes.kt`
里空闲教室那一段），全部是「`:data` 里已有实现在取数」的**薄包装**（解析口径一行不重做）。
决策来源：`docs/desktop-port-plan.md` §16.1 的 **D13**（2026-10-14 会议，用户拍板「一律端」），
施工序位是 §16.3 的第①批。

统一口径照 §5.2：信封 `{code,data,message}`、`code == HTTP 状态码`、`401` =「请先登录：POST /api/session/login」、
`400` 参数形状、`404` 未实现、`502` 上游/网络故障（含会话失效那档）。

**会话**：这一批**一个新站点都没注册** —— `changes` / `exams` / `textbooks` 打教务（`jwxt`）、
`score-detail` 打移动教务（`jwapp`），两个站点早就在清单里（后者是 §5.6 那一批加的）；
`seat-count` 读的是那份**免登录**的课表 CDN（与 `/api/emptyroom/cdn` 同一档）⇒ 它**不套**会话判据。

| 端点 | 请求 | 响应 `data`（逐字段） |
|---|---|---|
| `GET /api/jwxt/changes` | `?term=`（省略 = 当前学期） | `{term:string, count:int, changes:[{courseName,courseCode,kind,fromDay,fromStartSection,fromEndSection,toDay,toStartSection,toEndSection,toLocation,weeks:[int],toWeeks:[int]}]}` |
| `GET /api/jwxt/exams` | `?term=`（省略 = 当前学期） | `{term:string, count:int, exams:[{courseName,courseCode,examDate,examTime,location,seatNumber}]}` |
| `GET /api/jwxt/textbooks` | `?term=`（省略 = 当前学期） | `{term:string, count:int, books:[{courseName,textbookName,author,publisher,isbn,price,edition,courseCode,hasTextbook:bool}]}` |
| `GET /api/jwapp/score-detail` | `?courseId=<必填>` | `{courseId:string, detail:{courseName,coursePoint,examType,majorFlag,examProp,replaceFlag,score,scoreValue,gpa,passFlag,specificReason,itemList:[{itemName,itemPercent,itemScore,itemScoreValue}]}` 或 `detail:null` |
| `GET /api/emptyroom/seat-count` | `?room=<必填，教室名>` | `{room:string, seats:int}` 或 `seats:null` |

**我课表那三端点（`jwxt`）：与 `/api/jwxt/schedule` 的分工**

- `?term=` 的形状与那一档完全一样：`isTermCode`（`2026-2027-1`）不认就 `400`，**空白串 = 省略**
  （= 当前学期，与 `/api/jwxt/term` 同一个来源：`:data` 的 `AppSchoolCourseSource.currentTerm()`）。
  响应里那个 `term` 就是**实际去查的那个学期**（省略时是服务端定下来的那一个）；
- `changes` 端的是 `JwxtScheduleApi.changes(term)`（`wdkb` 的 `xskcb/xsdkkc.do`）—— 也就是 `:core` 的
  `JwxtChanges.apply(emptyList(), …)` 的**纯记录**那半，**不掺课表**。⚠️ 它**不是** `/api/jwxt/schedule` 的替代：
  那一发（`xskcb.do`）给的是**排课原样**，调课/停课/补课一条都不反映；两张表合起来才是屏上「今天这门课在哪上」。
  合并那一层（`JwxtChanges.apply`）在 `:core`，是共享代码 —— **服务端不合并**（合并要那份课表行，端一次就
  多一层「哪一份说了算」的口径，而且 Android / 桌面吃的就是共享层那一份）。⚠️ 但**消费方拿到这一条也合不了**：
  `apply` 吃的是 `JwxtChange`（带 `jxbid` 与 `appliedAt`），而本端点端的是说明面的 `ScheduleChangeEvent`
  —— 2026-10-14 接线时实测如此，`:web` 因此给的是**排课原样**（理由与处置见本节末的消费方表）。
- `changes` 的 `kind` 是 `MOVED` / `CANCELLED` / `ADDED` 三个**枚举名**（`:core` 的 `ScheduleChangeEvent.Kind`），
  `weeks` / `toWeeks` 是周次数组（`SKZC` 位串里为 `1` 的那几周，已排序）；停课没有 `to*`、补课没有 `from*`
  （那几格是 `0` / 空串，不编）；
- ⚠️ **教师名不投影**：`ScheduleChangeEvent` 上本来就没有那一格（`:data` 的 `JwxtChange` 带 `teacher`
  与 `appliedAt`，但它们没进事件）—— 调停补课报文里的 `YSKJS` / `XSKJS` 是「姓名/工号」，属 §5.2 红线的
  姓名那一类；夹具里明明有那个值，测试就是在那前提下断言响应里没有它；
- ⚠️ **取不到记录不是错误**：`:data` 的 `rawChanges` 把任何失败都吞成空表（“课表照原样给，比整张课表出不来强”）
  ⇒ 上游出问题时这里是 `changes:[]` 而不是 `502`。这一条是 `:data` 的**既有口径**，本层不改也不遮掩；
- `exams` 端的是 `examSchedule(term)`（`studentWdksapApp` 的 `wdksap.do`）：六格原样，其中 `examDate`
  （`2027-01-15`）/ `examTime`（`14:30-16:30`）是 `:data` 从上游那两个键里**剥好的原文** ——
  上游把日期与时段塞在同一格、还各带一遍对方，剥干净是解析的事（本层不做第二次）。
  ⚠️ 它与 `iclassface` 的 `time` 同一类：**不是** ISO-8601（那是“考试安排在什么时候”的原文，按 §4 那条例外处理）；
  行序 = 上游序（请求里带 `*order=-KSRQ,-KSSJMS`，最近的考试在前）；
- `textbooks` 端的是 `textbooks(studentId, term)`（帆软报表 `frReport2/show.do` 的 `jcgl/wdjc.cpt`）：
  九格 = `:core` 的 `TextbookItem` 八格（字段名一个不改）+ **`hasTextbook`**。
  ⚠️ `studentId` **从会话取**（`AccountContext.activeAccountId`，与 `/api/jwxt/grades` 同一处），它只进
  请求的 `xh=`，**不进**响应（§5.2 红线）；
- **`hasTextbook` 的语义**：`TextbookItem.hasSubstantiveTextbook`（`:core`/:data 的判据：书名不是「无教材」
  且有点实质信息）—— 报表里「无教材」那几行的**书名就是「无教材」**，那些行 `hasTextbook:false`。
  本层**不重写**那个判据，只把它如实报出来（消费方沿用 `ScheduleViewModel` 的用法：把没有教材的排在后面）；
  ⚠️ 那些行的 `isbn` 是报表给的**占位号**（`9780000000000` 那种），本层**原样投影**：
  滤它是 `:core` 的 `realIsbn` 那一处（屏上不显示），在服务端再写一份就是把同一个判据做成两处要同步的口径；
- 错误码：`400` `?term=` 不是学期号 · `401` 没会话 · `502` 上游/网络故障
  （`textbooks` 的报表链在 `:data` 里认得出「被重定向到登录页」那一档 ⇒ 会话失效也在这条 `502` 上）。

**成绩分项（`jwapp/score-detail`）：把「暂无分项成绩」如实端成 `null`**

- 端的是 `JwappScoreSource.getScoreDetail(courseId)`（移动教务的 `score/scoreDetail`，请求体就是 `{id}`）——
  屏上「点开一门课看分项」那一格。⚠️ **契约 §5.6 里那一行当年写的是「刻意不端」**，本批按 D13 把它端了
  （见本章末「与 §5.6 的关系」那一段）；
- `?courseId=` **必填**（取自 `/api/jwapp/scores` 里那一行的 `id`），缺 / 空白 ⇒ `400`；
- ⚠️ **「这门课没有分项」⇒ `200` + `detail:null`**，**不是** `502`、也不是 `404`：`:data` 把上游那几种
  「没有细则」的答法统一成了 `NoScoreDetailException`，那是**上游明确说没有**（§4：`null` = 上游明确说没有，
  字段缺失 = 本端不投影）。响应里 `detail` 这一格**显式是 `null`**（不是缺字段）；
- 消费方照 `:core` 的 `JwappScoreViewModel.loadDetail` 那条老规矩接：拿到 `detail:null` 就拿**它已经拿到的
  那一行**自己拼一份空明细（`ScoreItem.asEmptyDetail()`），而不是把它画成「加载失败」——
  两边走的是同一条分支；
- ⚠️ `itemPercent` 是**比例**（`0.3` = 30%）：`:data` 的 `JwappApi.getDetail` 已经把上游那个 `"30%"` 字符串
  除过 100 了（本层不除第二次）；`itemScore` 是原文、`itemScoreValue` 是它解析出的数字（解析不出来是 `null`）；
- 与 `/api/jwapp/scores` / `/precise-scores` 是同一族、同一站点（`jwapp`）的第三发：**三发都不合并**
  （合并口径在 `:core` 的 `JwappScoreViewModel`，与 §5.6 同一条）；
- 错误码：`400` `courseId` 缺 / 空白 · `401` 没会话 · `502` 上游/网络故障（含会话失效那档）。

**教室座位数（`emptyroom/seat-count`）：一个数字，拿不到就是 `null`**

- 端的是 `EmptyRoomSource.seatCount(roomName)`：点亮课表详情 / 日程里那一格「N 座」
  （`:core` 的 `ScheduleScreen` 读 `host.emptyRoom.seatCount(...)` 那个调用点）。
  它的取数与 `/api/emptyroom/cdn` **同一档**（`:data` 的 `EmptyRoomApi.getRoomSeatCount` 读的就是当天那份
  CDN 课表，免登录）⇒ 这条端点**不套**会话判据（没登录也答）；
- `?room=` **必填**（教室名，如 `主楼A-101`）：缺 / 空白 ⇒ `400`；
- ⚠️ **拿不到就是 `null`**，不是 `0`、也不是错误：`:data` 那边「名字对不上 / 当天 CDN 拉不到 / 那间房没有
  座位数」三种情况都回 `null`（`EmptyRoomSource.seatCount` 的 KDoc 把这条写死了 —— 调用点按 `null` 处理，
  屏本来就不画那一块）。§4：`null` = 上游明确说没有；拿 `0` 冒充会让屏上出现一排「0座」；
- 为什么单开一条而不是让消费方从 `/api/emptyroom/cdn` 自己挑：那一份是**整校区当天全部教室**（几百 KB），
  为一个数字拉它一次不划算；这条端点让「取一个座位数」在契约上是一个动作；
- 错误码：`400` `room` 缺 / 空白 · `502` 上游/网络故障（**没有 `401`**，见上面那条会话口径）。

**与 §5.6 的关系（本批把那条旧口径改写了）**

§5.6 的「移动教务成绩」那一节里，`getScoreDetail` 原本列在**「刻意不端」**里（当时的如实缺口，还带了
「下一批可以照 `?courseId=` 再开一条」这句 TODO）。本批按 D13 端了它 ⇒ 那一行已在本批**显式改写成
「§5.8 已端」**（照 §7 第 1 条：文档不能自相矛盾）。`unevaluatedCourseNames` / `reportedGrades(studentId)` /
`reAuthenticate` / `JwappScoreCache` 四项**仍未端**，理由逐条没变（学号那条是红线）。

#### 消费方（`:web`）：**已落地**（2026-10-14 接线）

五条端点里的四条进了 `:web`（**座位数 / 考试 / 教材 / 成绩分项**；`changes` 那一条读得到、但本端还
没有屏上消费方，见下表②行），三处同改齐了（本文档 + `:testkit` 夹具 + 实现）。接线动作照 §5.5 / §5.6 /
§5.7 那三次的同一条形：每个域一个 `:core` 的消费方客户端 + 宿主槽位 + 屏顶部那条如实说明：

| 屏（`:core`） | 用了哪个端点 | 真画了什么 | 如实不画 / 做不到（缺口） |
|---|---|---|---|
| 日程 `ScheduleScreen`<br>（取数 `WebScheduleSource`，新客户端 `:core/net` 的 `CampusJwxtScheduleApi`） | `GET /api/jwxt/exams?term=` + `GET /api/jwxt/textbooks?term=` + `GET /api/jwxt/changes?term=` + `GET /api/jwxt/schedule?merged=1`（**批①收尾**加的那一档） | 考试那一栏（`ExamItem` 六格：考试倒计时横幅 + 完整考试列表）、学期栏与课程详情里的教材那一栏（八格 +「有教材的在前」那条排序）、调停补课记录（`ScheduleChangeEvent` 十二格），以及**格子里的课**（`?merged=1` 那一档⇒已合过调停补课，与 Android / 桌面同一张表） | ① **接线当时那句「格子仍是排课原样」已于 2026-10-14 批①收尾收掉**（合并走 `?merged=1`，本端一行都没自己合 —— 逐条见 §5.9）；② `changes` 这一发**目前没有屏上消费方**（`:app` 里它是屁岱回答「最近调了什么课」的输入，而 web 的屁岱是降级页）—— 接口那一格照读，只是现在没人读；③ campus-api 形态下这三条端点**取不到**（本端只认 serve 契约里那一种字段面，同名路径在 campus-api 那一头的字段名不同 —— §7 第 2 条）⇒ 考试与教材两栏空着、格子仍是排课原样 |
| 日程详情里的「N 座」<br>（`ScheduleCapability.SEAT_COUNT`） | `GET /api/emptyroom/seat-count?room=` | 详情里那一格「N座」（`WebScheduleHost.roomSeatCount` → `CampusEmptyRoomApi.seatCount` 的 serve 那一支） | ① `seats:null` = 这间房查不到 ⇒ **不画那一格**（不写成「0 座」）；`seats` **字段缺失**是形状没对上 ⇒ 抛错；② 这条端点**不套会话**（与 `/cdn` 同档）；③ campus-api 形态下 `honored` 里**不列** `SEAT_COUNT`（本端只认 serve 契约里那一条、而那一形态下那份 `seatCount` 只剩「自己拉过的那一份当天 CDN」而课表详情页是另一个实例 ⇒ 列了就是假开关）⇒ 那一格不画；④ 本端不为一个座位数拉整校区那份 `/cdn` |
| 教材（日程的学期栏 / 课程详情） | 同上那一发 `GET /api/jwxt/textbooks?term=` | 见上一行：教材那一栏真画（含「无教材」排在后面） | ⚠️ **学号不由本端传**：契约里学号只进服务端的请求、不进响应（§5.2）⇒ 这条端点**不要**学号，本端为此在 `JwxtScheduleSource` 上多了一格 `textbooksWithoutStudentId`（默认 `false` = 老行为，`:app` / 桌面一行未改）—— 不读它，屏上那个「未获取到学号」的拦门会把「教材已接」变成看不见的效果；campus-api 形态下那一条仍按老样子拦（那一头确实没有这条） |
| 成绩 `JwappScoreScreen` | `GET /api/jwapp/score-detail?courseId=` | 「点开一门课」的分项（十二格 + 分项四格）—— `JwappScoreSource.canFetchScoreDetail` 在 serve 形态下翻 `true`，卡片点了真能展开 | ① 「这门课没有分项」= `200` + `detail:null` ⇒ 本端抛 `NoScoreDetailException`（与 `:data` 的 `JwappApi.getDetail` 同一个异常）⇒ 屏走「自己拼一份空明细」（`ScoreItem.asEmptyDetail()`）那条老分支，**不是**「加载失败」；② campus-api 形态下开关仍是 `false`（那个上游没有这一条）⇒ 卡片点了不展开；③ `itemPercent` 是比例：本端不除第二次（屏上乘 100）；④ 未评教名单与 `reportedGrades(studentId)` 两路仍未接（前者属评教那一侧、后者要学号，红线） |

三处接线时定下来的细节（免得下一个读的人当成 bug）：

1. **本批一个新屏都没加** ⇒ `WebSupport.openableRouteIds` 与 `ToolboxWebApp.kt` 的 `AppPage` 的
   `when` **两者都未动**（日程与成绩两屏早在清单与分支里，这一批接的是它们**屏内**的取数与能力开关）；
2. **`SCHEDULE_CHANGE_BUBBLE` 已翻（2026-10-14 批①收尾）**：那一条气泡在 `:app` 里是
   `InboxStore.post(OwnInbox.scheduleChange(…))`，而那个工厂当时**住在 `:app`** ⇒ 本端不列（`:desktop`
   至今不列）。本批把 `OwnInbox` **搬进了 `:core`**（包名与每一个 `id` 的形状逐字不变；`:app` 那侧
   只少了一个文件、引用一行未改，`InboxTest` 一句未动）⇒ `:web` 真能照着办：`honored` 里补上了它
   （**两种形态都列** —— `InboxStore` 在 wasm 上是 `localStorage`，首页那一格 `HomeCard.INBOX` 与
   消息收纳那一屏读的就是它），`WebScheduleHost.postScheduleChange` 与 `:app` 接成同一行。
   ⚠️ 它与「调停补课端点有没有」仍是两件事：气泡来自共享屏自己的课表比对（`ScheduleDiff`）；
3. **`SEAT_COUNT` 只在 serve 形态列**（见上表那一条）：列了做不到的就是假开关。

夹具与用例：`JwxtFakeUpstream`（新增教材报表那一枪：POST + `reportlets=[…]`，**既有常量一个没动**）、
`JwappFakeUpstream`（新增「这门课没有分项」那一档）、`EmptyRoomFakeUpstream`（**零改动** ——
`seatCount` 读的就是它早就有的那份当天 CDN 课表）；`changes` / `exams` 用的也是早就有的样本。
用例见 §6 的 `ServeJwxtChangesExamsTextbooksScoreDetailSeatCountTest`（**10** 例）。


### 5.9 批①收尾（2026-10-14 落地）：`GET /api/jwxt/schedule?merged=1` —— 已合并调停补课的那一份

**同一个端点，多一档参数**：不带 `merged` 的那一档**一字未改**（§5.3 那行，排课原样、契约面逐字冻结）。
决策来源：`docs/desktop-port-plan.md` §16.5 的「由复核带出来的、需要跟着修的接口缺口」第 1 条
（接线批如实不合并，缺口写在日程页顶部；用户拍板走「让 `/api/jwxt/schedule?merged=1` 直接端已合并的
那一份」，**不给说明面端点硬加字段**）。统一口径照 §5.2（信封、`code == HTTP 状态码`、`401` 指路
「先登录」、`400` 参数形状、`502` 上游/网络故障）。
**会话**：一个新站点都没注册（还是 `jwxt`，与 §5.3 / §5.8 同一份会话）。

| 端点 | 请求 | 响应 `data`（逐字段） |
|---|---|---|
| `GET /api/jwxt/schedule?merged=1` | `?term=`（省略 = 当前学期，与不带 `merged` 时同一条读法）+ `merged=1`（本档必给 `1`） | `{term:string, count:int, courses:[{courseName,teacher,location,weekBits,dayOfWeek,startSection,endSection,courseCode,courseType}], changes:[{courseName,courseCode,kind,fromDay,fromStartSection,fromEndSection,toDay,toStartSection,toEndSection,toLocation,weeks:[int],toWeeks:[int]}]}` |

- 端的是 `:data` 的 `JwxtScheduleApi.schedule(term)` —— **合并那一层就在 `:data`**（`JwxtChanges.apply`，
  搬迁前 Android 用的就是它）；本层是**薄包装，一行合并规则都不重写**；
- `courses` 是 `:core` 的 `CourseItem`，**九格**：`weekBits` 是位串（上游 `SKZC` —— `:data` 那条路用的
  就是它），`dayOfWeek`/`startSection`/`endSection` 是数字，`courseCode`/`courseType` 有值
  （非 `merged` 那一档的八列里没有这两格）。⚠️ `CourseItem` 上那两格分钟级时间
  （`startMinuteOfDay`/`endMinuteOfDay`）**不投影**：它们只有自建日程会填，教务行上恒为 `-1`
  （§4「字段缺失 = 本端不投影」）—— 消费方解回 `CourseItem` 时那两格自动落到同一个默认值，
  两端拿到的是同一张表，不是两个口径；
- `changes` 与 `/api/jwxt/changes`（§5.8）**同一族十二格**：同一个 `JwxtChanges.apply` 顺手算出来的
  事件（不是第二份解析、也不另发一次请求）；
- `count` = `courses.size`。行序 = `JwxtChanges.apply` 给的顺序（被拆出来的那几条先于新位置那一条）；
- 错误码：`400` `?term=` 不是学期号 · `400` `merged` 给了但不是 `1`（空白串也算「不是 1」；读法与
  `/api/jwxt/grades` 的 `all` 同一条 —— **不静默当成默认那一档**） · `401` 没会话 ·
  `502` 上游/网络故障。

#### 消费方（`:web`）：**已落地**（2026-10-14，本批）

| 屏（`:core`） | 用了哪一档 | 真画了什么 | 如实不画 / 做不到（缺口） |
|---|---|---|---|
| 日程 `ScheduleScreen`（取数 `WebScheduleSource.schedule` → `:core/net` 的 `CampusJwxtScheduleApi.mergedSchedule`） | `GET /api/jwxt/schedule?merged=1` | **格子里的课与 Android / 桌面同一张表**：调课 / 停课 / 补课都在（原周次被挖掉、新位置那一条也在）；教材与考试那两栏照旧（见 §5.8） | ① campus-api 形态下**没有这一档**（那一头不认 `merged`）⇒ 格子仍是排课原样，日程页顶部那行说明写明了；② 与取数无关的三处能力缺口（导出日历 ICS / 课程详情里的「思源学堂」与「本次考勤」/ 今日栏「接下来」的作业截止）**两种形态下都不画** —— 逐条见 `WebScheduleHost` 的 `honored` 那一段与页顶那行说明 |

页顶那条「如实说明」（`WebScheduleGapNote`）本批**收紧了**：serve 形态那一句不再是「格子仍是排课
原样」（已经合了），改成「与 Android / 桌面同口径」，并把两种形态下**同样做不到的三处**逐条留在
同一行里（不写的话，用户看着一张比 App 短的日程页只能猜）。

同一次提交里还收掉了 §16.5 的第 2、3 条（都与本节同源，处置见各自的节）：**教材中心屏的
「本学期我的教材」接上了**（`GET /api/jwxt/textbooks` 早已在 §5.8，本批补的是 `:web` 那一层映射
—— `:core` 的 `toJiaocaiTermTextbooks()`）与 **`OwnInbox` 搬进 `:core`**（`SCHEDULE_CHANGE_BUBBLE`
因此在 `:web` 上翻成 true，见 §5.8 末段第 2 条）。

夹具与用例：本批 `:testkit` **零改动**（`merged=1` 用的就是早就有的 `xskcb.do` + `xsdkkc.do` 两份
样本）；`:server:test` 新增 `ServeJwxtScheduleMergedTest`（**2** 例：真合并那一档 + 默认那一档行为
不变与 `merged` 形状 400），`:core` 新增 `ServeJwxtScheduleMergedApiTest`（**3** 例：解回
`SourceSchedule` / 参数读法 / 形状没对上与非 serve 形态抛错）。


### 5.10 批②（2026-10-15 落地）：`GET /api/accounts` —— 多账号页那一族的**只读**端点

**决策来源**：`docs/desktop-port-plan.md` §16.1 的 **D9**（「web = serve 进程，浏览器上切换账号 = 让 serve
换一套凭据」）+ 工单 **S7a**（`docs/storage-layout-plan.md`）。统一口径照 §5.2（信封、`code == HTTP 状态码`、
`401` 指路、`502` 上游/网络故障）。**会话**：一个新站点都没注册 —— 这一族**不打任何上游**，
它读的是本机账号表与 serve 自己的登录态（所以没有 `502` 那一档：没有上游可坏）。

| 端点 | 请求 | 响应 `data`（逐字段） |
|---|---|---|
| `GET /api/accounts` | ——（不收参数） | `{authenticated:bool, readOnly:true, accounts:[{id, accountType, lastUsedAt, active}]}` |

- **这一条答的是两个真相的合成**（两个都只读）：① 本机账号表 `xjtu_accounts`（`:data` 的 `AccountStore`；
  它**与桌面端是同一份文件** —— 同一个数据根、同一套账号后缀规则，见 `docs/agent-web-spec.md` 那一条）；
  ② **serve 这一侧的当前会话**（`AccountContext.activeAccountId`：登录成功或冷启动恢复时写入）。
  表里没有②时**补一条**放在末尾（它的 `lastUsedAt` 就是 `null`）—— 否则屏上会出现「已登录但一个账号也没有」；
  同一个学号两处都在时只出现**一行**（去重）。行序 = 表里原本的顺序，补的那条在末尾（屏自己会按
  「当前账号优先、最近使用优先」再排一次，与三端同一套）。
- ⚠️ **`id` 是打码后的学号**（`2023****0001`）：既是显示名也是句柄。**完整学号、姓名（`nickname`）、
  密码、设备指纹、RSA 公钥一律不投影**（红线 §3.4）。这不是 §5.2 例外清单里的新一条：它端的是
  **打码形式**（`maskedAccountId`：够长就留前 4 后 4、短一点留前 1 后 1），由 `ServeAccountsTest`
  钉住 —— 而夹具里那些值**明明都在**（「不是夹具没有所以没出现」）。
- `accountType` 是 `undergraduate` / `postgraduate`（`:core` 的 `AccountType.key` 那套写法）。
- `active` = **serve 这一侧的当前会话就是它**，**不是**账号表里的激活指针（那是桌面端的，serve 不写也不读它）。
- `lastUsedAt` 是 ISO-8601 带时区（§4）；`null` = 本端**明确说没有**（上面②那种补出来的行就是），
  **不编一个时刻**（先例：`/api/dorm/reading` 不编时间戳那一条）。
- `readOnly` 是**能力开关**（§4）：常量 `true`，见下。
- **不要求已登录**：未登录时 `authenticated:false`，账号表照旧列出来（那张表在磁盘上，与任何会话无关）。
  错误码只有 `401`（令牌闸门那一档：没带 / 令牌不对）。

#### 写侧（2026-10-20 落地）：`/api/accounts/{switch,add,delete,password}` —— **HTTPS 是硬前提**

**决策来源**：2026-10-19 会议 **#10 = 丙**（写侧全做；**HTTPS 变硬前提**）。
⚠️ 下面这段旧文字（「这一批**没有**写端点」）**已作废**，保留作历史记录：

> ~~D9 要的「浏览器上切换账号 = 让 serve 换一套凭据」，落到实现上就是 serve 自己的**登录态迁移**……
> 本批按工单的「只读优先」只端**读**那一半 ⇒ 四个动作**一个端点都没有**，`readOnly:true` 就是这件事的能力开关……~~

**现在的实现**（`server/.../AccountRoutes.kt`，2026-10-20）：

| 端点 | 方法 | 请求体 | 响应 `data` |
|---|---|---|---|
| `POST /api/accounts/switch` | POST | `{"id":"2023****0001"}` | `{ok, changed, message, snapshot}` |
| `POST /api/accounts/add` | POST | `{"username","password","accountType"?:"undergraduate|postgraduate","activate"?:false}` | 同上 |
| `POST /api/accounts/delete` | POST | `{"id","confirm":true}`（缺 `confirm` ⇒ **400**） | 同上 |
| `POST /api/accounts/password` | POST | `{"id","newPassword"}` | 同上 |

- `snapshot` = 动作之后那一瞬间的 `GET /api/accounts` 形状（客户端不用补发一次读）。
- `id` 接受**完整学号/手机号**或 `GET` 返回的**打码 id**；打码形式命中多条 ⇒ **409**（不猜）。
- **HTTPS 硬前提**：判据只有一个 —— `ServeModule` 的 `config.tls != null`（与既有的 `secureCookie` 同一先例），
  作为 `httpsEnabled` 往下传。每个 POST 的**第一句**先 `requireHttps`，**再**读 body ⇒ **明文链路上密码根本不会被收取**。
  拒绝形态：**HTTP 403 + `code:403`（`ApiErrors.HTTPS_REQUIRED`）**，文案里带可操作信息（`--https` 会自动生成
  `<数据根>/tls/serve.p12`，另附 `keytool -genkeypair … -storetype PKCS12` 的完整命令）。
  **读端点不受影响**；明文下 `readOnly = !httpsEnabled` 如实报 `true`。
- **幂等按目标态**（不使用 Idempotency-Key）：switch（「当前账号就是它」）/ add（「它在表里」）/ delete（「它不在表里」）/
  password（「密码已经是新的」）的重复提交一律 `ok:true` + `changed:false`；**add 同 id 异密码 ⇒ 409 指向 `/password`**；
  **绝不写第二行、不二次删、不二次清缓存**。四个动作共用一个 `Mutex` 串行执行。
- 删除**当前**账号 ⇒ `session.logout()`（先放会话再删，避免 debounce 把 TGC 写回）；删除**其他**账号 ⇒ `SessionBackend.wipe(suffix)`；
  改密且目标是当前账号 ⇒ 内存会话与落盘凭据一起替换（`ServeSession.rekeyAccount`）。
- **switch / add 不联网、也不写 `AccountStore.active_account`**（那是桌面端的主键，上文已写明 serve 不写不读它）。
- **add 不探活**：密码打错要到下一次取数才暴露（`401/502`）。理由：探活会触发短信二验，且违反「可重放」要求。

**消费方（`:web`）**：`WebAccountsHost` 的四个写方法已接上（2026-10-20）；未启用 HTTPS 时页顶与失败 toast 说明「要开 HTTPS」。

#### 消费方（`:web`）：**已落地**（2026-10-15，本批）

| 屏（`:core`） | 用了哪一格 | 真画了什么 | 如实不画 / 做不到（缺口） |
|---|---|---|---|
| 账号管理 `AccountManagerScreen`（取数 `:core/net` 的 `CampusAccountsApi.accounts()`） | `GET /api/accounts` | **与 App / 桌面同一份屏、同一套层级**（当前账号、账号列表、身份与「上次使用」都由那一份共享屏画）；打码 id 就是屏上的显示名 | ① 四个写动作这一端**一个都没有**（切换 / 新增 / 删除 / 改密）⇒ 页顶一行与失败 toast 如实指路到 App / 桌面端（见上）；② **campus-api 形态**下这一族不存在 ⇒ 落 `WebServeOnlyScreen`（「切到 serve 模式就能用」），不是一张空账号表；③ 头像那一格照旧 `null`（证件照缓存是 `:app` 的 `HelloProfileStore`，要 `Context` 与 Bitmap）⇒ 走首字母圆，与桌面端同一条 |

夹具与用例（§7 的第 1 条，三处同改）：`:testkit` 新增 `AccountsFixture`（两个**编造**账号 + 密码 / 姓名 /
指纹 / 公钥常量，纯字符串 —— `:testkit` 一个工程依赖都没有，装不了 `:core` 的 `Account` 对象）；
`:server:test` 新增 `ServeAccountsTest`（**3** 例：未登录且空表 + 闸门 401 · 登录态 + 表里一条别的账号
（打码逐字段、`active` 只标 serve 那一条、`lastUsedAt` 的 `null`、红线与「键集合」钉死）· 表里也有当前
会话那一条时合成成一行）；`:core` 新增 `ServeAccountsApiTest`（**3** 例：四格逐字段与 ISO → epoch 往返 ·
`readOnly` 缺了按只读 + 空 id 行丢掉 + 认不出的身份落回本科 · campus-api 形态抛错且**一个请求都不发**）。


### 5.11 批⑤（2026-10-15 落地）：`/api/agent/*` —— 屁岱（Agent）在浏览器上（D10，**S-a → S-d 四件全部落地**）

**决策来源**：`docs/desktop-port-plan.md` §16.1 的 **D10**；**设计真源 = `docs/agent-web-spec.md`**（438 行，
端点形状、SSE 十一类事件、工具白名单、三份落盘、与 `:app` / `:desktop` 的差异表、未决问题都在那里）。
统一口径照 §4（信封、`code == HTTP 状态码`、失败给能直接看的中文短句）。

**这一节记的是 S-a 的实况 + 后面三小批的续记**（工单把 D10 分成 S-a `:server` 端点 → S-b `:core` 三处小改 →
→ S-c 白名单工具层 → S-d `:web` 接线）：十四个端点 + SSE 已落地并被 **27** 用例钉住；`:core` 的「工具行文案 /
跳转建议 / 自愈历史」上提与 `:data` 的密钥半保护器随 S-a 一起动（S-a 的形状离不开它们）。

**S-b / S-c / S-d 都已落地**（2026-10-15 同一天的续三批）：`:core` 的 `AgentConfig.keyConfigured` + 配置面板那三个槽位
+ `AgentChatSession` 抽口（S-d 的前提），桌面的密钥半改用**与 serve 同一个保护器**（缺口 A 的桌面那一半），
白名单工具层那九件与 `/api/agent/config` 的 `toolAllowlist` 是**同一份定义**（逐条见下面那一段里改成事实的两条）。
**`:web` 接线（S-d）也已落地**：浏览器那一格现在是 `:core` 的 `AgentScreen` 本身（驱动是本端的
`WebAgentSession`：SSE 帧 → 状态；fetch/SSE 桥是 `@JsFun` 手写的那一段），`AppRoute.Agent` 从路由差集里去掉
（**5 → 4**），页面上那段「这一份部署能做什么」按 `toolAllowlist` **现生成**（白名单与页面说明同源）。
下面「本批**没有**做的」那一段已按事实重写 —— 它现在记的是 S-d 之后**还剩下的**那几件，不是「没做接线」。

**会话**：一个新站点都没注册（这一族**不打任何学校上游** —— 它打的是用户自己配的模型服务商，而且那一枪在
测试里一律是夹具）。

| # | 端点 | 请求 | 响应 `data`（逐字段） | 错误码 |
|---|---|---|---|---|
| 1 | `GET /api/agent/config` | —— | `{provider, model, effectiveModel, baseUrl, assistantName, searchEngine, thinkingEnabled, reasoningEffort, showReasoning, keyConfigured, searchKeyConfigured, toolAllowlist:[string], honored:[string], canEditKey}`；`searchEngine` 为 `deepseek` / `off` | `401` 无令牌 |
| 2 | `POST /api/agent/config` | `{provider, model, baseUrl, assistantName, searchEngine, thinkingEnabled, reasoningEffort, showReasoning, apiKey?, searchApiKey?}`；`searchApiKey` 是服务商不是 DeepSeek 时联网搜索单独用的 Key | `{ok, message}` | `400` 取值认不出 / 缺字段 · `401` |
| 3 | `POST /api/agent/models` | `{provider, baseUrl?}` | `{models:[string]}`（去重升序） | `400` · `401` · `409` 没配 key · `502` 服务商 / 网络 |
| 4 | `GET /api/agent/sessions` | —— | `{sessions:[{id,title,createdAt,updatedAt,locked}]}`，序 = `updatedAt` 降序 | `401` |
| 5 | `POST /api/agent/sessions` | `{title?}`（缺省 = `DEFAULT_TITLE`「新对话」） | `{session:{…}}` | `401` |
| 6 | `GET /api/agent/sessions/{id}` | —— | `{conversation:{messages:[…],lastTotalTokens,contextExhausted}\|null}`；`null` = 这条会话不在了 | `401` |
| 7 | `POST /api/agent/sessions/{id}/rename` | `{title, lock?:true}` | `{ok, message}`（对不存在的 id 是**空操作**） | `400` · `401` |
| 8 | `POST /api/agent/sessions/{id}/delete` | ——（幂等） | `{ok, message}` | `401` |
| 9 | `GET /api/agent/memory` | —— | `{items:[{key,value}]}`，序 = `AgentMemory.all()`；`__order` **不投影** | `401` |
| 10 | `POST /api/agent/memory/forget` | `{key}` | `{ok, message}`（`message` 是 store 的原话；`ok` = 原来有没有这一条） | `400` · `401` |
| 11 | `POST /api/agent/memory/clear` | —— | `{ok, message}` | `401` |
| 12 | `POST /api/agent/chat` | `{sessionId, text}` | **`text/event-stream`**（见下） | `400` 形状 · `401` · `404` 会话不在 · `409` 没配 key / 有一轮在跑 / 上下文耗尽 · `502` **只在流开始前** |
| 13 | `POST /api/agent/stop` | `{sessionId?}` | `{ok, message}`（没有在跑也 `200`，`ok:false`） | `401` |
| 14 | `GET /api/agent/status` | —— | `{running, sessionId:string\|null}` | `401` |

十四条**全部**挂在 `route(API_PREFIX)` 的令牌闸门里面（`ServeModule` 里一行 `agentRoutes(agent)`），
所以「没带令牌 ⇒ 401」是自动的 —— `ServeAgentTest.everyEndpointSitsBehindTheTokenGate` 把 14 条逐个走了一遍。
**这一族不要求已登录**：key 与循环都在 serve，而「没配 key」是一门明确的错（见下），不是 401。

#### `POST /api/agent/chat` 的流（`text/event-stream`）

响应头带 `cache-control: no-store` 与 `x-accel-buffering: no`（反代不缓冲）。一帧 = `event: <名>` + 一行
`data: <JSON>` + 空行；**保活**是 SSE 规范的注释行 `: ping`（每 15 秒一次，客户端必须忽略）。

**十一类事件**（与设计稿 §3.2 的表逐条对应）：

| 事件 | `data` | 何时 |
|---|---|---|
| `delta` | `{text}` | 模型正文增量，逐段 |
| `reasoning` | `{text}` | 思维链增量（仅思考开启时） |
| `tool_call` | `{name}` | 一次工具调用聚合之后、执行之前（**只报名字**，文案由消费者按 `:core` 的同一份表拼） |
| `tool_result` | `{name, success, error:string\|null}` | 该次执行结束（失败时 `error` 是原话） |
| `usage` | `{totalTokens}` | 模型回报 usage（只用来显示 / 兜底） |
| `mfa` | `{pending, siteName:string\|null}` | 校园站点短信二验挂起 / 结束（**只有站名，没有手机号**） |
| `widget` | `{widgets:[{type,json}]}` | 一轮工具执行全部结束后、`done` 之前（一次给全） |
| `nav` | `{routes:[{label,route}]}` | 同上（`route` 是 `AppRoute.id`） |
| `done` | `{content, sessionId, title, contextExhausted, totalTokens:long\|null}` | **唯一正常终止事件**，之后流关闭 |
| `error` | `{kind, message}` | **唯一异常终止事件**（`kind` = `session_expired` / `llm` / `tool` / `internal`） |
| `: ping` | ——（无 `data`） | 每 15 秒一次，直到流关闭 |

**开门阶段 vs 开流之后**：HTTP 状态码在流开始后改不了 ⇒ `400/401/404/409/502` 这些走**信封**（非 SSE），
流开始之后的失败一律走 `error` 事件。这一条与 §4 没有冲突：§4 管「响应长什么样」，这里是「开门那段是信封、
开流之后才是帧」——**不是** §4 的第三条例外。

**停止 / 断开是同一条路**：`POST /api/agent/stop` 取消这一轮；被取消（或客户端断开、或写帧失败）时
**一个终止事件都不发** —— 这一轮没跑完，说它「正常结束」（`done`）或「出错」（`error`）都是假话。
`stop` 自己是个普通端点，会如实回一句 `{ok, message}`（没有在跑时 `ok:false`）。

#### 三条要点（写在这里，免得下一个人重新推一遍）

- **红线：任何响应（含 SSE 每一帧）都不含 API Key** —— 不是掩码，是**字段不存在**。`keyConfigured` 是
  这一族唯一的「key 相关」投影（一个 bool）。同一族的红线还包括：`StoredMessage.images`（那是 **serve 那台
  机器**上的绝对路径，可能带用户名，而浏览器根本读不到那块盘）**不投影**、`llmHistory`（含工具结果与画像
  原文）**不投影**、MFA 帧里只有站名。`ServeAgentTest` 的每一条用例都在**夹具里明明有那把 key** 的前提下
  断言它不在响应里（并且有一条反过来钉住它确实落在 `agent_config_secure` + 账号后缀那份 store 里 ——
  未登录时后缀是 `default`，所以文件名是 **`agent_config_securedefault.properties`**，即设计稿 §0「缺口 B」
  那条口径的落点）。
- **两处 `409`**（`ApiErrors.ACTION_REJECTED`）：①「上一轮还在生成」（Android 是**静默忽略**，这里有意更清楚）；
  ②「还没配置 API Key」/「上下文已达上限」——三种情形的 `message` 各说各的，客户端只要显示它。
- **`404` 的用法扩了一处**：`chat` 的「会话不存在」用 `404` + 指名 message（`GET /api/agent/sessions/{id}` 仍用
  `200 + conversation:null`，照 `/api/library/my` 的 `my:null`）。§5 的 404 兜底（端点不存在）语义不变。

#### 两处**有意**偏离 `docs/agent-web-spec.md` 表格的地方（口径要记录，不许默默漂）

1. **会话与消息的时间是 epoch 毫秒，不是 ISO-8601**（§4 的通则在这里让位）：`createdAt` / `updatedAt` 是
   `:core` 模型 `AgentSession` 的 `Long` 字段、`StoredMessage.timestamp` 也是 ——浏览器那一端要把它们原样
   放回**同一个模型**（抽屉自己格式化）。转成字符串等于逼两端各写一遍转换，而这两格的使用者只有本族自己。
2. **`done` 里没有 `finishReason`**：`:core` 的 `AgentRunner` 只回正文
   （`finishReason` 用在它内部的截断 / 续写判据上，不外传），投影一个自造的值就是造事实 —— 按 §4「字段缺失
   = 本端不投影」处理。`done` 实际带的六格见上表。

#### S-d 落地之后的实况（哪几件成了、还剩哪几件没做）

- ✅ **白名单工具层（S-c）已落地**（2026-10-15）：`ServeAgent.forServe` 交给模型的是 `ServeAgentTools` ——
  设计稿 §6.1 那九件（`calculate` / `app_guide` / `preference` / `get_calendar` / `search_yellow_page` /
  `find_faculty` / `get_schedule` / `get_empty_rooms` / `get_exam_schedule`），其中课表族那四条是**逐字搬**
  `:app` 的 `AgentToolRegistry`（差异只有五处端上缝，由 `ServeAgentToolCopyDriftTest` 逐行钉住）。
  `GET /api/agent/config` 的 `toolAllowlist` 因此**如实是这九件** —— 它仍是**从真下发给模型的工具定义里读出来的**
  （不是另维护一份清单）。`tool_call` / `tool_result` / `widget` / `nav` 四类事件在真机上会出现。
  ⚠️ 没开放的那一串（写路径 / 钱 / 本人敏感 / 要站点会话 / 联网搜索）逐条理由在设计稿 §6.2，
  `ServeAgentToolsTest` 有一条用例把那串名字逐个钉住「不在工具表里」。
- ✅ **`:core` 的三处小改全部落地**（S-b）：工具行文案 / 跳转建议 / 自愈历史早在 S-a 就提到顶层
  （`AgentShared.kt`）；`AgentConfig.keyConfigured`（默认 `false`、**不进 `AgentConfigStore.save()`**）与
  配置面板那三个槽位（`enabled = config.isConfigured`、API Key 那一行可注入、「记住的偏好」那一块可注入）、
  以及 `AgentChatSession` 抽口（`AgentViewModel` 实现它，`AgentScreen(session = …)` 与 `AgentChat(vm = …)`
  都收这一条接口）在这一批补上。`:app` / Android 可见行为**逐字未变**（默认参数 = 现状）；
  桌面那一半的「缺口 A」也修了：`agent_config_secure<suffix>` 改用**与 serve 同一个** `protectedSecureKeyValueStore`
  （Windows ⇒ DPAPI 信封，Linux / macOS ⇒ 明文 `0600`，与今天逐字相同），盘上老的**明文**值原样读得回来。
- ✅ **`:web` 接线（S-d）已落地**（2026-10-15）：浏览器那一格现在是 `:core` 的 `AgentScreen` 本身（同一个屏、
  只换了驱动），三件新东西 + 一处清单：
  1. **`WebAgentSession`**（`web/src/wasmJsMain/.../web/WebAgentSession.kt`）：SSE 帧 → 状态（`AgentStreamState`：
     十一类逐类映射，与 `AgentViewModel` 逐条同判据 —— 50ms 合流、工具行换气泡、`done` 才收尾、
     「没见到终止事件就关流」= 已停止）+ 会话 / 配置 / 记忆那几发 HTTP（`code == 0` 才算成功，照 §4）；
  2. **`@JsFun` 的 fetch / SSE 桥**（`AgentSseBridge.kt` + 纯函数的 `AgentSseParser`）：**不走 Ktor 的 Js 引擎**
     （设计稿 §3.1 未核对 #2）—— `fetch` + `resp.body.getReader()` + `TextDecoder`，一帧一行的帧由 Kotlin 切；
     `AbortController` 按 key 存在 `globalThis` 里，切会话 / 新建 / 点停止都能真的取消；
  3. **`WebAgentScreen` / `WebAgentHost`**：面板两槽位按端上传（API Key 那是**只读徽标**、「记住的偏好」
     读的是 **serve 上那一份** —— §4.3 第 3 条的必改项）；屏顶上那段「能做什么」按 `toolAllowlist` 现生成；
  4. **路由那一处**：`AppRoute.Agent` 进了 `WebSupport.openableRouteIds`，路由差集 **5 → 4**
     （剩 `Browser` / `DownloadManager` / `Jiaocai1` / `Jiaocai1Reader`）。
  ⚠️ campus-api 形态下这一族**没有端点**（Key 与循环都不在它那一头）⇒ 那一格画的是 `WebServeOnlyScreen`
  （「切到 serve 模式就能用」），与只读四域的规矩同一条：**「本端有没有这一屏」与「此刻后端是哪一形态」是两件事**。
- ⚠️ **`@JsFun` 把 Kotlin 回调交给 JS 那件事，探针结论与一个盲点**（设计稿 §4.5 未核对 #3）：`:web:wasmJsTest`
  里的 `AgentJsFunCallbackProbeTest` 在**真浏览器**里验了两条 —— ① JS **同步**调用 Kotlin 回调、值真的落回来；
  ② 回调先被 JS 存进 `globalThis`、以后再由 JS 调用（**稳定的函数句柄**，能用第二次）—— 于是这一族用
  **回调推送**的形态，不需要退化成「JS 攒缓冲 + Kotlin 轮询拉」。
  ⚠️ **僵点**：③「`Promise` 微任务里回调」那一支**本测试源集验不了** —— `kotlin.test` 的 wasmJs 适配器不支持
  `suspend` 测试函数（编译器直接报错），而那一支天然是异步的。它靠 ①② 盖住的那件事 + 真浏览器里手点一次
  （`docs/serve-visual-checklist.md` ⑪ 那一行）来证明 —— 这里**不假装验过**。
- ⚠️ **两件没拍的（未决项照旧）**：① 「改上一条重发」在浏览器上只能做到「屏幕上换掉 + 再发一次」——
  契约里没有「替换上一轮」的入口（设计稿 §9 未决 #7），而那一颗钮是共享屏画的（逐字全等与「不画点了会失败的钮」
  在这里摊不开，要拍板才能收）；② 没配 Key 时输入栏上方那条提示仍写着「API Key 配好就能聊」
  （那是 `:core` 的文案，浏览器上应说「Key 在运行 serve 的那台机器上」—— 未决 #8）。
  两件都写在那张屏的页顶说明里，**页面不说假话**：说了这一端只能再发一次。
- **`GET /api/agent/sessions/{id}` 的 `messages[].widgets` 会带回来**（落盘形状），但**卡片的重建**在浏览器那
  一侧（`storedToWidget`，`:core` 现成的）—— 这一族只在 `widget` 事件里把同一批 `{type,json}` 端出去。

#### 夹具与用例（§7 的第 1 条「三处同改」的核）

- **`:testkit`**：**新增** `AgentFixture`（一把**编造**的 API Key + 型号 / 助手名 / 标题 / 记忆键值 / 回复正文 /
  工具名 / MFA 站名与一个**手机号常量**，纯字符串 —— `:testkit` 一个工程依赖都没有，装不了 `:core` 的对象）。
  这一族不打学校上游，所以夹具不是「页面原文」而是「一组编造的值 + 那把 key」：红线断言靠的就是
  「夹具里明明有它」这一点。
- **`:server:test`**：**新增** `ServeAgentTest`（**27 例**：配置三 · 型号两 · 会话五 · 记忆两 · 对话十一 ·
  闸门与尾卡两 · **生产装配一** · 纯函数一。十四个端点逐条 ≥2 例 —— 形状 / 闸门 401 / 400 / 404 / 409 / 502 各档、
  SSE 逐帧、红线、没配 key 的明确错误、并发 409、停止、上下文耗尽、MFA + 保活；最后一条把十一类帧的形状一次钉全）。
  夹具传输 / 工具表 / 起标题三格都在这个文件里（**绝不打真服务商**）；「生产装配一」那一条专门走
  `ServeAgent.forServe`（其余用例用的是测试自己拼的那份），并钉住匿名账号那份文件名（缺口 B）。
- **`:server:test` 又新增 8 例（2026-10-15，S-b / S-c 两批）**：`ServeAgentToolsTest`（**7 例**：纯逻辑三件一例、
  公开通讯录两件一例、`get_calendar` / `get_schedule` / `get_exam_schedule` / `get_empty_rooms` 各一例、
  「白名单之外不下发」一例）+ `ServeAgentToolCopyDriftTest`（**1 例**：把 serve 与 `:app` 两份源文件里同名函数的
  函数体逐行比一遍，差异只允许是清单里那 13 条端上缝，且每条必须真的用上）。上游全是 `:testkit` 早已存在的
  假上游（教务 / 智慧教室 / 空闲教室 CDN / 校历 / 黄页 / 教师检索），**不打真站点、不花一分钱**。
- **`:core:jvmTest` / `:data:jvmTest` / `:desktop:test` 各新增一例（S-b）**：`keyConfigured` 那一格（默认 `false`
  ⇒ 三端行为不变）、它**不进落盘**（存了也不写键）、桌面 `agent_config_secure` 的旧明文读得回 + 有保护器时落信封
  （拿一份夹具保护器把 Windows 那条路在 Linux 上就地钉住）。
- **`:web`**：本批（S-d）**新增 32 例**（`tests = 52`，基线 20：`AgentJsFunCallbackProbeTest` 2 ·
  `AgentSseParserTest` 11 · `WebAgentSessionTest` 13 · `WebAgentNoteTest` 6；`WebRouteCoverageTest` 那三条
  跟着改了预期（差集 5 → 4、`AppPage` 多出来的分支只剩 `Browser`））。其中 `WebAgentNoteTest` 把一个
  **原有的 S8 快照机制**多用了一次：`:server` 的 `ServeAgentTools.kt` 也在构建期读进快照 ⇒ 它能把「页面说明
  盖了每一件真下发的工具」当场对一次（换了名单不改页面说明就红）；`web/build.gradle.kts` 里那份清单多一行。


### 5.12 C-2 端点批（2026-10-20 落地）：考勤三栏 / 评教提交 / 成绩单两段式 / 作业截止 / 思源配对

**决策来源**：2026-10-19 会议 **B-3/B-11 = (c) 一次性全做**、**B-12 = (b)**、**① = (b) 显式两段式提交**、**B-5 = (a)**、**B-4 = (a)**。
统一口径照 §5.2（信封、`code == HTTP 状态码`、`401` 指路、`502` 上游/网络故障）。

| 端点 | 方法 | 参数 / 请求体 | 响应 `data` | 说明 |
|---|---|---|---|---|
| `/api/attendance/stats` | GET | `term` | 整学期按课程的考勤统计 | 缺勤按学期全量算（不用「本周」接口） |
| `/api/attendance/streams` | GET | `term` | 打卡流水（`id/location/collectTime/effective`） | 日期窗由学期起止推 |
| `/api/attendance/records` | GET | `term&from&to` | **新增投影 `teacher`** | **红线例外 ⑤ 只覆盖这一个字段、只在这个端点**（2026-10-19 批准）；请假族仍不投影任何姓名 |
| `POST /api/jwxt/evaluations/submit` | POST | `{term,type,wjdm,jxbid}` | `{submitted:bool}` | 自动填答后提交；**已在已评名单 ⇒ 200 + `submitted:false`（幂等）**；上游拒 ⇒ **409**；`/status` 的 `canSubmit` 改为如实 `true` |
| `POST /api/dzpz/transcript/submit` | POST | `{type?}` | `{created,requestId,…}` | **① 两段式第一步**：建申请单（**真的会在教务留一条待审批记录** ⇒ 消费方按钮文案必须写清）；**幂等**：10 分钟内重复点 ⇒ `created:false` 同一个 `requestId` |
| `POST /api/dzpz/transcript/preview` | POST | — | `{ok,filename,filesize,downloadPath}` | 第二步：生成预览 PDF + 取下载信息 |
| `GET /api/dzpz/transcript/download?ticket=` | GET | 一次性内存票据 | `application/pdf`（RFC 5987 文件名） | **契约第三条例外**（`application/pdf`）落在这里；**上游 host / link 永不出现在响应里**；票据重放 ⇒ 404 |
| `/api/lms/due` | GET | — | `{count,items[]}` | 作业截止：读 `:data` 的 `LmsDueStore` 落盘缓存，**不为作业发任何请求** |
| `/api/lms/course-match` | GET | `courseCode?/courseName?/teacher?` | `{matched,course|null}` | 思源课程配对；判据复用 `:model` 的 `lmsCourseMatched`（与桌面/App 同一份） |

**B-4 的说明（避免被当成漏做）**：日程屏那三格（考勤角标 / 思源下钻 / 本次考勤）在 web 上**不需要新端点** —— `/api/attendance/records` 已给 `week/startSection/date/status`，web 侧直接用 `:model` 的纯函数 `attendanceMarksOf` 建索引即可（匹配器已由 B-4 下沉到 `shared/model/.../schedule/CourseLinkMatching.kt`）。

**B-12 的如实结论**：**卡挂失上报做不了** —— 全仓只有只读的 `CampusCardApi`（读 `barflag`），`:app` 与 legacy 都没有挂失写的 URL / 参数；**猜上游违反规矩** ⇒ 需要一次**真实抓包**才能端。评教提交已端（见上）。

⚠️ **§5.5 里两句已过时**（以本节为准）：「考勤只投学期选择 + 流水表」（三栏已于 2026-10-20 补齐）、「请假族一律不端姓名」（2026-10-15 起真投影，见 §5.2 例外 ④）。

#### 消费方（`:web`）：**已落地**（2026-10-22，批5）

这一批的七个端点以前**一个客户端引用都没有**（端点先落地、屏没接），本批把它们全接上：

| 端点 | 谁在用（`:web` 侧） | 屏上变成什么 |
|---|---|---|
| `/api/attendance/stats` · `/streams` | `CampusAttendanceApi`（`:shared:network`）的 `computeCourseStatsFromRecords` / `getKqtjCurrentWeek` / `getKqtjByTime` / `getStreams` | 考勤屏的「统计」与「打卡流水」两栏**真画**（`AttendanceCapabilities` 三项都 `true`） |
| `POST /api/jwxt/evaluations/submit` | `CampusJudgeApi.judge`（serve 形态下 `canSubmit = true`） | 评教屏那个「一键评教」块**真能提交**（屏标题从「学生评教（只读）」改回「本科评教」）；上游拒 ⇒ `409` 被屏按「这一门失败」计数，不中断剩下几门 |
| `/api/lms/due` | `CampusLmsApi.dueHomeworks` → `WebScheduleHost.dueHomeworks` | 日程页今日栏「接下来」的作业截止 |
| `/api/lms/course-match` | `CampusLmsApi.courseMatch` → `WebCourseLinkAccess.lmsCourseFor` | 课程详情里的「思源学堂」入口 |
| `/api/attendance/records`（已有） | `WebCourseLinkAccess.attendanceMarks` + `:model` 的 `attendanceMarksOf` | 课表格子上的**考勤角标**与详情里的「本次考勤」（后者还看设置里「课表显示考勤角标」那一格，与 App/桌面同一个键） |

三处**如实差异**（都不是「还没做」）：

1. **统计那一格是 `suspend` 了**：算路在 `:data`（只有 JVM/Android 变体），浏览器里拿不到它 ⇒ 把
   `AttendanceSource.computeCourseStatsFromRecords` 改成 `suspend`，serve 那一份打 `/api/attendance/stats`
   （服务端跑同一条算路，数字与拿那份流水自己算出来的逐格相同）。本机那两份实现只是加一个关键字，语义一字未改。
2. **两条端点只认学期号**（不认一对日期）：`stats` / `streams` 的 `?term=` 由 serve 侧算好的学期号填
   （`/records` 那一发回显的 `term`，已缓存）；屏上那两个日期参数是它用来算学期窗口的。
3. **下钻取数失败 = 那一格不画**：屏上这两处在 `LaunchedEffect` 里裸调（`:data` 那一份自带站点级
   失败冷却、不会抛），所以 `WebCourseLinkAccess` 自己吞掉错误回 `null` —— 「没配到课」与「这一发没取到」
   在屏上是同一种表现：不给入口（给错课比不给更糟）。

四格在 serve 形态下才算「有」（`ScheduleHost.honored` 按形态给：考勤 / 思源下钻 / 作业截止三档只在 serve 里）
—— campus-api 那一形态没有这三族端点，屏照旧不画，日程页顶那条缺口说明也**按形态分了两段**。

**用例**：`:shared:network:jvmTest` 新增 `ServeC2EndpointsApiTest` **5** 例（`MockEngine`）——
`due` 八格逐字段（`fetchedAt` 的 ISO 真转成毫秒、`null` 回 0）· `course-match` 配上 / 没配上两档 +
三个参数进 query · 评教提交的请求体四格、已被评过也是成功、`409` 是异常且文案原样带上 ·
两个 campus-api 形态的闸门。另：`ServeReadonlyFourDomainsApiTest` 里考勤那一例改成**逐字段核**统计八格
与流水四格（原来那句「统计不端 ⇒ 空表」已不成立）。

#### 消费方（`:web`）：**成绩单两段式**（2026-10-22，批6）

`CampusTranscriptApi`（`:shared:network`）以前只填得了第一步（`canRunWorkflow = false`）；现在把三发 HTTP
映射到屏上那七步（屏与 ViewModel 一行未改）：

| 屏上的步骤 | serve 里对应什么 |
|---|---|
| Step 1 加载表单 | `GET /api/dzpz/transcript`（§5.5，未变） |
| Step 2 学籍联动 | **空壳**：服务端在建单那一发里自己跑（`DzpzRoutes` 的 `submit` 那一支），而它返回值里有**学号** —— 红线、例外清单里没有它 ⇒ 本端后续几步都不读它（屏上也一格不画） |
| Step 3 保存申请 | `POST /api/dzpz/transcript/submit`（第一段；幂等：`created:false` 也成功） |
| Step 4 生成文件 | `POST /api/dzpz/transcript/preview`（第二段：生成 + 签章 + 取下载信息一口气做完，回一张一次性票据） |
| Step 5 签章 / 第二次提交 | **没有单独的端点**（就在上一发里）⇒ 原样交回上一步的结果，不发请求 |
| Step 6 取下载链接 | 交回 `preview` 存下的三格（`filename` / `filesize` / `downloadPath`）—— 上游 host 与 link 永不出现在响应里 |
| Step 7 下载 PDF 字节 | `GET /api/dzpz/transcript/download?ticket=`（`application/pdf` 那条例外） |

两处**如实差异**：

1. **Step 7 先看状态码与内容类型**：失败那一半回的是**信封（JSON）**（票据过期/重放是 `404` + 一句中文）——
   不把一段 JSON 当 PDF 交上去（不然屏上会「成功」地存出一个假的 .pdf）；
2. **保存交回给浏览器自己下**（`:web` 的 `WebTranscriptSaveSink`：`Blob` + 下载锚点）：目标位置是浏览器的
   下载目录、本端不知道它在哪 ⇒ 屏上那行「将保存到 X」不画（与 `WebArchiveActions` 同一条路子）。

⚠️ **第①步真的会在教务留一条待审批记录**（契约 §5.12 的要求：消费方按钮文案必须写清）—— 屏上那句
缺口说明与这个按钮的语义都在 `:web` 的 `AppRoute.Transcript` 分支里写着。

`:web` 的 `AppRoute.Transcript` 现在画的是与 App / 桌面**同一份屏、同一条链**；campus-api 形态仍只有第一步
（`canRunWorkflow = false`，屏上画缺口卡）—— 两种形态在 `AppRoute` 那一支里分开。

**用例**：`:shared:network:jvmTest` 新增 `ServeTranscriptWorkflowApiTest` **5** 例（`MockEngine`）——
七步的两段式映射逐发核（包括「Step 5 不发请求」「请求体就一格 type」「票据与 ticket 参数」）·
幂等命中（`created:false`）也成功 · 票据过期（`404` 回信封）要报错而不是交回 JSON · 上游拒 `409` 的文案 ·
campus-api 形态的闸门。另修 `ServeReadonlyFourDomainsApiTest` 里那一例（它原来钉着「serve 只端了第一步」，
现在钉的是相反的事实）。**真浏览器里核了字节读**：`:web:wasmJsBrowserTest` 新增 `WebServeBytesTest`
（`bodyAsBytes` 在 Chrome 里真读回四格字节 —— 这条缝以前只编过没跑过）。


### 5.13 下载那一族（2026-10-20 落地实现 · 2026-10-21 补契约测试与本节）：`/api/downloads`

**决策来源**：`docs/parity-ledger.md` §6.1 会议 **#9 = 丙「做法①」** —— 浏览器里没有「App 管的下载队列」
这一回事（用户点链接是浏览器自己在下，serve 不知情）。所以这一族**不是**「把登记表端出来给人看」的影子版，而是：

> **serve 进程里装真的下载引擎**（`:feature:lms` 的 `DownloadManager` + `:data` 的 `JvmDownloadEngine` /
> `JvmDownloadFileSystem`，与桌面窗口模式同一套），**浏览器只当界面**。

于是队列、进度、删除、落盘（落到**那台机器**的 `~/Downloads`）都是真的。统一口径照 §5.2（信封、
`code == HTTP 状态码`、`401` 指路、`409` 上游/本层明确拒绝一次写动作、`502` 上游/网络故障）。

| 端点 | 方法 | 请求 / 参数 | 响应 `data` |
|---|---|---|---|
| `/api/downloads` | GET | —— | `{count,downloading,completed,active,dir:{location,notes[]},tasks:[…]}` |
| `/api/downloads` | POST | `{url, cameraType?, courseName?, activityTitle?, activityId?}` | `{ids:[int]}`（登记表的自增主键） |
| `/api/downloads/progress` | GET | —— | `{downloading,active,completed,tasks:[…]}`（**只列活跃任务**，屏上轮询它） |
| `/api/downloads/{id}` | DELETE | `?deleteFile=false` 只删记录 | `{id,deletedFile}` |
| `/api/downloads/{id}/pause` | POST | —— | `{ok:true,id}` |
| `/api/downloads/{id}/resume` | POST | —— | `{ok:true,id}` |
| `/api/downloads/{id}/cancel` | POST | —— | `{ok:true,id}` |

一条任务在列表里（`tasks[]`）十二格：`id` · `title` · `courseName` · `cameraType` · `filePath` · `fileSize` ·
`downloadedBytes` · `progress`(0~1) · `status` · `statusLabel`(中文短句) · `createTime`(ISO-8601) · `errorMessage`。
进度那一条的一行八格：`id` · `title` · `downloadedBytes` · `totalBytes` · `progress` · `status` ·
`statusLabel` · `errorMessage`。

**进门条件（2026-10-21 补的）**：四条都**要求已登录**（未登录 `401` 指路）。上一轮落地时只挂了令牌闸门，
后果是：拿到令牌就能让**那台机器**去取任意 URL 并往操作者的下载目录里写文件；而且
`AccountContext.activeAccountId` 还是 null 时，登记表会落到 `serve_download_tasks_default` 那个命名空间。
本批与其余二十多族拉齐。

**本批顺手修的三处**（都是上一轮没测试所以没暴露的，逐条可核）：

1. **路由挂错了**：原本写的是绝对路径 `/api/downloads`，而这棵子树已经挂在 `route("/api")` 之下 ⇒
   实际注册成 `/api/api/downloads`，**整族在线上也是 404**。现在改挂相对段（与 SessionRoutes / AccountRoutes 同一条规矩）。
2. **回的是裸对象**：四条都直接 `respond(DTO)` ⇒ 没有 §4 的信封。本批全部包上 `ApiEnvelope.ok(…)`
   （这一族**不在** §4 的例外清单里）。
3. **删「正在下载」那一条是静默空操作**：`:data` 的 `deleteTask` 只在非 `downloading` 时真删，而旧代码照旧回
   `200` + 成功形状 ⇒ 屏上点删除看起来成功、那一行还在。现在**回查一次**再答：行还在 ⇒ `409` +
   「这条任务还在下载中，删不掉」（不谎报成功）。

**如实边界**：

- 登记表用**自己的店名**（`serve_download_tasks<账号后缀>`，见 `ServeDownloadTaskStore`）⇒ **与桌面窗口的列表不共享**
  （共用一个文件时两边的手工编码一旦不一致，就会「对方写的行读不出来 ⇒ 下一次整份回写把它抹掉」= 静默丢记录）；
- 引擎只支持**可直接 GET 的直链**：HLS（`m3u8`）在这一层就拒（`400`，不交给引擎去失败）；
- `dir.location` 是**运行 serve 那台机器**的下载目录（不是浏览器所在的设备）—— 那三条 `notes` 就是要把这件事说清；
- **七个端点**（2026-10-21 晚补齐三个动作动词）：暂停 / 恢复 / 取消 —— 桌面那一屏本来就直调 `DownloadManager`
  的这三个方法，web 的下载管理屏要用就得有端点。两条口径：
  - **`{ok,id}` 不回状态**：`:data` 的 `resumeDownload` 只丢一个协程（那一刻状态还没变）⇒ 报状态就是编事实；
    屏上照旧轮询 `/progress` 或重拉列表（与桌面那一屏同一步）。
  - **与 `DELETE` 的分工**：`DELETE` 是「删掉就没了」（幂等，200）；三个动词是「**对这一条做一件事**」
    ⇒ 目标不在表里、或状态不允许时一律 **`409` + 中文短句**（不谎报成功）。两处状态闸门是必要的：
    `:data` 的 `pauseDownload` **不看当前状态**（拿一个已完成的任务调它会把那一行改回「已暂停」，
    之后 `resumeAll` 还会把它重新下一遍）；同理对已完成的任务调 `resume` 会把那一集重下一遍。
    可暂停 = `downloading` / `pending`；可恢复 = `paused` / `pending`；取消任意状态都行。

#### 消费方（`:web`）：**已落地**（2026-10-21，批 3）

屏就是 `:core` 那份 `DownloadManagerScreen`（三端同一份），但**屏不再直接收一个具体的 `DownloadManager`**
—— 那一份是「本机引擎 + 本机登记表」的装配，浏览器里造不出来（会议 #9 又明说**不做影子版**）。
所以抽了一层窄接口 **`DownloadsHost`**（`:feature:lms`，`media/DownloadsHost.kt`）：

| 谁 | 实现 | 说明 |
|---|---|---|
| Android / 桌面 | `DownloadManager` 自己（`class DownloadManager(...) : DownloadsHost`） | 语义一字未改；只有 `resumeDownload` / `resumeAll` 声明成 `suspend`（它们本来仍是「丢一个协程就返回」），另加一个 `changes`（把真进度流摊平） |
| web | `WebDownloadsHost`（`:web`，走本节的 `/api/downloads`） | 取数 `ServeDownloadsApi`（`:feature:lms`，与它的模型同模块） |

三处**如实差异**（屏上按宿主能力画，不画假按钮）：

1. **没有推送** ⇒ `changes` 是**轮询**（5 秒一发；契约给的就是「屏上轮询」那一档）；
2. **目录那一格要等第一次列表回来**（它来自响应的 `dir`）⇒ `videoDirPath` 在首次列表前是空串
   （屏进页就会拉一次，所以弹窗里实际看不到空的）；
3. **不画播放按钮**（`onPlayTask = null`）：文件在**运行 serve 那台机器**上，浏览器打不开它；
   同理 `files = null`（没有本机下载目录那一组）。

另加一条**错误通道**：`DownloadsHost.notices`（默认空流）—— serve 那一份用它报 `409` 与网络故障，
屏收到就弹一张一次性提示（`DownloadNoticeDialog`：「没做成」+ 中文短句）。
**本机那一份永远不发**（它没有会失败的远端动作），所以那一张弹窗在 App / 桌面上永远不会出现。

⚠️ 三个「全部」动作（全部暂停 / 继续 / 取消）**没有批量端点**（桌面那一屏本来也是本机循环调的）⇒
web 那一份逐条调：好处是**失败只影响那一条**，坏处是 N 发请求。要不要开批量端点，留待下一步拍。

**夹具与用例（2026-10-21）**：`:server:test` 新增 `ServeDownloadRoutesTest` **5** 例（进门条件七条逐条 401 +
列表与目录那一格 + 新建→下载中→完成→删除的完整状态机 + `deleteFile` 开关与幂等 + 请求体与 `m3u8` 形状 +
三个动作动词的完整往返（暂停 → 恢复 → 取消）与两处 409（目标不在表里 / 状态不对））。
它**注入一份可控的 `DownloadManager`**（内存版登记表 + 卡在闸门上的引擎 + 临时目录版的落盘端口）：
生产装配里的引擎会真发 HTTP、落盘端口会往操作者的 `~/Downloads` 写文件 —— 契约测试不该碰这两样。
**客户端那一侧**：`:feature:lms` 新增 `ServeDownloadsApiTest` **4** 例（`MockEngine`）—— `list` 逐字段
（十二格 + 三个计数 + 目录那一格，且 **`videoUrl` 不投影**、`createTime` 的 ISO→毫秒真转对了）·
`enqueue` 的请求体就是契约那几格 · 三个动词走 `POST` 子路径、删除走 `DELETE` 带 `deleteFile`，
且 **`409` 是结果不是异常**（`ok=false` + 中文短句原样交回）· campus-api 形态一律 `serveOnly`。
`:web` 那一侧：`WebRouteCoverageTest` 的差集从 4 条降到 **3** 条（`DownloadManager` 出差集 ——
「门在、屏不在」那一类少一条），`wasmJsBrowserTest` 69 例全绿。
夹具那一处：`:testkit` **零改动**（这一族不打上游）。

### 5.14 教材全文库那一族（2026-10-21 落地）：`/api/jiaocai1`

教材全文库（`jiaocai1.lib.xjtu.edu.cn`）。与其余二十多族同一条做法：**`:data` 里早有取数实现**
（`Jiaocai1Api` / `Jiaocai1PageLoader`，Android 与桌面直连在用），本层只做投影。

**会话**：借的是**教材中心**那个站点（`jiaocai`）—— 全文库的会话由 `jiaocai.lib` 那一侧的
`portalLogin` 桥接过来（`:data` 的 `PortalRedirect`），与桌面 `DesktopSiteGate(JIAOCAI_SITE_KEY)`
走的是同一条，**一个新站点都没注册**。

| 端点 | 方法 | 请求 / 参数 | 响应 |
|---|---|---|---|
| `/api/jiaocai1/classify` | GET | —— | `{count,nodes:[{id,name,level,parentId,nodeId,children:[…]}]}` |
| `/api/jiaocai1/search` | GET | `keyword` 与 `cls` **至少给一个**；`field`（五选一，默认 `bookName`）；`page` | `{keyword,field,cls,page,totalRows,currentPage,totalPages,count,books:[…]}` |
| `/api/jiaocai1/book` | GET | `ssno` **必填** | `{ssno, book:{title,pageRanges[8][2],bookPages,pageCount}\|null}` |
| `/api/jiaocai1/page` | GET | `ssno` + `index`（阅读器页序，**从 0 起**） | `image/jpeg` 字节（**§4 第四条例外**） |
| `/api/jiaocai1/cover` | GET | `url`（只认全文库自己的域名） | `image/jpeg` 字节（同上） |
| `/api/jiaocai1/shelf` | GET | —— | `{count,rows:[…]}`（八格，见下） |
| `/api/jiaocai1/shelf` | POST | `{ssno,title,author,coverUrl,totalPages,lastReadIndex,lastReadAt?,addedAt?}` | 同上（整份） |
| `/api/jiaocai1/shelf/progress` | POST | `{ssno,lastReadIndex,totalPages,at?}` | 同上（整份） |
| `/api/jiaocai1/shelf/{ssno}` | DELETE | —— | 同上（整份） |

**分类树的节点就是 `:core` 的 `Jiaocai1Category`**（那个模型本来就是 `@Serializable`）⇒ 零转换、零漂移；
`id` 即检索参数 `cls`。**一本书八格**：`ssno` · `title` · `author` · `publishDate` · `themeWord` ·
`callNo` · `classifyPath` · `coverUrl`（要字节就打 `cover` 那一条）。

**`book` 只回页表那一半**：`pageRanges` 是 8 种页型各自的 `[起, 止]`（`起 > 止` = 这本书没有这类页），
顺序与 `:core` 的 `Jiaocai1Paging.TYPE_NAMES` 逐位对应；`pageCount` 是摊平之后的总页数（夹具那本书是 **34**）。
⚠️ **`jpgPath` 不投影**：那是服务端按会话下发的**令牌路径**（`:core` 的 `Jiaocai1BookHandle` 自己就写着
「不要持久化」）。客户端要的是页表，取字节走 `page` 那一条。打不开（没有开放全文 / 会话过期那一枪没补上）⇒
`200` + `book:null`（与 `/api/lms/course-match` 的 `matched:false` 同一条：「没有」不是错误）。

**书架**（一行八格，与 `:core` 的 `Jiaocai1ShelfRecord` 逐字同名）：`ssno` · `title` · `author` · `coverUrl` ·
`totalPages` · `lastReadIndex` · `lastReadAt` · `addedAt`。

- **落在 serve 侧、按账号**（店名 `serve_jiaocai1_shelf<账号后缀>`）：口径照会议 #9 的先例 ——
  **serve 装真的那一层、浏览器只当界面**，与桌面的 `DesktopJiaocai1ShelfStore`（数据根下一个 JVM 文件）同性质：
  **跟着账号、换浏览器还在**。
- ⚠️ **两个时间是 epoch 毫秒，不是 ISO-8601**（§4 的通则在这里让位，与 `/api/agent/sessions` 的 `createdAt`
  同一条理由）：这两格是 `:core` 模型 `Jiaocai1ShelfRecord` 的 `Long` 字段，浏览器要把它们原样放回**同一个模型**
  （屏上自己算「3 天前」）。请求体里省略（或给 0）= 此刻。
- 写端点每一条都**回整份书架**（与 `/api/accounts` 的写端点回快照同一条做法）；`shelf/progress` 只动已有的行
  （不在书架里就是空操作，不是错误，也不会凭空长出一行）；`shelf/{ssno}` 与它一样幂等。
- 移出书架时顺带丢掉那一本的**本地页缓存**（与 `:core` 的 `removeFromShelf` 同一步）。
- **与桌面不共享**（各用自己的店名，理由同上那条：共用一个文件会互相写坏）。
- ⚠️ **web 的档案导出读不到它**：浏览器的档案导出是**同步**的（在页面里拼 zip），读不到 serve 侧的东西 ⇒
  `files/jiaocai/jiaocai1_shelf.json` 这一族在 web 的档案里仍**不落地**（`WebArchiveRows` 那句如实说明不变）。

**页图与封面这两条（§4 第四条例外）**：成功那一半回 `image/jpeg` 字节，**失败那一半仍是信封**
（取不到 ⇒ `502` + 中文短句；页序越界 ⇒ `400` 且文案里写出这本书有多少页）。为什么不塞 data URI
（`venue/captcha` 那一条先例）：扫描页一张几百 KB，base64 是体积乘 1.33 且整张进内存；而屏上就是一张位图，
`serve_token` 是 `Path=/` 的 cookie ⇒ 直接当图片取即可。

**其余如实边界**：

- **封面只认全文库自己的地址**（`jiaocai1.lib.xjtu.edu.cn`）：`?url=` 是客户端从检索结果里带回来的，
  不校验就等于把 serve 变成一台谁都能用的取图代理（`file://` / 别的域名 / `127.0.0.1` 一律 `400`）；
- **封面要在会话建立之后才拿得到**：`:data` 的 `Jiaocai1PageLoader.coverBytes` 走的是取字节那条路，
  **不会**像 `classify` / `search` / `openBook` 那样自己跟一遍 `portalLogin` 跳转链 ⇒ 全新会话里先取封面
  会拿回跳转页（按「取不到」回 `502`）。真实屏上的次序就是「先检索、再画封面」，所以这不算缺路；
  页图那一侧因为要先 `book` 拿到句柄，天然满足；
- **`prefetchWindow` 在这一端是空的**：预取是「一台设备上本地磁盘 + 内存 LRU」的优化（`:data` 的
  `Jiaocai1PageLoader`），浏览器这一侧一页一发请求即可，本层不为它多开端点；
- **取页图失败会重开一次**：`jpgPath` 是会话令牌，上游轮换之后旧路径会一直给空体（`:data` 那层靠图片魔数判成败），
  本层在 `page` 那一条上「取不到 ⇒ 重开这本书 ⇒ 再取一次」，仍拿不到才回 `502`。


#### 消费方（`:web`）：**已落地**（2026-10-22，批 4）

两屏就是 `:feature:jiaocai` 那两份（`Jiaocai1LibraryScreen` / `Jiaocai1ReaderScreen`，与 Android / 桌面同一份），
浏览器只当界面（会议 #9 = 丙「做法①」）：**检索、页表、页图、书架都真的发生在运行 serve 的那台机器上**。

| 谁 | 实现 | 说明 |
|---|---|---|
| 取数（两屏共用） | `ServeJiaocai1Api`（`:feature:jiaocai`，与它的模型同模块） | 九条端点里的七条 JSON；`page` / `cover` 只给**路径构造**（`pagePath` / `coverPath`），字节由 `:web` 取 |
| `Jiaocai1Source` | `WebJiaocai1Source`（`:web`） | 页图 / 封面走**同源 `fetch`**（`Jiaocai1Bytes`，`ArrayBuffer` → URL-safe Base64 → `ByteArray`）；`prefetchWindow` / `evictBook` / `closePages` 是**空操作** |
| `Jiaocai1ShelfStore` | `WebJiaocai1ShelfStore`（`:web`） | `observeAll()` 是**冷流**（收一次先拉一份）；写端点回整份 ⇒ 直接覆盖本地那份 |

四处**如实差异**（都已经想清楚理由，不是「还没做」）：

1. **页图 / 封面是一发一发同源 fetch**，不是「本机磁盘缓存 + 预取窗口」那一套 —— 预取是「一台设备上本地磁盘 +
   内存 LRU」的优化（本节上面那条「如实边界」已明说本层不为它多开端点），而 `serve_token` 是 `Path=/` 的
   cookie ⇒ 同源请求自动带上，不必自己塞头；
2. **取字节不产生 `:data` 的 `AuthExpiredException`** ⇒ 屏上那条 `authExpired` 通路在浏览器这一端走不到；
   会话过期由 `/api/*` 的 `401` 与 `WebAuthEvents`（`Data.kt` 那个钩子）接住，与其余 serve 屏同一条；
3. **书架取数失败会画 `ErrorState`**（不是「书架还是空的」——那是编事实）：为此刻意给 `Jiaocai1ShelfStore`
   补了两个**带默认实现**的成员（`refresh()` / `notices`，默认空实现 / 空流），Android 与桌面那两份
   一个字节都没改；屏上那句「取不到」与那条「重试」是同一份共享屏画的；
4. **`jiaocai1` 的分类树缓存不传**（`jiaocai1Cache = null`）：浏览器这边没有 `DataCache` 的等价物，
   不假装有 30 天缓存（与 `lms` 的 `cacheStore = null`、成绩那屏的 `cache = null` 同一条）。

⚠️ **档案导出读不到书架**：浏览器那一侧的导出是**同步**的（在页面里拼 zip）⇒ `files/jiaocai/jiaocai1_shelf.json`
这一族在 web 的档案里**仍不落地**（上面「书架」那一段已经写着，本批没有改变这一条）。

**用例**：`:feature:jiaocai:jvmTest` 新增 `ServeJiaocai1ApiTest` **14** 例（`MockEngine`）—— 分类树两级递归
与「`nodes` 不是数组就报错」· 检索八格 + 分页四格 + 四个参数都进 query（空 `cls` **不发**、中文关键词百分号编码）·
空结果那一档与「两格分页缺了就按第一页」· `book` 的八组页型逐组 + 摊平 34 页 + **`jpgPath` 不投影** +
`book:null` 与「页表不是八组」分开 · 书架一行八格（两个时间**是毫秒**、原样读）· `upsert` / `progress` 的请求体
逐格、`delete` 走 `DELETE` 不带体 · 业务拒绝（`code != 0`）是异常且文案原样带上 · `pagePath` / `coverPath`
的参数编码 · campus-api 形态一律 `serveOnly`。
`:web` 那一侧：`WebRouteCoverageTest` 的差集从 3 条降到 **2** 条（`Jiaocai1` 出差集），
`wasmJsBrowserTest` 全绿（见 `docs/parity-ledger.md` §8.9 里那条：`Jiaocai1Reader` 仍在差集里，
但**不是「本端做不到」**——它是带参数的深链，门在 `Jiaocai1` 那一屏内部）。
夹具那一处：`:testkit` **零改动**（这一族的假上游上一批已经齐了）。

**夹具与用例（2026-10-21）**：`:server:test` 新增 `ServeJiaocai1Test` **6** 例 —— 分类树两级与检索八格逐字段
（含第二页与空结果那一档）· 检索的参数形状（两个条件都空 `400` / `field` 认不出 `400` / `page` 不是正整数 `400`）·
`book` 的八组页型与总页数 34 且 **`jpgPath` 不投影** · `page` 真回 JPEG 字节（Content-Type + 魔数）与页序越界 `400` ·
`cover` 的域名白名单（五种坏 URL 逐条 `400`）与正主字节 · 书架的完整往返（空表 → 加一本 → 只改进度 → 移出，
每一步回整份，且**全程不碰上游**）。
夹具那一处：`:testkit` 的 `Jiaocai1FakeUpstream` **零改动**（分类树 / 检索 / reader / 页图 / 封面五条它早就齐了）；
`ServeEndpointTestSupport` 新增两个小工具（`getBytes` 按字节读、`delete`）—— 二进制那几条不能按字符串读。

## 6. 夹具契约测试（D5 要求，不能只写文档）

- 位置：**`:server:test`**（`kotlin("jvm")` 模块的测试任务就叫这个名；本轮已经有 13 例：6 例是 serve 第一步的
  外壳验收，7 例是 `/api/session*` 的契约验收）——起**真** `:server`（端口 `0`，系统挑），用**同一批上游样本**
  （已抽成 `:testkit`，`testImplementation(project(":testkit"))`）灌进 `:data`，对 `/api/*` 的响应**逐字段断言**；
- 与既有测试的关系：`:data:jvmTest` 的夹具测的是“数据层”；契约测的是“**同一份数据层 × HTTP 形状**”，
  两者都要在：前者保证解析没漂，后者保证形状没漂；
- 断言写在代码里（`ServeSessionTest` / `ServeServerTest`），与仓库其余契约测试同一形态；
  “上游样本 → 期望 JSON”的成对资源文件**这一轮没采用**（样本已经在 `:testkit` 的夹具常量里，
  再措一份 JSON 只会多一份要同步的副本）—— 若后续要上，放在 `:server/src/test/resources/`。
- MFA（短信二验）那条链需要一份**真的能走完**的上游剧本：二次认证页 + 取手机号 + 校验验证码 +
  隐藏表单回提，四步都在 `:testkit`（`CasSafetyVerifyPage` / `LibraryFakeUpstream` / `JwxtFakeUpstream`），
  默认关着开关，契约测试开它。
- 计数（2026-10-14 核，**第四批之后**）：`:server:test` 共 **80** 例 —— 上面那 13 例之外是按域分批补的契约验收，
  §5.5 那一批（只读四域）是 `ServeReadonlyFourDomainsTest`（**8** 例，每域两例 —— 成功逐字段一例、
  闸门/参数形状一例），它用的就是 `:testkit` 那四份早已存在的假上游（`DzpzFakeUpstream` /
  `AttendanceFakeUpstream` / `CouponFakeUpstream` / `DormPowerFakeUpstream`）—— 那一批只往
  `AttendanceFakeUpstream` 里补了一个 `TEACHER_NAME` 常量（把原本内联在 `recordsPageJson` 里的教师名
  提升为常量，供「教师名不投影」那条红线断言引用；值没变）。

**新增的 8 例（2026-10-14，第二批只读域 §5.6）**：`ServeLmsJiaocaiJwappIclassfaceTest` ——
四族每族两例（成功逐字段一例、闸门/参数形状一例）：`lms` 两例（课程 + 活动列表；详情按类型只出现一格
+ `deadline` 从列表并进来 + 参数 400）· `jiaocai` 两例（检索 + 未命中那一档；`ssno` 三格必填 + 那个
书目号真取到）· `jwapp` 两例（学期成绩逐字段 + 两条上游的请求体/表单各自对着夹具断言 ——
`precise-scores` 真打到**教务**的 `xscjcx.do` 而不是 `jwapp`）· `iclassface` 两例（`date` 默认今天 +
逐字段；形状不对 400 + 空表那一档是 `count 0` 而不是错误）。
每例都过 §5.2 的红线断言，而且是在**夹具里明明有那些值**的前提下证明的（学堂的教师名与本人姓名/学号、
签到表里的姓名与学号）—— 不是「夹具没有所以没出现」。夹具沿用 `:testkit` 那几份早已存在的假上游
（本轮只往 `LmsFakeUpstream` 提了两个常量 `USER_INDEX_NAME` / `USER_INDEX_USER_NO`，值没变）。

**新增的 10 例（2026-10-14，第三批 §5.7）**：`ServePayFeedbackMatchWebvpnTest` ——
四族每族至少两例（成功逐字段一例、闸门/参数形状一例；反馈那一族因为「凭据没配全」
也是契约的一部分而多两例）：

- **付款码两例**：① 未登录 401（且夹具计数为 0）+ 拿码那一串逐字段（就是夹具常量 `BAR_CODE`）；
  ② 加餐券五格（`amountFen` 是分、`serverFlag` 不投影），**且写路径那一发（`voucherUpdateCalls`）必须是 0**；
- **反馈四例**：① 没配凭据 ⇒ `502` + 指路文案（不是 404），且没令牌仍是 401；
  ② 回复与公开问答逐字段（富文本片段数组那一档、不是我要的那条工单不进响应、没勾「公开」的不出现），
  **提交那一发（`submitCalls`）必须是 0**；③ `tickets` 缺 / 空白 ⇒ 400 且不打上游；
  ④ `ServeFeedback.install` 的环境变量判据（五个都在 ⇒ 可用；三个必填缺一个 ⇒ 如实「没配」）；
- **匹配交友两例**：七格逐字段（窗口两端 / `scanned` / `truncated` / 小时升序且只有食堂类支出 /
  食堂名归并成「学苑食堂」/ 阈值没到 ⇒ `diningHours` 是空串），**且商户全称与金额一个都不在响应里**；
  参数形状 400（三种）+ `from` 与 `to` 的默认值（`to` 省略 = 今天、`from` 省略 = 往前 90 天）；
- **WebVPN 两例**：① 正向转换的密文域名那一段是**定值**（与 `:desktop:test` 的
  `DesktopWebVpnCodecJvmTest` 钉的是**同一个值** —— 两端同一份实现，所以这个值在两处各钉一次正是要
  证明的事）+ 已经是网关地址的原样返回；② 反向 + 带端口/路径/查询/片段的来回 + 四个 400
  （body 不是 JSON / `direction` 不认识 / `url` 空白 / 不是网关地址）+ 没令牌 401。


以及**客户端那一侧**的 `:core:jvmTest` `ServePayFeedbackWebvpnApiTest`（**7** 例，2026-10-14 同批）：
逐字段形状（`barCode` 是一串字符串、券的五格、`replies` 是数组而 `public-qa` 的 `items` 三条原文、
webvpn 的请求体就是 `{url, direction}` 两个字段）· 失败只读 `message`（夹具里故意塞了一个旧路的
`msg`，断言它**没有**被读出来）· **缺字段不编造**（空白 `barCode` 抛错、`showCardId` 为空的券不列、
`serverFlag` 如实留空串、归不到工单上的回复不列、只问到的那些才回）· 三个能力开关（`canSelectVouchers` /
`canSubmit` 报 false、两个写方法抛错）· webvpn 两个方向的失败**刻意不同**（`from_vpn` 的 `400` 如实翻成
`null`、`401` 抛错 —— 不把「没登录」说成「网址格式不对」）· 以及最后一条：非 serve 分支抛错。
夹具**这一轮一份都没新增**（也不该有：付款码与消费统计两族都打在早已存在的 `CampusCardFakeUpstream`
上、反馈那一族打在 `FeedbackFakeUpstream` 上、WebVPN 是纯函数根本没有上游可打）——
§7 第 1 条要求的「夹具」那一处**不是每次都得动，但每次都得核**，上面就是这个核的结论。

**新增的 10 例（2026-10-14，第四批 §5.8）**：`ServeJwxtChangesExamsTextbooksScoreDetailSeatCountTest` ——
五族每族两例（成功逐字段一例、闸门/参数形状一例）：

- **调停补课两例**：① 未登录 401（且夹具计数为 0）+ 一条 `MOVED` 的十二格逐字段（`kind` 是枚举名、
  `weeks` 只有第 5 周、`toLocation` 是换到的那间教室），**且上课教师名（`YSKJS` 里那个「姓名/工号」，
  夹具里明明有）不在响应里**；② `?term=` 形状不对 400（不打上游）+ 显式学期原样回 `term` 那一格；
- **考试两例**：① 未登录 401 + 六格逐字段（`examDate` / `examTime` 是上游剥好的那两格）+ 表单里带
  `XNXQDM` 与 `*order`；② 形状不对 400 + **空白串当省略**（与 `/api/jwxt/schedule` 同一个读法）；
- **教材两例**：① 未登录 401 + 九格逐字段 + **`hasTextbook` 两个值都出现**（第一行 true、第二行「无教材」false，
  占位 ISBN 原样在 `isbn` 里）+ **学号只在请求里**（`reportlets` 里有 `'xh'`，响应里没有它）；
  ② 形状不对 400 + 显式学期进了 `reportlets` 的 `xnxqdm`；
- **成绩分项两例**：① 未登录 401 + 十二格 + 两条分项（`itemPercent` 已经是比例 `0.3`）+ 请求体就是 `{id}`；
  ② 「这门课没有分项」⇒ `200` 且 `detail` 是**显式的 `null`**（不是 502/404），`courseId` 缺 / 空白 ⇒ 400 且不打上游；
- **座位数两例**：① 逐字段（**没登录也答得到**，与 `/cdn` 同档）+ **拿不到就是 `null`**（不是 0）；
  ② `room` 缺 / 空白 ⇒ 400 且不打上游。

夹具那一处：本批往 `JwxtFakeUpstream`（教材报表那一枪：POST + `reportlets=[…]`）与 `JwappFakeUpstream`
（「这门课没有分项」那一档）各**加了**东西，**既有常量一个没动**；`EmptyRoomFakeUpstream` **零改动**
（`seatCount` 读的就是它早就有的那份当天 CDN 课表）—— §7 第 1 条要求的「夹具」那一处每次都得核，
上面这句就是本批的核。

以及**客户端那一侧**的 `:core:jvmTest` **11** 例（2026-10-14，§5.8 接线那一批）：
`ServeJwxtScheduleExtraEndpointsApiTest`（**4** 例）—— 三条端点逐字段（`kind` 是枚举名、周次是数组、
教材九格里 `hasTextbook` 就是 `:core` 的判据、占位 ISBN 原样透传）、`?term=` 空白即省略而显式就原样发、
失败只读 `message`、非 serve 分支抛错；`ServeJwappScoreDetailApiTest`（**4** 例）—— 分项逐字段 +
`itemPercent` 是比例（不除第二次）、`detail:null` ⇒ `NoScoreDetailException`（不是加载失败）、
`courseId` 空白不打上游、campus-api 形态 `canFetchScoreDetail = false`；`CampusEmptyRoomApiTest`
新增（**3** 例）—— serve 座位数那一发（`seats:null` ⇒ `null`、字段缺失 ⇒ 抛错、空白教室名不问）、
以及 campus-api 那一档「座位数不自己发请求」（旧行为钉住）。

**批①收尾（2026-10-14）**：`:server:test` 80 → **82**（新增 `ServeJwxtScheduleMergedTest` 两例：
§5.9 那一档的真合并 + 默认那一档行为不变与参数形状 400）；**客户端那一侧**的 `:core:jvmTest`
新增 `ServeJwxtScheduleMergedApiTest`（**3** 例）、`TermTextbooksTest`（**2** 例，教材中心那一段的映射）、
`OwnInboxTest`（**2** 例，`OwnInbox` 搬进 `:core` 之后 id 形状与公告时间不变）。
`:testkit` **零改动**（本批三个件全落在早已存在的夹具上）—— 「夹具那一处不是每次都得动，但每次都得核」：
这次的核就是这一句。

**新增的 3 例（2026-10-15，批② 工单 S7a §5.10）**：`ServeAccountsTest` —— 这一族**不打上游**，所以它验的
不是「上游样本 × HTTP 形状」而是**本机两个真相 × HTTP 形状**：① 未登录且账号表为空 ⇒ 一张空表 +
`readOnly:true`，没令牌照旧 401；② 登录态（落盘凭据在 ⇒ 冷启动静默恢复）+ 表里另一条 ⇒ 两行：表里那条
打码后逐字段（ISO 解回 epoch）、serve 那一条 `active:true` 且 `lastUsedAt` 是**显式 null**，外加红线断言
（夹具里完整学号 / 密码 / 姓名 / 指纹 / 公钥全在，响应里一个都不许有）与「响应只有四格」的键集合断言；
③ 表里也有当前会话那一条 ⇒ 合成成一行并带上表里的 `lastUsedAt`。夹具那一处本批**新增**了
`AccountsFixture`（纯字符串常量，见 §5.10 末段）；`:core` 那一侧同时新增 `ServeAccountsApiTest`（**3** 例）。

**新增的 8 例（2026-10-15，批④ / D11 §3 第 6/7 条）**：`ServeHttpsTest` —— 这一族也**不打上游**
（全部是监听 / TLS / 证书 / 参数），`:server:test` **85 → 93**：

- **默认那一档逐字冻结两例**：① 不给新参数 ⇒ `tls == null`、`--host/--port/--dist` 三个默认值与
  今天逐字相同，证书默认路径就是 `<数据根>/tls/serve.p12` 而且**不建目录**，`setCookieHeader`
  仍然只有 `Path=/; HttpOnly; SameSite=Lax`；② 同一台机器上无论怎么调，HTTPS 那个端口上发明文
  HTTP 必须失败（不静默回落 —— 实测 CIO 的 `sslConnector` 恰恰是那个静默形态，所以这条必须是断言）；
- **真 HTTPS 往返一例**：`--https --port 0` ⇒ `ServeHttps.start`（**生产入口**）起来、
  `resolvedConnectors` 那个端口上真握 TLS + 收发（客户端只信那一张自签证书）、`/api/status` 200、
  闸门还在（无令牌 401 / 对令牌过闸门后 404）；
- **cookie 那一例**：HTTPS 形态下 `GET /api/session` 的 `Set-Cookie` 仍带 `HttpOnly + SameSite=Lax + Path=/`，
  只多一个 `Secure`；明文那一档仍是那一行**逐字**；
- **转发语义一例**：一条 TLS 连接上连打两发（`Connection: keep-alive`）—— 字节转发不许把
  keep-alive 弄丢（将来 D10 的 SSE / 长连接就靠它）；
- **自签生成一例**：默认路径上生成（两个文件 `0600`、证书 `CN=localhost` 且 SAN 含 `localhost` 与
  `127.0.0.1`、`sanOf` 三档），第二次启动**不重签**（指纹相同）；
- **失败语义两例**：① `--cert` / `--cert-alias` 离了 `--https` 就报错（`--https` 本身不带值、`--cert` 缺值也报错）；
  ② 库不存在（不捂着生成）、别名不在库里、文件不是证书库、密码文件没了 —— 四种各一句中文短句。

`:testkit` 本批**零改动**（这一族根本不打上游，与 §5.10 那一批「不打上游的族」同一个形态）。



**新增的 2 例（2026-10-15，批③-a / D11b §5.3.2 第 6 条）**：`ServeVenueActionTest` —— 支付那一跳
（`:server:test` **93 → 95**）：

- **闸门与参数一例**：无令牌 401（闸门）· 令牌对但没登录 401（指路先登录）· 缺 / 空白 `orderId` 400（文案说出缺什么）；
- **302 那一例**：登录后 302 + `Location` 逐字等于 `:data` 拼的那个真支付页地址（夹具的
  `VenueFakeUpstream.paymentPageUrl` 是同一个拼法）· 响应体为空（**没有信封**，§4 的第二条例外）·
  订单号里的 `/` 与 `+` 原样带到 `Location` · **本层不取数**（`orderCalls` / `tobookCalls` 都是 0：
  取支付页等于替用户打支付站，更别说发起支付）· 红线（响应里不允许出现身份）。

`:testkit` 本批**有改动**（这一族第一次动）：`VenueFakeUpstream` 新增 `PAYMENT_PAGE_PATH` 与
`paymentPageUrl(orderId)` —— 那个地址的拼法（`java.net.URLEncoder` 那一套）全文只留一处，测试与 `:data` 都用它。
（夹具仍**不扮演**那一页：`/pay/…` 打过去还是 404。真实现在 80 端口那台站点上，而测试不许替用户打支付站。）
`:core` 那一侧同时改了 `CampusVenueServeWriteTest`（`paymentUrl` 那一格从「空串」翻成「serve 的代跳地址」，
并钉住订单号的编码与 `browserLoginUrl` 仍为空串）与 `VenueMappingTest`（旧路那两条写方法照旧抛 + 不给登录入口）；
`:web` 新增 `WebVenuePayTargetTest`（**3 例**，纯函数 `venuePayTarget` 的三支）。

**本批的六项门禁实测（2026-10-15，XML 实测计数）**：`:server:test` **95**（93 → 95）· `:core:jvmTest` **515** ·
`:desktop:test` **117** · `:data:jvmTest` **259** · `:app:testDebugUnitTest` **444**（一条未减）·
`:web:wasmJsBrowserTest` **8 例 / 0 失败**（5 → 8）；`:web:compileKotlinWasmJs` **BUILD SUCCESSFUL**。

**批③-b / D7 的四项写路径 + 一条如实结论（2026-10-15）：**

四批按主题分开提交（中文前缀、各自注明 D7 编号与契约节），**每一项都带上了三处同改**（本文档 +
`:testkit` 夹具 + `:web` 实现），逐条见 §7 的第 12b/12c/12d/12e 条与 12f 条（那一条是没做的那格）。

| 提交 | D7 | 端点 | 夹具那一处 |
|---|---|---|---|
| 反馈**提交** | ① | `POST /api/feedback/submit` | `FeedbackFakeUpstream` + `submitRejected` |
| 考勤**请假 / 销假** | ② | `attendance/leave*` 六条 | `AttendanceFakeUpstream` + `leaveCreateRejected` |
| **领券** | ③ | `POST /api/coupon/activate` | `CouponFakeUpstream` + `activateRejected` / `activateExpired` |
| 付款码的**勾选同步** | ④ | `POST /api/pay/vouchers/select` | `CampusCardFakeUpstream` + `voucherUpdateRejected` |
| 成绩单 **PDF 落盘** | ⑤ | **没端**（依据与最小可行方案见 §5.5 末） | 零改动 |

**本批的六项门禁实测（2026-10-15，XML 实测计数）**：`:server:test` **108**（95 → 108，新增
`ServeFeedbackSubmitTest` 3 · `ServeCouponActivateTest` 3 · `ServePayVoucherSelectTest` 3 ·
`ServeAttendanceLeaveTest` 4）· `:core:jvmTest` **517**（515 → 517：反馈提交那一例 + 请假三读三写那一例）·
`:desktop:test` **117** · `:data:jvmTest` **259** · `:app:testDebugUnitTest` **444**（**一条未减**，`:app` 本批**零改动**）·
`:web:wasmJsBrowserTest` **8 例 / 0 失败**；`:web:compileKotlinWasmJs` **BUILD SUCCESSFUL**。

⚠️ 本批**没有**动 `:data`（四个域的取数与解析一行未改）⇒ `:data:jvmTest` 与 `:desktop:test` 的计数
逐条不变；`ServeRouteSupport.positiveInt` 那次小整理改了四个 `:server` 文件（删掉 4 份逐字相同的
private 拷贝、留一份 `internal`），不影响任何测试的计数。

**新增的 27 例（2026-10-15，批⑤ §5.11 / D10 的 S-a）**：`ServeAgentTest` —— 十四个端点逐条 ≥2 例
（**配置三例**（默认值如实 / 写读往返 + 键集合与红线 / 认不出的取值 400 与 `apiKey` 的可选语义）·
**型号两例**（没配 key 的明确错 + 「一个请求都不打出去」/ 成与 `502`）· **会话五例**（空表 / 新建 /
内容与 `conversation:null` / 改名锁与空操作 / 删除幂等）· **记忆两例**（序与 `__order` 不投影 / 忘却与清空）·
**对话十一例**（没 key · 开门阶段 400/401/404 · 逐帧的 `delta`→`usage`→`widget`→`nav`→`done` + 落盘 ·
工具与思维链三帧 + 工具行文案与 `nav` 跳转建议 · 工具失败 · `error` 两种 `kind` · 并发 409 · 停止 · 上下文耗尽 ·
MFA 与保活）· **闸门与尾卡两例**（14 条一遍扫过 / status 默认值与尾卡 404）· **生产装配一例**
（`ServeAgent.forServe` 真走一遍：工具表空、key 落 `agent_config_securedefault`、会话落 `agent_sessionsdefault`）·
**纯函数一例**（十一类帧的形状一次钉全））。夹具的那三格（传输 / 工具表 / 起标题）就在这个文件里，测试**绝不打真服务商**。

**本批的六项门禁实测（2026-10-15，XML 实测计数）**：`:server:test` **136**（109 → 136，新增 `ServeAgentTest` 27）·
`:core:jvmTest` **536**（**不变** —— `AgentShared` 那次上提是纯搬迁，`AgentViewModel` 的断言一条未改）·
`:desktop:test` **118**（**不变**）· `:data:jvmTest` **259**（**不变** —— `AgentConfigStore` 多了一个**默认值等于现状**的构造参数）·
`:app:testDebugUnitTest` **444**（**一条未减**，`:app` 本批**零改动**）· `:web` 本批**零改动**（S-d 未做）。

⚠️ 两处**没有**测试覆盖、必须如实记的事：① Windows 上的 DPAPI 那一档在本机（Linux）验不了 —— `agent_config_secure`
套上 `protectedSecureKeyValueStore` 之后，Linux 上 `protector == null` ⇒ 字节与今天**逐字相同**（有测试钉的是
「key 确实落在那份 store 里」），DPAPI 信封本身要靠 Windows CI；② Ktor 3.6 的**服务端没有** SSE 插件
（`gradle/libs.versions.toml` 里没引 `ktor-server-sse`）⇒ 帧是手写的，保活是注释行 —— 形状由上面那条纯函数用例钉住，
但「在真浏览器里逐帧上屏」**到今天（S-d 落地）才有路径**：客户端换成了 `@JsFun` 的 fetch/SSE 桥，
帧的切分与映射由 `:web` 侧的纯函数做（`AgentSseParser` / `AgentStreamState`，有 24 例钉着）——
「桥那一半（异步回调）」在本机只能靠真浏览器里手点一次，见本节末尾与 `docs/serve-visual-checklist.md` ⑪。

**批⑤收尾（S-b / S-c / S-d 三小批）的六项门禁实测（2026-10-15，XML 实测计数）**：`:server:test` **144**（136 → 144，S-c 新增 `ServeAgentToolsTest` 7 + `ServeAgentToolCopyDriftTest` 1）· `:core:jvmTest` **537**（536 → 537，S-b 的 `keyConfigured` 那一格）· `:desktop:test` **119**（118 → 119，同一个洞的那一例）· `:data:jvmTest` **260**（259 → 260，`protectedSecureKeyValueStore` 多一个 protector 参数那一例）· `:app:testDebugUnitTest` **444**（**一条未减**，`:app` 三小批**零改动**）· `:web:wasmJsBrowserTest` **52 例 / 0 失败**（基线 20 → 52，S-d 新增 32）；`:web:compileKotlinWasmJs` **BUILD SUCCESSFUL**。
⚠️ 本批**新增的一处「验不了」**必须如实记：`@JsFun` 回调桥的**异步**那一支（`Promise` 微任务里回调
Kotlin 那一格）在本测试源集里**断言不了** —— `kotlin.test` 的 wasmJs 适配器不支持 `suspend` 测试函数
（实测：编译器直接报「suspend functions annotated with @kotlin.test.Test are unsupported」）。
同步回调与「JS 存下句柄、以后再调」两条是真验过的（`AgentJsFunCallbackProbeTest`，跑在真浏览器里），
所以桥的形态是对的；**逐字上屏那一步要靠真浏览器里手点一次**（`docs/serve-visual-checklist.md` ⑪ 那一行）。

## 7. 变更纪律

1. 改契约 = **同时改**：本文档 + 夹具 + `:web` 对应实现（三处都不动就是漂移的开始）；
2. `:web` 侧只认**本文档**，不认 campus-api 的字段名（那条路在 serve 模式下退休）；
3. 与 `:data` 的分工：**`:data` 定义模型与口径，本文档只定义"怎么把它端出去"**——同一个字段名不在这两处各起一个。
4. **这条纪律最近三次契约变更都照做了**：`0022296`（§5.3 两行 + §5.4）、`4abd1ae`（§5.3.1 写路径）、`45a0e50`（§5.3.2 写路径）—— 本文档、`:testkit` 夹具、`:web` 实现三处都在**同一次提交**里（见各自的提交信息）。反过来，不碰 `/api/*` 形状的那些搬迁提交（`:core`/`:data`/`:desktop`）**不动本文档**。
5. ✅ **那次例外已补位（2026-10-13 接线）**：只读四域的七个端点（§5.5）最初**只改了本文档 + `:testkit`
   夹具两处，`:web` 那一处有意留空**，当时在 §5.5 末尾**显式标注为「消费方待接」**（按第 1 条那本是漂移，
   本条纪律允许的唯一形态是「把缺的那一处指名点姓地标出来」）。后续那一次接线把第三处补上了：
   四个新 `Campus*Api`（`CampusTranscriptApi` / `CampusAttendanceApi` / `CampusCouponApi` /
   `CampusDormPowerApi`）+ 四屏接线 + `WebSupport` 入口 + §5.5 的消费方表 ⇒ 三处同改齐了。
   **教训照旧**：「端点在 `:server` 上能跑」不等于「这屏在该端上能用」，两者之间差的就是这一处。
6. ✅ **那一例外也补位了（2026-10-14 接线）**：第二批只读域的八个端点（§5.6）当时**只改了本文档 +
   `:testkit` 夹具两处**（`LmsFakeUpstream` 提了两个常量，值没变），`:web` 那一处**待接**，
   并在 §5.6 末尾**指名点姓地**标了出来（每屏要哪个端点、拿不到什么、接的时候要注意什么）。
   随后那一次接线把第三处也补上了：四个新 `Campus*Api`（`CampusLmsApi` / `CampusJiaocaiApi` /
   `CampusJwappScoreApi`（一个类两个端口）/ `CampusIclassfaceApi`）+ 四屏接线 + `WebSupport` 入口 +
   §5.6 的消费方表 ⇒ 三处同改齐了。**教训照旧**：「端点在 `:server` 上能跑」不等于「这屏在该端上
   能用」，两者之间差的就是这一处；而且这两轮都证明了一件事：那一处**不是文档问题**，它是实现。
   另外记一条接线时才浮现的事：屏上为了「不画拿不到的东西」而在**端口上**加了几格能力开关
   （`LmsSource.supportsDownload` / `supportsSubmissionList`、`JwappScoreSource.canFetchScoreDetail`），
   它们的默认值保持 Android / 桌面的原行为 —— 这是上一轮那次接线就定下的形式（§5.5）。
7. ✅ **那一例外也补位了（2026-10-14 接线）**：第三批六个端点（付款码拿码 / 反馈读 / 匹配交友的消费统计 /
   WebVPN 纯转换）当时**只改了本文档 + `:testkit` 夹具两处**（夹具那一处是零改动），`:web` 那一处
   **待接**，并在 §5.7 末尾**指名点姓**地标了出来（每屏要哪个端点、拿不到什么、接的时候注意什么 ——
   匹配交友那一屏连「为什么整屏接不上」都写了：wasm 上没有 deflate / Base64，`MatchProfile.codecAvailable == false`）。
   随后那一次接线把第三处补上了：三个 `:core` 客户端（`CampusPayApi` / `CampusFeedbackApi` /
   `CampusWebVpnApi`）+ 三条 `AppPage` 分支 + 一条**如实说明页**（`WebMatchGapScreen`，`AppRoute.Match`）
   + `WebSupport` 入口 + §5.7 的消费方表 ⇒ 三处同改齐了。
   三条教训照旧：「端点在 `:server` 上能跑」不等于「这屏在该端上能用」；那一处**不是文档问题**，它是
   实现；而且这一轮还多了一条 —— **“端出来了”不等于“这一端真用得上”**：匹配交友那一族的端点本端
   一个都没读（那一屏整屏不可用，落的是说明页），付款码与反馈各自只用了半族（矩阵画不出、提交写不了），
   这三件事都写进了 §5.7 的消费方表，而不是靠“端点已落地”这一句糊过去。
   另外记一条：本批的「夹具」那一处仍然是**零改动**（四族全落在早已存在的假上游上：
   `CampusCardFakeUpstream` / `FeedbackFakeUpstream`；WebVPN 是纯函数、根本没有上游可打）——
   所以这条纪律的「夹具」那一处**不是每次都得动，但每次都得核**：这个核的结论写在 §6 末
   （不能因为没动就当它不存在）。
8. ✅ **那一例外也补位了（2026-10-14 接线）**：第四批五个端点（§5.8：我课表三端点 / 成绩分项 /
   教室座位数）当时**只改了本文档 + `:testkit` 夹具两处**，`:web` 那一处**待接**，并在 §5.8 末尾
   **指名点姓**地标了出来（每屏要哪个端点、拿不到什么、接的时候注意什么）。随后那一次接线把第三处
   补上了：`:core/net` 的两个客户端（`CampusJwxtScheduleApi` —— 三条只读端点；`CampusJwappScoreApi`
   补上 `score-detail`）+ `:core` 的 `CampusEmptyRoomApi.seatCount` 的 serve 那一支 +
   `WebScheduleSource` / `WebScheduleHost` 的对应槽位 + §5.8 的消费方表 ⇒ 三处同改齐了。
   四条教训照旧：「端点在 `:server` 上能跑」不等于「这屏在该端上能用」；那一处**不是文档问题**，
   它是实现；「端出来了」不等于「这一端真用得上」；以及能力开关那一格要跟着**一起翻**
   （这一批翻的是 `canFetchScoreDetail`，还新加了一格 `textbooksWithoutStudentId` —— 默认值保持
   各端原行为）。这一轮自己还多出一条，值得记住：
   - ⚠️ **「端出来了」也不等于「照原样就合得上」**：`/api/jwxt/changes` 端的是说明面的
     `ScheduleChangeEvent`，而 `:core` 的合并入口 `JwxtChanges.apply` 吃的是带 `jxbid`（认原课）与
     `appliedAt`（定先后）的 `JwxtChange` —— §5.8 末尾那句当时写的「把两份一起交给共享层的
     `JwxtChanges.apply`」**按当时端出来的形状做不到**。本批的处理是**如实不合并**（格子给排课原样，
     缺口写在日程页顶部那一行说明里），并把「要合得先端哪两格」写回 §5.8 —— 没有第二个折中：
     按课程名猜着合会在多班同课同时段时**改错格子**，那比少画糟。
   - 本批**夹具那一处零改动**（五条端点全落在早已存在的假上游上，`:testkit` 本批一次都没开）——
     所以「夹具」那一处**不是每次都得动，但每次都得核**：这次的核写在 §6 末。
9. ✅ **那一例外也补位了（2026-10-14 批①收尾）**：§5.9 那一档落地的**同一次提交**里就带上了
   本文档 + `:testkit` 夹具（**零改动** —— 用的就是早就有的 `xskcb.do` + `xsdkkc.do` 两份样本）+
   `:web` 实现（`CampusJwxtScheduleApi.mergedSchedule` + `WebScheduleSource.schedule` + 页顶那行说明
    + `honored`）⇒ 三处本来就是齐的。同一次提交还收掉了 §16.5 的第 2、3 条（与它同源）：教材中心屏的
   「本学期我的教材」接上、`OwnInbox` 搬进 `:core`（`SCHEDULE_CHANGE_BUBBLE` 因此在 `:web` 上翻 true）。
   **这一批要记的新纪律**：「同一个端点加一档参数」也算契约变更 —— 默认那一档要**逐字冻结**并由用例
   钉住（`ServeJwxtScheduleMergedTest` 里那条 `changesCalls == 0` 的断言就是干这个的）。
10. ✅ **批② 工单 S7a（2026-10-15）三处同改齐了**：`GET /api/accounts`（§5.10）落地的同一次提交里带上了
    本文档（§5 的那一行 + §5.10 + 本节 + §6 的计数）、`:testkit` 夹具（**新增** `AccountsFixture` —— 这一族
    不打上游，夹具不是页面原文而是两个编造账号，理由写在 §5.10 末段）与 `:web` 实现（`:core` 的
    `CampusAccountsApi` + `WebAccountsScreen` + `WebSupport.openableRouteIds` 加 `AppRoute.Accounts` +
    `AppPage` 那条分支）。
    **这一批多出来的一条规矩**：「端只读、写动作如实不端」也要在契约里**指名点姓**地写成能力开关
    （`readOnly:true`）**加上**一条「为什么不端」的理由 —— 否则下一个人会把它读成「漏了」而去补一个
    半成品的写端点。这也是**路由差集 7 → 6** 的那一格：`AppRoute.Accounts` 从此在 `:web` 的清单里
    （§16.2 第 1 条的路由全等口径），而「清单里有」不等于「四个动作都能做」—— 那件事写在页面上。
11. ✅ **批④（2026-10-15，D11）三处同改齐了**：serve 内置 HTTPS（§3 第 6/7 条 + §3.2 的 cookie 属性 + §5.1）。
    这一批**不动任何端点的请求/响应形状**（还是那几个信封），所以 §7 第 1 条的「三处」是：
    - **本文档**：§3（新增第 6/7 条、改第 2 条）、§3.2（cookie 的 `Secure` 随形态）、§5.1（`Set-Cookie` 那行）、§6（计数）；
    - **夹具**：**零改动** —— 这一批不打任何上游（验的是监听/TLS/证书/参数，`ServeHttpsTest` 一次没碰
      `:testkit`）。「夹具那一处不是每次都得动，但每次都得核」：这次核的结论就是这一句；
    - **`:web`**：**零改动** —— 形状没变，`Secure` 是 cookie 属性、由浏览器自己带（`web/.../ServeSession.kt`
      那边不需要知道这件事）。将来浏览器靠 HTTPS 换成**安全上下文**才能用的那些能力（剪贴板 /
      `crypto.subtle` / Service Worker）属 D12 —— **2026-10-15（D12 ③）已经逐条查清**：剪贴板**可用**（`--https` 或 localhost 下 CMP 的 `writeText` 真在，非安全上下文下只 `console.warn`）、`crypto.subtle` **没有消费方**、Service Worker / PWA **已在 2026-10-16 做出来**（`manifest.webmanifest` + 图标 + `sw.js` 离线壳（网络优先）+ `pwa.js` 注册 + 通知权限与手势，见 `docs/desktop-port-plan.md` §16.12；**Android 小组件在浏览器里没有对应物 ⇒ 那一档不做**），**只有**「普通页面里的后台定时（`periodicSync`）」仍是不做的那一档。
    这一批多出来的一条规矩：**「默认那一档逐字冻结」还要管命令行** —— 不给新参数时 `serve` 的行为
    不只是「端点的形状不变」，还包括启动横幅、`--host/--port` 的语义、cookie 属性一个都不多；
    `ServeHttpsTest` 的第一条与第三条（明文默认值 / HTTPS 端口上发明文必须失败）就是干这个的。
    另：`--https` 下「证书缺了 / 路径不存在 / 密码不对 / 不是证书库」一律报错退出（码 2），
    **不许静默回落成明文** —— 这条也是用例钉住的（`ServeTlsException` 四条）。
12. ✅ **批③-a（2026-10-15，D11b）三处同改齐了**：支付那一跳 `GET /api/venue/pay`（§5.3.2 第 6 条）——
    本档**唯一**一条不当信封答的端点。同一次提交里的三处：
    - **本文档**：§4（例外清单从一条变两条）、§5 的表与状态收口（`venue/pay` + 批③-a 那段）、
      §5.3.2（新增第 6 条 + 原来那段「已知缺口」改成收口记录）、§5.4（消费方那一格的缺口翻转）、§6（计数）；
    - **夹具**：`:testkit` 的 `VenueFakeUpstream`（`PAYMENT_PAGE_PATH` + `paymentPageUrl`，见 §6 末）——
      这是这一族第一次动夹具。夹具**不扮演**那一页（`/pay/…` 打过去仍是 404）：真实现在 80 端口那台站点上，
      而测试**不许**替用户打支付站；
    - **`:web`**：`:core/net` 的 `CampusVenueApi.paymentUrl`（serve 形态给代跳地址）+ 宿主 `WebVenueHost`
      （判据 `venuePayTarget`：只开一个标签、优先支付页，3 例 wasm 测试钉住）+ `ToolboxWebApp` 那条分支
      改指 `WebVenueScreen`。
    **本批如实留下的那一半**：这一跳**一定被浏览器跟随**（顶层 302），但**落成能付款的一页不担保** ——
    支付页要的是它自己站点在**浏览器这一侧**的会话，而 serve 的会话只在 serve 进程的 cookie 罐里、页面也
    替不了别的域名写 cookie（Android 能是因为 WebView 在同一个进程里，`syncCookiesToWebView` 把 cookie
    罐灌进了 `CookieManager`）⇒ 页面上如实写一句「跳过去后若要求登录，请用 App / 桌面端支付」，**没有**
    写成「本端已支持真支付」，也**没有**在线验证过（不许打支付站）。
    **这一批要记的新纪律**：「**不当信封答**」是一种契约变更，必须写进 §4 的例外清单 —— 不许只在实现里
    悄悄改一个响应形状（客户端按 §4 分支读每一个响应，多一条例外就是多一条要客户端知道的路）。
    另一条是老教训在支付这一格上的形态：**「端出来了」不等于「这一跳一定不用登录」** —— 浏览器这一侧的
    场馆站会话只能由站点自己给，这件事写在 §5.3.2 第 6 条与 `docs/serve-visual-checklist.md` 里，
    不靠「端点已落地」糊过去。

12b. ✅ **批③-b / D7 ①（2026-10-15）三处同改齐了**：反馈**提交**（`POST /api/feedback/submit`，§5.7）——
    这一族从「两读」变「两读 + 一写」。同一次提交里的三处：
    - **本文档**：§5 的表（`feedback` 那一行 + 状态收口）、§5.7（端点表加一行 + 「提交」那一段改写 +
      错误码 + 消费方表那一格）；
    - **夹具**：`:testkit` 的 `FeedbackFakeUpstream` 新增 `submitRejected` 开关与两个常量 ——
      该夹具里那条 POST 本来就在（`submitCalls` / `lastSubmitFields` 早就有）⇒
      本批动它是因为要演「上游到了、上游说不给写」那一档（与「凭据没配全」不是一回事）；
    - **`:web`**：`:core/net` 的 `CampusFeedbackApi.submit`（真 POST，`canSubmit` 从 `false` 翻回默认
      的 `true`）+ `ToolboxWebApp` 那条分支的页顶说明重写（从「三处缺口」改成提交真的那一档）。
    **本批要记的一条**：`canSubmit` 这种能力开关**就是 `:web` 那一处本身** —— 端点落地而开关不翻，
    屏上依旧「只能看、不能提」（那是三处同改里最容易假完的那一格：端点在 `:server` 上跑得起来，
    页半开着）。第二条是旧的：**开了写就是开了写** —— 提交那一枪在上游是**匿名可写**的，告警文案、
    §5.7 的「已知缺口」（限流、体量上限）都不是装饰。

12c. ✅ **批③-b / D7 ③（2026-10-15）三处同改齐了**：领券（`POST /api/coupon/activate`，§5.5）——
    加餐券这一族从「两读」变「两读 + 一写」。同一次提交里的三处：
    - **本文档**：§5 的表（`coupon` 那一行 + 状态收口）、§5.5（端点表加一行 + 那段「领券不端」改成
      收口记录 + 错误码 + 消费方表那一格）；
    - **夹具**：`:testkit` 的 `CouponFakeUpstream` 新增 `activateRejected` / `activateExpired`
      两个开关与一个常量 —— 那条 `path == "/app/voucher/activate"` 早就在（`activateBodies` 也在），
      本批加的是**失败那两档**；
    - **`:web`**：`:core/net` 的 `CampusCouponApi.activateCoupon`（真 POST，`canActivate` 从 `false`
      翻回默认的 `true`）+ `ToolboxWebApp` 那条分支的页顶说明重写。
    **本批要记的一条（与 12b 同一条，但这一处更能说明问题）**：领券这个写端点**自己分不出
    「上游说不能领」与「上游坏了」** —— 因为 `:data` 的 `CouponApi.activateCoupon` 返回 `Unit`，
    只有「抛一句上游原文」这一种失败。这不是本层偷懒：要分 `409` 就得先改 `:data`（把失败
    细分）⇒ 本层没有编一个判据出来，而是把这件事**写进契约**（否则下一个人会以为「领券被拒」
    也算「上游故障」是漏的）。

12d. ✅ **批③-b / D7 ④（2026-10-15）三处同改齐了**：加餐券勾选同步（`POST /api/pay/vouchers/select`，
    §5.7）。同一次提交里的三处：
    - **本文档**：§5 的表（`pay` 那一行 + 状态收口）、§5.7（端点表加一行 + 那段「刻意不端的写路径」
      改成收口记录 + 错误码 + 消费方表那一格）；
    - **夹具**：`:testkit` 的 `CampusCardFakeUpstream` 新增 `voucherUpdateRejected` 开关与一个常量
      —— `UPDATE_VOUCHERS_PATH` 那一条路由与 `voucherUpdateCalls` / `lastVoucherUpdateBody` 早就
      在（它是给两端写路径测试用的），本批加的是**失败那一档**；
    - **`:web`**：`:core/net` 的 `CampusPayApi.updateVoucherStatus`（真 POST，`canSelectVouchers`
      从 `false` 翻回默认的 `true`）+ `ToolboxWebApp` 那条分支的页顶说明重写。
    ⚠️ **这条是 D7 清单里唯一的「做不到」**：付款码的「**支付**」上游客户端本来就没有（`:data` 的
    `PaymentCodeApi` 四个方法里没有扣款那一个）⇒ 没东西可端。本层**没有**为了凑数造一个假端点，
    而是把「这一族唯一的写就是勾选同步」写成结论（§5.7 + §5 状态收口）。
    另一条要记的是**写端点的参数默认值**：`showCardIds` 刻意**不带**默认值 —— 缺了与「传了个空表」
    在写端点上不是一回事（后者是「取消全部勾选」），有默认值就会把一次笔误变成「把用户的勾选全清掉」
    （服务端 `PayVoucherSelectRequest` 与 `:core` 两处都按这条写的）。

12e. ✅ **批③-b / D7 ②（2026-10-15）三处同改齐了**：考勤请假 / 销假（§5.5.1）——
    这一族从「学期 + 流水两个只读端点」变「六条（三读 + 三写）」。同一次提交里的三处：
    - **本文档**：§5 的表（`attendance` 那一行 + 状态收口）、新增 **§5.5.1**（端点表 + 逐字段 +
      消费方段）、§5.5 消费方表那一格、§8 无改动；
    - **夹具**：`:testkit` 的 `AttendanceFakeUpstream` 新增 `leaveCreateRejected` 开关与一个常量
      —— 请假那一族六条路由**早就在**（两端一直在用），本批加的是**失败那一档**；
    - **`:web`**：`:core/net` 的 `CampusAttendanceApi` 补上六条（并给 `:server` 那一侧的
      `ServeRouteSupport.positiveInt` 搬了一次家：它原本是 `CouponRoutes` 的 private）+ 那个类里
      的 `AttendanceCapabilities.leave` 翻 `true` + `ToolboxWebApp` 那条分支的页顶说明重写。
    ⚠️ **本批碰到一个需要上一级拍板的事：审批人姓名**。`:data` 的 `LeaveApprover.name` 与
    `LeaveCourse.teacherNames` 都是**真人姓名**，而 §5.2 的例外清单里当时没有它们 ⇒ 本层按红线
    **不投影**（形状里留着、值是空串），屏上那个下拉框退化成一行 `userId`。**这一条已于 2026-10-15
    拍板并落地（例外 ④），见 12g**；
    默认值／幂等键那两条新纪律写在 §5.5.1 里（`requestId` 缺了就 400；`:core` 空串时自己补一枚）。

12f. ⚠️ **批③-b / D7 ⑤（2026-10-15）本轮没做 —— 如实报告，不是漏**：成绩单 PDF 落盘。
    `:data` 侧**有** PDF 能力（`generatePreviewPdf` / `downloadPdf`），但第 2~7 步是**真的往学校提交
    一份申请**（签章 + 审批）、第 2 步的返回值里有学号、而且“把字节落成文件”在契约的信封形状里没有
    位置 ⇒ 三条理由与两步式最小可行方案都写在 **§5.5 的成绩单那一段末尾**。这一格**没做任何代码
    改动**（三处同改里一处都没动）：`:web` 的成绩单屏保持「只画选类型 + 页顶说明」的现状。
12g. ✅ **§5.2 例外 ④（2026-10-15，批③-b 的遗留件）拍板并落地**：请假这一族的两个姓名真投影。
    上一级拍板把 **`LeaveApprover.name`（我自己的申请上的审批人姓名）与 `LeaveCourse.teacherNames`
    （我自己的课的任课教师姓名）加进 §5.2 的红线例外清单**。理由两条：
    ① 与例外 ③（`emptyroom/rooms` 实时档的教师名）**同性质** —— 都是「我自己的那条记录」上的
    真人名，不是别人的、也不是花名册；
    ② **不加就与 Android 屏不一致**：`:data` 一侧一直把这两个名字端给 Android / 桌面，而 serve 这一侧
    留空使屏上那个审批人下拉框退化成一行 `userId`（`:core` 写的是 `it.name.ifBlank { it.userId }`）。
    同一次改动动了四处：
    - **本文档**：§5.2 的例外清单从三处变四处（含理由与「只覆盖这两类」的限定）、§5 的 `attendance`
      那一行、§5.5.1 的逐字段那一条与消费方表那一格；
    - **`:server`**：`AttendanceRoutes.kt` 的 `LeaveApprover.toDto()` 与 `LeaveRecord.toDto()` 里
      课程那一格两个投影点（原来是写死的空串），两处 DTO 的 KDoc 从「刻意不投影」改成事实；
    - **`:core`**：`CampusAttendanceApi` 的 `teacherNames` 照 `:server` 的原文收下（审批人的 `name`
      本来就在收）+ 类 KDoc 那一节；`:web` 页顶说明里「上课教师名（流水与请假单两处）」改成流水一处；
    - **夹具 / 用例**：`AttendanceFakeUpstream` 新增 `LEAVE_APPROVER_NAME` / `LEAVE_COURSE_TEACHER`
      两个编造姓名常量、给夹具那条请假记录补上一节课（原先 `courses` 是空表）；`:server:test` 的
      `ServeAttendanceLeaveTest` **4 → 5** 例（新增一条专钉红线：只允许这两类姓名，别的一律不许）、
      `:core:jvmTest` 的 `ServeReadonlyFourDomainsApiTest` 那一例同步。
    ⚠️ 红线本身一个字没松：学生本人姓名 / 学号 / 手机号 / 通讯录仍一律不端，考勤流水那一格的 `teacher`
    （当堂上课的教师名）**仍不投影**（它不在例外 ④ 里，§5.5 那一条一字未改）。

13. ✅ **批⑤（2026-10-15，D10 的 S-a）三处同改核过了**：`/api/agent/*` 那十四条（§5.11，D10 的第一批）：
    - **本文档**：§5 的表（新增一行）+ 状态收口（一段）+ **§5.11**（端点 / SSE 十一类事件 / 红线 /
      两处有意偏离 / 「本批**没有**做的」）+ §6（新增 27 例与六项门禁计数）+ 本节；
    - **夹具**：`:testkit` **新增** `AgentFixture`（一把**编造**的 API Key + 一组编造的值）。它是这一族唯一
      能当夹具的东西：这一族**不打学校上游**（连模型那一枪在测试里也是夹具）⇒ 夹具不是「页面原文」，而是
      「一组编造的值 + 那把 key」，红线断言靠的就是「夹具里明明有它」；
    - **`:web`**：**零改动** —— 本批**没有**做 S-d 接线（那一屏仍是降级页）。这一处在 §5.11 的「本批**没有**
      做的」里**指名点姓**地写了出来（每一条：做到哪、还剩什么、为什么），那是第 1 条纪律允许的唯一形态。
    **本批要记的三条新纪律**：
    - **「能力开关必须来自实现本身，不能来自『打算实现什么』」**：`:toolAllowlist` 是从**真下发给模型的工具定义**
      里读出来的（不是另维护一份常量清单），所以 S-c 未落地时它**如实是空数组**。反过来说：要是当初写成一份
      手维护的清单，这一版就会报「有 9 个工具」而模型一个都调不到 —— 那就是一个**假入口**；
    - **「现在不能」的三种情形走同一个 `409`**（先例是 `LOGIN_BUSY` / `ACTION_REJECTED`）：没配 key /
      已有一轮在跑 / 上下文耗尽。它们不是形状错误（`400`）、也不是「等会儿再来」（`502`）——「先处理那一件」
      才是它们的意思，`message` 各说各的；
    - **SSE 这一族的「不是异常终止」是**：被停止 / 客户端断开 / 写帧失败 ⇒ **一个终止事件都不发**（`done` 与
      `error` 都不发）。这条必须写进契约，否则下一个人会把「流没发完」读成「契约漏了一个事件」。
    另记一条**施工事实**（值得下一个人知道）：`SseAgentTransport` 把增量回调派发到 `Dispatchers.Main.immediate`，
    而 **`:server` 没有主线程**（它只依赖 `kotlinx-coroutines-core`，不引 coroutines-swing / -android）⇒
    直接写会在**第一个增量**上抛「Module with the Main dispatcher had failed to initialize」。本批把那一格改成
    「有主线程就用它、没有就用 `Dispatchers.Default`」（Android / 桌面**行为一字未改**）—— 不改的话，
    这一族在 serve 上根本跑不起来，而它在夹具下完全看不出来。
14. ✅ **批⑤ 的续两小批（2026-10-15，S-b 剩余 + S-c）三处同改核过了**：`AgentConfig.keyConfigured` / 配置面板
    三个槽位 / `AgentChatSession` 抽口（S-b）与 serve 白名单工具层九件（S-c）：
    - **本文档**：§5 那一行与状态收口（改成事实）+ **§5.11**（「本批**没有**做的」那一段按事实重写、夹具与用例补两条）
      + 本节；
    - **`:core`**：`AgentConfig.keyConfigured`（默认 `false`、不落盘）、`AgentConfigPanel` 三个槽位、新的
      `AgentChatSession.kt`、`AgentViewModel` / `AgentChat` / `AgentScreen` 收口；**`:app` / Android 行为逐字未变**；
    - **`:data` / `:desktop`**：`protectedSecureKeyValueStore` 多一个 `protector` 参数（默认本机那一档，只为让
      Windows 的密封路在 Linux 上可测）；`DesktopAgentHost` 的密钥半改用同一个保护器（缺口 A 的桌面那一半）；
    - **`:server`**：`ServeAgentTools.kt`（九件工具，课表族那四条逐字搬 `:app`）+ `ServeAgent.forServe` 接上它 +
      `schedule_cache<suffix>` / `custom_courses` 两份与桌面同名的落盘；
    - **夹具 / 用例**：`:testkit` **零改动**（这一批用的全是早已存在的假上游）；`:server:test` **136 → 144**、
      `:core:jvmTest` **536 → 537**、`:data:jvmTest` **259 → 260**、`:desktop:test` **118 → 119**，
      `:app:testDebugUnitTest` **444 一条不减**。
    ⚠️ **施工坑一条**（值得下一个人知道）：`:app` 的 `toKx()` / `toJavaTime()`（`util/TimeBridge.kt`）是
    `internal`，`:server` 拿不到 ⇒ 逐字搬过来的那几段要 `:server` 自己写一份同名同语义的换算，
    否则那几行会变成「差异清单」里的假差异（那份清单只该记**端上资源**那几处）。
15. ✅ **批⑤ 的 S-d（2026-10-15，`:web` 接线）三处同改核过了**：浏览器里那一格从降级页换成 `:core` 的
    `AgentScreen` 本身：
    - **本文档**：§5 那一行与状态收口（改成事实）+ **§5.11**（S-d 那一大段、探针结论与盲点、`:web` 新增 32 例、
      六项门禁计数）+ 本节；
    - **`:web`**：**新增** `AgentSse.kt`（`AgentFrame` + 纯函数 `AgentSseParser`）、`AgentSseBridge.kt`
      （`@JsFun` 的 fetch/SSE 桥 + abort）、`AgentStreamState.kt`（十一类事件 → 状态）、`WebAgentSession.kt`
      （会话 / 配置 / 记忆的那几发 HTTP + 消费循环）、`WebAgentScreen.kt`（表头 / 页顶说明 / Key 徽标 /
      serve 侧记忆那一块 / `WebAgentHost`）；**改** `ToolboxWebApp.kt`（`AppRoute.Agent` 那条分支）+
      `WebSupport.kt`（`openableRouteIds` 加 `Agent`、文件头那段同步，**差集 5 → 4**）+ `WebFallbackScreens.kt` /
      `WebShell.kt` / `WebScheduleSource.kt`（三处提到「屁岱是降级页」的旧话改成事实）+ `web/build.gradle.kts`
      （快照多读一份 `:server` 的源文件）；
    - **`:core`**：`AgentScreen.kt` 的 `onSave` 多一行 —— 保存之后把 `keyConfigured` 按**宿主那一份**补回来
      （面板自己拼的那份不带它；Android / 桌面的默认实现恒 `false` ⇒ **三端行为逐字不变**）。这一行是工单
      拍板的「保存一次之后页面仍然知道这份部署配没配 key」那个要求；
    - **夹具 / 用例**：**`:testkit` 零改动**（这一族不打学校上游：它打的是用户自己配的模型服务商，测试里一律夹具）；
      `:web:wasmJsTest` **新增 32 例**（基线 20 → 52，逐组分法在 §5.11）；其余五项**一条未减**（537 / 144 / 119 /
      260 / 444，逐条见 §5.11 的门禁段）。
    ⚠️ **施工坑两条**（值得下一个人知道，两条都是**实际踩到**的）：
    ① **KDoc 里的路径通配会把块注释嵌套掉**：Kotlin 的块注释是可嵌套的，写一句「十四个端点都在
    `/api/agent` 那一族」没问题，写成 `/api/agent` 后面跟一个星号，就会让编译器报
    `Syntax error: Unclosed comment`（而且报在文件末尾，看着像另一处错）—— 设计稿 §16.7 那条「KDoc 里
    不要出现块注释起始符」就是这个坑；
    ② **wasmJs 的 `kotlin.test` 不支持 `suspend @Test`**：探针那条异步用例必须删掉（编译器直接报
    `suspend functions annotated with @kotlin.test.Test are unsupported`）⇒ 这一端凡是要等异步结果的断言
    都写不了，只能盖住纯函数与同步回调那一半。
---

## 8. 这份草案的依据（谁写的、读了什么）

- 读者：`serve` 模式的实现者（`:server` + `:web` 改造），以及每次把某个 `*Api` 搬进 `:data` 的那次提交。
- 依据（全部是只读核对）：`docs/desktop-port-plan.md` 的 D3/D5/D6 与 §3/§5/§6；
  `:core/core/net/` 那 17 个 campus-api 实现（它们的类 KDoc 里有逐条字段映射与"如实降级"说明；
  只读四域的四个是 2026-10-13 接线时补的，本文件草拟时那一族还是 13 个）；
  `:core` 里 12 个取数端口的接口 KDoc；`web/tools/serve-same-origin.py`（serve 模式的雏形）。
- 未核实的部分已标 `TODO`/`待确认`，**不要当成结论用**。