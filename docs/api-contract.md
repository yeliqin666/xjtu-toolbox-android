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
| **能力** | **只读**：图书馆/场馆/评教都在自己的 status 里写 `readOnly:true` | **含写路径**（已落地的两种）：图书馆约座 / 换座 / 签到 / 退座（§5.3.1）与场馆下单 / 取消（§5.3.2）。⚠️ **付款码与加餐券不在其中**：`:data` 侧两份实现都有（`data/src/commonMain/kotlin/com/xjtu/toolbox/pay/PaymentCodeApi.kt`、`data/src/commonMain/kotlin/com/xjtu/toolbox/coupon/CouponApi.kt`），Android 与桌面的窗口模式已在用 ⇒ 2026-10-13 起加餐券的**只读**两发（`/api/coupon/{list,detail}`）已落地（§5.5）；⚠️ 2026-10-14 起**付款码的「拿码」**也落地了（`/api/pay/code` + `/api/pay/vouchers`，§5.7），但**领券**（`activateCoupon`）、**付款码的勾选同步**（`updateVoucherStatus`）这类真改上游状态的写路径仍**没有对应端点**（「支付/扣款」那个动作客户端本来就没有，不是本轮砍的）。⚠️ 2026-10-14 的第二批只读域（§5.6）同一条口径：思源学堂 / 教材中心 / 移动教务成绩 / 人脸识别签到四条链在 `:data` 里都有实现（Android 与桌面端直连在用），本轮只端**只读**那一半 —— 学堂的作业**提交**与附件**下载**、教材的**全文阅读**、成绩的**分项**与成绩**报表**都不端，签到的姓名/学号按红线不投影（逐条见 §5.6）。⚠️ 第三批（§5.7）再补三族同一条口径：反馈只端**读**（提交反馈是写路径 ⇒ 不端）、匹配交友只端**消费统计**（学工档案那一半 `:data` 里没有源 ⇒ 不端），WebVPN 那一族是**纯函数**（不收会话、不碰上游、不改任何状态）。⚠️ 第三批的 `:web` 消费方 2026-10-14 也接上了（§5.7 的消费方一节），但**“端出来了”不等于“这一端真用得上”**：付款码只画数字、券只读，反馈只能读不能提，匹配交友**整屏读不了**（落一张说明页 —— wasm 上没有 deflate / Base64 ⇒ 出不了分享码） |
| **谁能做** | 一刀切只读 | **能力开关随部署与账号变**：`canBook` / `canCancel` / `canSubmit` / `hasSeatPlan` / `availableSources` 必须**如实**出现在响应里，屏按它决定画不画按钮（"点了会失败的按钮一个都不画"） |
| **会话** | 单账号、回环、零鉴权（凭据托管在 campus-api 进程里） | **浏览器不带任何凭据**：登录/登出/短信二验都经 `/api/session*`，会话在 `:server` 进程里（默认只监听 `127.0.0.1` + 访问令牌）。令牌先换成 cookie `serve_token`（只有它能被浏览器带着走），之后的请求就只靠它 |

> 推论（写进 `:web` 的改造）：浏览器端原来那 13 个「只读投影」实现在 serve 模式下**要退休**——
> 它们把能力开关硬编成 `false`（`canBook=false` 那种）。新契约里开关是**响应的一部分**，
> 于是同一份 `:web` 屏在 serve 模式下能看到真实能力（能约座就是能约座）。

## 3. 默认安全口径（D3 定稿，实现时逐条守住）

1. 默认**只监听 `127.0.0.1`**；
2. 首次启动生成并**打印访问令牌**；浏览器侧必须带（`Authorization: Bearer <token>`，或令牌换 cookie 后随请求带）。
    cookie 名固定 **`serve_token`**，由 `GET /api/session` 发出（`Path=/; HttpOnly; SameSite=Lax`，见 §5.1）；
    浏览器在地址栏里贴不了 `Authorization`，所以实际形态就是「先用 Bearer 换 cookie，之后只带 cookie」；
    不带 `Secure` 是因为默认是明文 HTTP（浏览器会直接丢掉带 `Secure` 的 cookie）。
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
- **`code` 就是 HTTP 状态码**（2026-10-11 落地 `/api/session*` 时定下，`:server` 的 `ApiErrors` 是这一条的实现）：
  成功 `code == 0` 且 HTTP 200；失败时 `code` 与 HTTP 状态码**逐位相同** —— 一条信息不出现两种写法，
  客户端读哪一个都一样。已用的码：`400` 请求体形状不对 · `401` 无令牌 / 登录被拒 · `404` 端点不存在 ·
  `409` 另一次登录正在进行 / 上游明确拒绝一次写动作（§5.3.1） · `502` 上游或网络的故障（稍后重试可能就成）。
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
| `/api/venue/captcha` `/api/venue/book` `/api/venue/cancel` | `VenueSource` | 体育场馆（写） | **P1（写路径）· 已落地 2026-10-12** | 无旧形状：serve 侧新开的两条写路径（契约 §5.3.2 逐字段；落地提交 `45a0e50`） |
| `/api/dzpz/transcript` | `TranscriptSource`（`:data` 侧 `AppTranscriptSource` + `TranscriptApi`） | 电子成绩单 | **P0 · 已落地 2026-10-13** | **新形状**：只端七步里的**第一步**（建单表单）—— 出 PDF 那半条链没有端点，逐字段见 §5.5 |
| `/api/attendance/terms` `/api/attendance/records` | `AttendanceSource`（`AppAttendanceSource` + `AttendanceApi`） | 考勤 | **P0 · 已落地 2026-10-13** | **要改**：`startSection ← startTime`、`endSection ← endTime`、`term ← termString` 三处改名（`:core` 那两个 `Time` 字段其实是节次），`teacher` 按红线不端，见 §5.5 |
| `/api/coupon/list` `/api/coupon/detail` | `CouponSource`（`AppCouponSource` + `CouponApi`） | 加餐券（**只读**） | **P0 · 已落地 2026-10-13** | **新形状**（原来这一行是 P2 = Stage C 宿主能力；本轮只端**只读**半，领券不端）。金额单位是**分**，见 §5.5 |
| `/api/dorm/rooms` `/api/dorm/reading` | `DormPowerSource`（`:data` 的 `DormPowerApi` 本身就是端口实现） | 宿舍电费 | **P0 · 已落地 2026-10-13** | **要改**：旧的 `GET /api/dorm/kwh?roomId=` 一个端点把列表与读数混在一处 ⇒ 拆成两个；`reading` 不带时间戳，见 §5.5 |
| `/api/lms/courses` `/api/lms/activities` `/api/lms/activity` | `LmsSource`（`:data` 侧 `AppLmsSource` + `LmsApi`） | 思源学堂 | **P0 · 已落地 2026-10-14** | **要改**：`instructors` / `liveInstructorNames`（**教师姓名**）按红线不投影；附件的三个下载/预览地址与回放、直播地址**不端**（要站点会话）；`type` 是 `LmsActivityType.value` 那个小写字符串，见 §5.6 |
| `/api/jiaocai/search` `/api/jiaocai/ssno` | `JiaocaiSource`（`AppJiaocaiSource` + `JiaocaiApi`） | 教材查询 | **P0 · 已落地 2026-10-14** | **新形状**：`search` 只收一个 `keyword`（这一屏没有院系/教师那种筛选控件）、**没有翻页**；`ssno` 是「列表里拿不到、进详情才有的全文库书目号」那一枪，见 §5.6 |
| `/api/jwapp/scores` `/api/jwapp/precise-scores` | `JwappScoreSource` + `JwxtScoreSupplement`（`:data` 侧 `AppJwappScoreSource` + `JwappApi` / `CjcxApi`） | 成绩 | **P0 · 已落地 2026-10-14** | **新形状**：**一族两个站点**（`scores` 打移动教务、`precise-scores` 打**教务** `xscjcx.do`），服务端**不合并**两份（合并口径在 `:core` 的 `JwappScoreViewModel`，有测试钉着），见 §5.6 |
| `/api/jwapp/score-detail` | `JwappScoreSource`（`:data` 侧 `AppJwappScoreSource` + `JwappApi`） | 成绩（**分项**） | **P0 · 已落地 2026-10-14** | **新形状**：`?courseId=` 必填（取自 `scores` 那一行的 `id`）；「这门课没有分项」**如实回 `detail:null`**（不是 502/404）—— ⚠️ 这一条在 §5.6 里当年被写成「刻意不端」，本批端了、那一行已改写，见 §5.8 |
| `/api/iclassface/records` | `IclassfaceSource`（`AppIclassfaceSource` + `IclassfaceApi`） | 快速考勤流水 | **P0 · 已落地 2026-10-14** | **要改**：`name` / `studentNo`（姓名与学号）按红线不投影（屏上本来也不画那两格）；`time` **保留上游原文、不转 ISO-8601**，见 §5.6 |
| `/api/pay/code` `/api/pay/vouchers` | `PaymentCodeSource`（`:data` 侧 `PaymentCodeApi`）—— **借校园卡站点会话** | 付款码（**拿码**） | **P2 · 已落地 2026-10-14** | **要改**：旧那行写的 `/api/payment-code` 是建议路径、从未实现 ⇒ 现在写死成 `/api/pay/{code,vouchers}`（与其余各族同一条段名规矩）；⚠️ **支付 / 扣款这一档客户端本来就没有**（人只显示码）⇒ 不是本轮砍的；勾选券的同步（`updateVoucherStatus`，写路径）与勾选落盘也不端，见 §5.7 |
| `/api/feedback/replies` `/api/feedback/public-qa` | `FeedbackSource`（`:data` 侧 `AppFeedbackSource` + `FeedbackApi`） | 反馈（**只读**） | **P0 · 已落地 2026-10-14** | **新形状**：这一族**没有校园站点会话**（凭据是 `app_id` + `app_secret` 换租户令牌 ⇒ serve 端按**环境变量**在启动时注入，见 §5.7）⇒ 这两条只认令牌闸门；**提交反馈是写路径 ⇒ 不端** |
| `/api/match/dining` | `CampusCardSource`（流水） + `:core` 的 `DiningHabit` / `MatchProfile` | 匹配交友（消费统计） | **P0 · 已落地 2026-10-14** | **新形状**：只端「常在几点吃饭 / 常去哪个食堂」那两维（不含金额 / 商户全称 / 明细）；⚠️ `/api/match/profile`（**学工档案**）**不端** —— `:data` 里没有 `hello` 取数（那条链在 `:app`，桌面端也如实没有），见 §5.7 |
| `POST /api/webvpn/convert` | `WebVpnCodec`（`:data` 侧 `WebVpnUtil`） | WebVPN 转换器 | **P0 · 已落地 2026-10-14** | **新形状**：纯转换（两个方向、无会话、不碰上游、`400` 只有参数那一档）。**为什么不做成浏览器本地**：HTTP + 非 localhost ⇒ 非安全上下文 ⇒ `crypto.subtle` 被禁，见 §5.7 |
| `/api/v1/personal/me/user` | —— | （身份） | P2 | ⚠️ 待确认：这条出现在 `:core` 现有代码里，实现时先查清它是谁的投影 |

> **状态收口（2026-10-13 核账，纯文档轮次）**：`:server` 里**已实现**的端点族 =
> `status` · `session{,/login,/logout,/mfa}` · `calendar/school` · `info/{yellowpage,faculty}` ·
> `fitness/{years,score}` · `notification/{sources,list}` · `inbox` · `emptyroom/{cdn,rooms}` ·
> `jwxt/{terms,term,term-start,schedule,grades,school-courses,evaluations,evaluations/status}` ·
> `library/{campus,areas,seats,my,action}` · `venue/{products,slots,orders,status,captcha,book,cancel}` ·
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
> **仍未实现**的只剩一行 + 一处如实缺口：`/api/v1/personal/me/user`（P2：来源仍待确认）、
> `/api/match/profile`（P2：**学工档案**在 `:data` 里没有源 —— `hello` 那条链只在 `:app`，桌面端也
> 如实没有 ⇒ 本轮不端，判据与 `DesktopMatch.kt` 的 TODO 见 §5.7）。
> （`/api/payment-code` 那一行已于 2026-10-14 从「未实现」里出来 —— 拿码 + 可用券两发落地，路径同时
> 写死成 `/api/pay/{code,vouchers}`，见 §5.7；**支付 / 扣款**那个动作客户端本来就没有，**勾选同步**仍未端。）
> ⚠️ **表里那个 `P0`/`P1`/`P2` 是优先级，不是状态** —— `P1` 那两行（写路径）都已落地（见各自那一格）。
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
   `Set-Cookie: serve_token=<令牌>; Path=/; HttpOnly; SameSite=Lax`。浏览器先用
   `Authorization: Bearer` 打这一枪，之后**只带 cookie**（闸门两种形态都认，名字固定 `serve_token`）。
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
`404` 未实现 · `502` 上游/网络故障（`FriendlyError` 中文短句，含会话过期）。
能力开关一律**如实**：`canBook` / `canCancel` / `canSubmit` / `availableSources` / `contactsAvailable` 出现在各自响应里。

**红线（三处刻意例外，均为公开数据或登录者本人数据，测试用编造夹具值钉住）**：
「响应不含学号/姓名/手机号」对登录者本人成立；例外的三处是 ① `fitness/score` 的姓名/学号（本人数据），
② `yellowpage`/`faculty` 的教师名与部门电话（公开通讯录），③ `emptyroom/rooms` 实时档的上课教师名。

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

⚠️ **已知缺口（如实记下，不在本轮）**：下单能成之后屏上那个「去支付」**点了不会有动作** ——
支付页在 80 端口那台站点上（`VenueApi.paymentUrl` 拼的），而浏览器里没有场馆站的会话，
支付页地址也只有 `:data` 知道（`:core` 不复制一份站点知识）⇒ `CampusVenueApi.paymentUrl` 在 serve
模式下如实返回空串（不是抛、也不是编一个地址）。TODO：契约给了支付入口（或让 `/api/venue/status`
的 `browserLoginUrl` 能被客户端缓存下来）之后再把它接上。

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
     `canBook`/`canCancel` 翻 true（与 `/api/venue/status` 报的两个开关一致）。⚠️ 留下的**如实缺口**：
     「去支付」那一跳在浏览器里仍不可达（见 §5.3.2 末尾）；
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
- ⚠️ **`teacher` 不投影**：考勤记录里的上课教师名是**姓名**，而 §5.2 的例外清单里没有它
  （例外只有 ① `fitness/score` 本人姓名/学号 ② 黄页/教师检索的通讯录 ③ `emptyroom/rooms` 实时档的教师名）。
  `:web` 若要显示教师名，得先把它加进例外清单（那是改契约，不是改这一层）；
- ⚠️ **还没端的三块**（serve 模式下考勤屏目前只有「学期选择 + 流水表」）：`getStudentInfo`（姓名那格过不了
  红线）、统计栏（`getKqtjCurrentWeek` / `getKqtjByTime` / `computeCourseStatsFromRecords`）、
  打卡原始流水（`getStreams`）；
- **请假那一半一律不端**（`getSemesterWindow` / `getFlowPreview` / `getLeavePage` / `uploadEvidence` /
  `createLeave` / `withdrawLeave` / `cancelLeave`）：请假 / 销假是写路径；
- 错误码：`400` 上面那三格形状不对 · `401` 没会话 · `502` 上游/网络故障。

**`GET /api/coupon/list` / `GET /api/coupon/detail`（加餐券，只读）**

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
- **领券（写路径）不端**：`CouponSource.activateCoupon`（`POST /app/voucher/activate`）真会改上游状态 ⇒
  serve 模式下那个「领取」按钮**没有端点**（如实缺口）；
- 错误码：`400` `filter` 不认识 / `page`、`size` 不是正整数 / `showCardId` 缺失 · `401` 没会话 ·
  `502` 上游/网络故障（含 `:data` 认出的会话失效那一档）。

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
- 错误码：`400` `roomId` 缺/空白 · `401` 没会话 · `502` 上游/网络故障（含会话失效那一档 —— 上游的
  `401001` 被 `:data` 认成登录过期，本层按 `502` 报「稍后重试可能就成」）。

#### 消费方（`:web`）：**已落地**（2026-10-13 接线）

七个端点由 `:core/net` 的四个新 `Campus*Api` 消费（`CampusTranscriptApi` / `CampusAttendanceApi` /
`CampusCouponApi` / `CampusDormPowerApi`）—— 它们与既有那 13 个同一族（`ApiMode` 双形态），只有一个差别：
**默认形态（campus-api）那一支不猜也不编空数据，直接抛错**（这四个域在 campus-api 里根本没有端点：
`:app` / 桌面是走 `:data` 的 okhttp 抓的）。`:web` 因此把这四屏写成「serve 形态才画真屏，否则画一张
「这一屏要 serve 模式」的如实页」，并把拿不到的东西画在屏幕上，而不是编出来：

| 屏 | 端点 | `:web` 上真画了什么 | 如实不画（缺口） |
|---|---|---|---|
| 电子成绩单 | `GET /api/dzpz/transcript` | 类型选择 + 申请信息（「申请日期」就是 `defaultDate`） | 「一键申请」与那五步步骤预览：第 2~7 步没有端点（`TranscriptSource.canRunWorkflow = false`）；`params` / `submitParams` / `maindata` / `linkageUUID` / `signatureAttributesStr` / `signatureSecretKey` 六格**留空**（刻意不投影） |
| 考勤 | `GET /api/attendance/terms` + `/records` | 学期选择器 + 流水表（节次 / 状态 / 地点 / 日期） | 上课教师名（记录里有，但不投影 —— 红线）、姓名那一格（`getStudentInfo` 不端）、统计那一栏、打卡原始流水、请假那一栏（写路径）。那三栏在屏上写的是「本端拿不到 …」而不是「暂无 …」（`AttendanceCapabilities` 三档 `false`）；统计也不拿流水在本机重算 |
| 加餐券 | `GET /api/coupon/list` + `/detail` | 四档 + 翻页 + 金额（分）+ 有效期 | 封面图（要 egc 会话 ⇒ `loadImage` 给 `null`、券卡退化成图标）；「领取」按钮（写路径不端，`CouponSource.canActivate = false`）；金额不换算成元（除 100 是屏的事） |
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
- `?date=` 省略 = **今天**（`:core` 的 `todayInSystemZone`，服务端系统时区）；给了但不成形 ⇒ `400`。
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
| `GET /api/feedback/replies` | `?tickets=<逗号分隔，必填；一次最多认 20 个>` | `{tickets:[string], count:int, replies:[{ticket:string, text:string, time:string}]}` |
| `GET /api/feedback/public-qa` | ——（不收参数） | `{count:int, items:[{question:string, answer:string, category:string}]}` |
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
- **刻意不端的写路径**：`updateVoucherStatus(ids)`（`POST .../vouchers/updateVoucherStatus`）——
  屏上勾选加餐券时会把「这一轮用哪几张」**真的推给上游**。serve 模式下那个勾选动作**没有端点**，
  加餐券勾选的**宿主落盘**（`:core` 的 `PaymentSelectionStore`）也没有（serve 这一层没有宿主磁盘）；
- 「支付 / 扣款」这一档**上游客户端本来就没有**（`:data` 的 `PaymentCodeApi` 上一个那种方法都没有）：
  钱是商户扫了那串号之后在学校那边扣的，所以「只拿码」不是本轮砍出来的，是这一族**本来的形状**；
- 错误码：`401` 没会话 · `502` 上游/网络故障（含「校园卡未登录」那一档 —— `:data` 抛的是
  `RuntimeException("校园卡未登录，无法获取付款码")`，`FriendlyError` 按「应用自己写的中文短句」
  原样交回，与 §5.5 宿舍电费那条同口径）。**没有 `400`**：两个端点都不收参数。

**反馈（`feedback`）：只读两发；提交是写路径**

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
- ⚠️ **提交是写路径 ⇒ 这一轮不端**：`FeedbackSource.submit`（`POST .../records`）真往多维表格里
  加一行。serve 模式下反馈表单**没有端点**，于是「提交」那一半只能退回 GitHub 外链那条路
  （屏本来就有这条降级）；
- **凭据是运行期注入的**（这是这一族与别的族最不一样的一处）：反馈没有校园站点会话，「登录」是拿
  `app_id` + `app_secret` 换一张 `tenant_access_token`（见 `:data` 的 `FeedbackApi` KDoc）。两个
  窗口端的凭据是**构建期**烘焙的常量；serve 是部署在那台机器上的常驻进程 ⇒ `:server` 在启动时读
  环境变量 **`FEEDBACK_APP_ID` / `FEEDBACK_APP_SECRET` / `FEEDBACK_BASE_TOKEN`**（可选
  `FEEDBACK_TABLE_SUBMIT` / `FEEDBACK_TABLE_REPLY`；名字与 `desktop/build.gradle.kts` 那段
  `cfg()` 推导出来的逐字相同）注入给 `:data`（实现：`ServeFeedback`，在 `Main` 里调一次）。
  三个必填缺一个 ⇒ **如实「没配」**（`config = null`）⇒ 这两条端点按 `502` + 一句「去哪儿配」的
  中文短句报（**不是 404**：端点**在**，是这个部署没把凭据给它）；
- 错误码：`400` `tickets` 缺 / 空白 · `401` 无令牌（闸门那一条）· `502` 上游/网络故障，
  含「凭据没配全」那一档。

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
- `?from=` 省略 = `to` 往前 **90 天**；`?to=` 省略 = **今天**（服务端系统时区）；给了但不成形 /
  `from` 晚于 `to` ⇒ `400`；`scanned` 是**进统计的笔数**（窗口内的流水全表），`truncated` = 到页
  上限还没拉完（`true` 时那三格只覆盖了前几页，如实说）；
- ⚠️ **分享码那一半在浏览器端不可用**：这一族的终点是 `MatchProfile.encode` / `decode`，而它要的
  两条平台缝（deflate、Base64）在 **wasmJs 上不存在**（两个 actual 都如实返回 null）⇒
  `MatchProfile.codecAvailable == false` ⇒ **`:web` 上生成与解析分享码都不可用**。服务端**不**代它
  生成：码里含昵称与课表网格，是用户自己决定给谁看的东西（`MatchProfile` 的类 KDoc 就是为这条写的）；
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
| 付款码 `PaymentCodeScreen`<br>（取数 `CampusPayApi`） | `GET /api/pay/code` + `/api/pay/vouchers` 两条都真读：屏上显示那一串**数字**，加餐券那几行（名称 / 金额分 / 到哪天为止）也真画 | ① **条码 / 二维码矩阵一个都不画**：`qrCodeModules` / `code128Modules` 的 wasmJs actual 如实返回 `null` ⇒ 屏上换成一句「本端没有条码 / 二维码编码器，画不出可扫的码；下面这串数字就是付款码本身。」（不是画一张假的，也不是留两块空白让人以为图挂了）；② 券的**勾选框不画、整行不可点**：勾选同步 `updateVoucherStatus` 是写路径（本节「刻意不端的写路径」）⇒ 端口上 `PaymentCodeSource.canSelectVouchers = false`（默认 `true`，Android / 桌面一行未变），标题右边那一格改说「共 N 张 · 本端只读」（「未使用 / 已选 N 张」在只读这一档是编话：本端根本不知道上游那一头勾了哪几张）；③ 勾选落盘（`PaymentSelectionStore`）照旧接上（wasm 上是 localStorage），但本端是只读那一档 ⇒ 它只会读到一张空表 |
| 反馈 `FeedbackScreen`<br>（取数 `CampusFeedbackApi`） | `GET /api/feedback/replies?tickets=` + `/api/feedback/public-qa` 两条都真读：「大家在问」真画；工单号来自**屏自己的本地账本**（`:core` 的 `FeedbackStore`，wasm 上是 localStorage —— 这一端没有别的账本）⇒ 账本里有单号时「我提过的」那几张卡与它们的回复也真画 | ① **提交那一半整块表单不画**：`FeedbackSource.submit` 是写路径（本节「刻意不端的写路径」）⇒ 端口上 `FeedbackSource.canSubmit = false`（默认 `true`），屏上只留「去 GitHub 提 Issue」那条外链，并且把「只能看、不能提」写成一张卡 —— 与「凭据没配全」那一档**分开说**（那一档说的是「这个安装包没有应用内反馈」）；② 而**因为不能提交**，新浏览器里那份账本是空的 ⇒「我提过的」那一段不出现（只有账本里真有单号的人才看得到回复）；③ 部署要配 `FEEDBACK_*` 三个环境变量，否则两条端点一律 `502`，而屏把取数失败吞掉 ⇒ 页面呈现为「没有新回复 / 没有问答」—— 这一条写在页顶那句缺口说明里；④ 这一族**不套会话**，本端没有「先登录校园账号」那一步 |
| 匹配交友 `MatchScreen` | **一个端点都没读**（见右栏） | 这一屏在 `:web` 上落的是**如实说明页**（`WebMatchGapScreen`），不是一个半开着的交友界面：屏本身就在 `:core`（不是「还没搬过来」），用不了的是它的能力 —— `MatchProfile.codecAvailable` 在 wasm 上是 `false`（deflate 与 URL-safe Base64 两个 actual 都如实返回 `null`；浏览器那两份是流式异步 API，而分享码这条链在共享层是同步的）⇒ **生成与解析分享码都不可用**，而整屏的价值全在那两步上（本节「分享码那一半在浏览器端不可用」）。页面逐条写清缺什么：两件平台能力 / 学工档案那条链只在 `:app`（⇒ 服务端也没有 `/api/match/profile`）/ 考试与教材那两维本端没有缓存。⚠️ **`GET /api/match/dining` 这一轮在 `:web` 上没有消费方**：它是出码的一个**输入**，出不了码就没有地方用它 —— 单独把它画成一列就是半残界面（服务端端点与它的契约测试都在，只是这一端没有地方用）；真要接，得先把 deflate / Base64 搬进 wasm（`Deflate.wasmJs.kt` 的 TODO 记着两条路） |
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

---

## 8. 这份草案的依据（谁写的、读了什么）

- 读者：`serve` 模式的实现者（`:server` + `:web` 改造），以及每次把某个 `*Api` 搬进 `:data` 的那次提交。
- 依据（全部是只读核对）：`docs/desktop-port-plan.md` 的 D3/D5/D6 与 §3/§5/§6；
  `:core/core/net/` 那 17 个 campus-api 实现（它们的类 KDoc 里有逐条字段映射与"如实降级"说明；
  只读四域的四个是 2026-10-13 接线时补的，本文件草拟时那一族还是 13 个）；
  `:core` 里 12 个取数端口的接口 KDoc；`web/tools/serve-same-origin.py`（serve 模式的雏形）。
- 未核实的部分已标 `TODO`/`待确认`，**不要当成结论用**。