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
  `409` 另一次登录正在进行 · `502` 上游或网络的故障（稍后重试可能就成）。
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
> 多一个字段 = 多一处口径，留到 `:web` 那个弹窗真开始画时一起加）；② `attemptsLeft` 依赖
> `SessionManager.MFA_MAX_ATTEMPTS`（那边是 `private`，`:server` 侧是同一数字的第二份写法，
> 改内核那个值要连它一起改）；③ `/api/session*` 的响应**只有**上面那些字段 —— 学号 / 姓名 /
> 手机号 / 账号类型一律**不投影**（与 `/api/status` 那条红线同一口径）。

> **TODO（每条一行，由搬它的提交补齐）**：请求参数的确切名字与默认值 · 响应字段的逐条形状 ·
> 与 `:app` 同一条上游样本的逐字段一致性证据（夹具）· 该端点的能力开关取值表。
> **已补齐的那一行**：`/api/session*` —— 逐条请求/响应形状、错误码、Set-Cookie 归属与两条 TODO
> 都写在 §5.1（由 `2026-10-11` 那次「serve 第二步」的提交补齐）。

### 5.2 P0 取数端点（2026-10-11 落地，`:server` 实现，测试 33 例钉住）

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
| `GET /api/calendar/school` | —— | `{terms:[{name,startDate,endDate,remark?}]}` | `SchoolCalendarSource.terms`，开学日起序 |
| `GET /api/info/yellowpage` | —— | `[{dept,name,phone?}]`（或 `{departments:[…]}`，逐位置照 `YellowPageSource`） | MockEngine 夹具 |
| `GET /api/info/faculty` | `?name=&dept=&page=&size=` | `{page,size,total,list:[…],contactsAvailable:false}` | 不解析主页、不传 `contacts=1`（serve 只读）；`contactsAvailable:false` 固定 |
| `GET /api/fitness/years` | —— | `[{year,checked}]` | `FitnessSource` |
| `GET /api/fitness/score` | `?year=` | `{studentNo,studentName,year,score,level,…}` | **本人数据**（例外①）：姓名/学号可投影 |
| `GET /api/notification/list` | `?page=&size=&source=&all=1` | `{page,size,total,items:[…]}` | `size`=本次响应条数（`NoticeSource` 无页大小概念，非服务端分页窗） | 
| `GET /api/notification/sources` | —— | `[{id,name,type,…}]` | 29 个源 |
| `GET /api/inbox` | —— | 四路聚合（消息/事务/预约/校车，逐位置照 `InboxSource`） | 读侧；`markRead` 等写路径属 Stage B |
| `GET /api/emptyroom/cdn` | `?campus=&date=&from=&to=` | `{availableSources:[…], rooms:[…]}` | CDN 档 |
| `GET /api/emptyroom/rooms` | `?campus=&date=` | `{availableSources, live?:{…}, direct?:{…}}` | `availableSources`：未登录 `[cdn]`；登录后 `[live,cdn,direct]` |

**TODO（拍板过，未做）**：`emptyroom/rooms` 的 `from/to`（节次过滤）——`:data` `EmptyRoomDirectQuery` 只给全天，留待上游加维度；
`faculty` 的 `?filters=1`（筛选项表四张，`:data` 能解析但契约未定）与 `fitness/score` 的 `?year=all`（全学期聚合）同理；
时间戳一律服务端时区。

### 5.3 P0 需登录六域（2026-10-11 落地）

| 端点 | 请求 | 响应 `data`（逐字段，节选） | 备注 |
|---|---|---|---|
| `GET /api/jwxt/terms` | —— | `[{name,startDate,endDate,current?}]` | 学期名兜底 = `DM` 字段 |
| `GET /api/jwxt/term` | —— | 当前学期对象（形状同上） | `ScheduleSource` 一族 |
| `GET /api/jwxt/grades` | `?term=&all=1` | `{page,size,total,grades:[…]}` | 默认全量；`:data` `ScoreReportSource` |
| `GET /api/jwxt/school-courses` | `?course=&code=&teacher=&campus=&weekday=&from=&to=&page=&size=` | 见下节 | **要改**（契约§5）：补人数/学时、`YPSJDD`、开课单位、公选筛选（`:data` `AppSchoolCourseSource` 已能取的投影，取不到标 TODO） |
| `GET /api/jwxt/evaluations` | `?terms=&type=&finished=` | `{canSubmit:false,items:[…]}` | `canSubmit` 必须出现、如实 `false`（本实现只看不认不投） |
| `GET /api/jwxt/evaluations/status` | —— | `{canSubmit:false}` | 提交/撤销（P1）、研究生 gste/gmis 路（P1） |
| `GET /api/library/campus` | —— | `{current:{code,name}|null, campuses:[{code,name}], queryableFloors:[…]|null, canBook:true, hasSeatPlan:true}` | `current:null`=认不出不猜；`canBook:true` 如实（:data 写路径真） |
| `GET /api/library/areas` | `?campus=&floor=` | `{floor,areaCount,areas:[{code,name,floor,available,total}],totals:{…}}` | `available/total:null`=scount 无此区域（不拿 0 冒充「满了」） |
| `GET /api/library/seats` | `?area=&time=` | `{area,total,available,seats:[{seatId,available}]}` | 排序与 `:app` 同；**`time` 收了但上游无时间维**（TODO） |
| `GET /api/library/my` | —— | `{my:{seatId,area,statusText}|null}` | `my:null`=页面明确没预约（§4 null≠空对象）；`actionUrls` 不投影（指向 rg.lib，点了会失败，P1 `/api/library/action` 未实现） |
| `GET /api/card/balance` | —— | `{balance,pendingAmount,lostFlag,frozenFlag,expireDate,cardType,department}` | 红线：`account/name/studentNo` **不投影**；挂失口径选 **`barflag`**（`:data`/`:app` 读法，不选 campus-api 的 `lostflag`） |
| `GET /api/card/transactions` | `?from=&to=`（必填 YYYY-MM-DD，反序 400）`&page=&size=` | `{page,size,total,transactions:[{time,merchant,amount,balance,type,description}]}` | `amount` 带符号（负=支出）、`description`←resume |
| `GET /api/venue/products` | —— | `{venues:[{id,name,address?,iconType?,advanceDay,advanceNum}]}` | 拉页到不足为止 |
| `GET /api/venue/slots` | `?serviceId=&date=`（必填 400） | `{serviceId,date,slots:[{areaDetailId,areaName,stockId,timeSlot,price,date,allCount,usingNum,surplus,serviceid,isAvailable}]}` | **`surplus` 读法 = `(all-used).coerceAtLeast(1)`、`all==used` 给 1 不给 0**（旧字段废弃） |
| `GET /api/venue/orders` | `?page=&size=` | `{page,size,total|null,orders:[{orderId,status,statusText,createdAt,price,venueName,canPay,canCancel,details:[…]}],hasMore}` | |
| `GET /api/venue/status` | —— | `{canBook:false, canCancel:true, browserLoginUrl}` | `canBook:false` 如实（serve 无滑块宿主，与桌面端同口径）；`/book` `/cancel` 是 P1 不实现 |

**TODO（拍板过，未做）**：`library/seats?time`（上游无时间维）、`library/my.actionUrls` 与 `/api/library/action`（P1 写）、
`venue/book` `/cancel` 与 `canBook:true`（P1 写，须先解决滑块宿主）、`card` 挂失（P1 写）、`evaluations` 提交/撤销（P1）。

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
