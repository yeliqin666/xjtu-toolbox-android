# serve 模式 `/api/*` 契约

`:server` 用同一份数据层（`:shared:data`）在无窗口进程里托管 `:web` 的 wasm 产物，并提供 `/api/*`。唯一消费者是 `:web`；Android 与桌面窗口模式与数据层同进程，直接函数调用，不走本契约。

每个端点都是某个 `*Api` / `*Source` 的薄包装：模型与解析口径由数据层定义，本文只规定怎么端出去，同一个字段不在两处各起一个名字。

## 运行

```bash
./gradlew :web:wasmJsBrowserDistribution
./gradlew :server:run                       # 默认 http://127.0.0.1:8123，启动时打印访问令牌
./gradlew :server:run --args="--https --host 0.0.0.0 --port 8443"
```

浏览器打开启动横幅里的地址，在「会话」一格贴上访问令牌再登录。`:web` 默认探测 `/api/status` 区分 serve 与 campus-api 两种后端，`?backend=serve` 可强制 serve；联调 campus-api 可用 `web/tools/serve-same-origin.py` 做同源反代。

| 参数 | 作用 |
|---|---|
| `--host` / `--port` | 监听地址与端口，默认 `127.0.0.1:8123`；`--port 0` 由系统挑（测试用） |
| `--dist` | wasm 产物目录，默认 `web/build/dist/wasmJs/productionExecutable`（工作目录是仓库根） |
| `--https` | 切到 HTTPS，`--host/--port` 即 HTTPS 的地址与端口 |
| `--cert` / `--cert-alias` | 证书库（PKCS12 / JKS）与别名，默认 `<数据根>/tls/serve.p12`、`serve`；省略 `--cert` 且文件不存在时用 keytool 现生成自签（SAN 含 `localhost`、`127.0.0.1`） |

- 证书库密码：自签时随机生成并存为 `serve.p12.password`（`0600`）；自带证书库用环境变量 `XJTU_TOOLBOX_TLS_PASSWORD` 传，不要写在命令行上。
- 证书缺失、密码不对、不是证书库、只给 `--cert` 不给 `--https`，一律报错退出（码 2），不回落成明文。
- 想换自签证书：删掉 `tls/serve.p12` 与 `.password` 后重启。换访问令牌：删 `serve.properties`。
- 反馈端点的飞书凭据在启动时从环境变量注入（见 `ServeFeedback`），没配全时反馈端点回 `502` 并给出指引。

## 安全口径

1. 默认只监听 `127.0.0.1`；对外暴露必须显式 `--host 0.0.0.0`。
2. 除 `/api/status` 外，所有端点都在令牌闸门内：`Authorization: Bearer <token>`，或由 `GET /api/session` 换出的 cookie `serve_token`（`Path=/; HttpOnly; SameSite=Lax`，HTTPS 形态多一个 `Secure`）。浏览器的实际用法是先用 Bearer 换 cookie，之后只带 cookie。
3. `/api/status` 只报「活着 + 有没有会话」，不含任何身份信息，是唯一的裸对象响应。
4. 对外暴露时用 `--https` 或自建反代。明文加非 localhost 是非安全上下文，`navigator.clipboard`、`crypto.subtle` 等直接不可用（`?route=probe` 页能看到当前部署的浏览器能力）；写与支付端点不要在明文公网口上用。
5. HTTPS 形态下内部明文监听只绑回环，令牌闸门与换 cookie 语义不变。

## 通用形状

- **信封**：`{ "code": 0, "data": <T|null>, "message": "…" }`。`code` 与 HTTP 状态码逐位相同，成功为 `0` + 200。失败时 `message` 是能直接给用户看的中文短句（`FriendlyError` 口径）。
- **错误码**：`400` 参数或请求体形状不对 · `401` 无令牌、未登录、凭据被拒或学校会话过期 · `404` 端点不存在 · `409` 已有登录在进行，或上游明确拒绝一次写操作 · `502` 上游或网络故障。
- **不走信封的例外**（成功时；失败仍是信封，且都在闸门内）：
  - `GET /api/venue/pay`：`302` 跳到场馆支付页；
  - `GET /api/dzpz/transcript/download?ticket=`：`application/pdf`；
  - `GET /api/jiaocai1/page`、`/api/jiaocai1/cover`：`image/jpeg`；
  - `POST /api/agent/chat`：`text/event-stream`（开流前的失败仍是信封）。

  新增例外属于契约变更，要写进这里。
- **时间**：ISO-8601 带时区字符串（服务端系统时区），不用 epoch。个别字段保留上游原文（如 `iclassface` 的 `time`），在对应模型的 KDoc 里注明。
- **分页**：`?page=<从 1 起>&size=<n>`，响应带 `page` / `size` / `total`。
- **空值**：`null` 表示上游明确说「没有」，字段缺失表示本端不投影，两者不混用。
- **能力开关**：涉及写操作的响应必须如实带上开关（`canBook` / `canCancel` / `canSubmit` / `canActivate` / `canSelectVouchers` / `availableSources` / `contactsAvailable` / `readOnly` 等），客户端按开关决定画不画按钮，不靠猜。

## 隐私红线

响应不含学生本人的学号、姓名、手机号，也不含通讯录。例外只有以下几处，都是公开数据或「我自己那条记录上」的人名：

1. `fitness/score` 的本人姓名与学号；
2. `info/yellowpage`、`info/faculty` 的公开电话与教师名；
3. `emptyroom/rooms` 实时档的上课教师名；
4. 请假一族：`leave/preview` 的审批人姓名、`leave/records` 的任课教师姓名；
5. `attendance/records` 流水里的上课教师名。

`accounts` 的 `id` 是打码学号（`2023****0001`），密码、指纹、公钥一律不投影。契约测试要在夹具里确实有这些值的前提下断言它们没出现。

## 端点

除 `status` 外均需令牌；标「需登录」的还要求 serve 进程里已有校园会话，否则 `401`。

| 前缀 | 端点 | 说明 |
|---|---|---|
| `status` | `GET` | 探活，免令牌，裸对象 |
| `session` | `GET` · `POST login` · `POST logout` · `GET/POST mfa` | 见下文「会话」 |
| `calendar` | `GET school` | 校历 |
| `info` | `GET yellowpage` · `GET faculty` | 黄页、教师检索（`contactsAvailable` 固定 `false`） |
| `fitness` | `GET years` · `GET score?year=` | 体测（需登录） |
| `notification` | `GET sources` · `GET list` | 通知聚合 |
| `inbox` | `GET` | 消息收纳（只读投影） |
| `emptyroom` | `GET cdn` · `GET rooms?source=direct\|live` · `GET seat-count?room=` | 空闲教室；未登录只有 `cdn` 档，座位数拿不到时为 `null` |
| `jwxt` | `GET terms` · `term` · `term-start` · `schedule[?merged=1]` · `changes` · `exams` · `textbooks` · `grades` · `school-courses` · `evaluations` · `evaluations/status` · `POST evaluations/submit` | 教务：课表（`merged=1` 为已合并调停课的版本）、考试、教材、成绩报表、全校课程、评教（QMS） |
| `jwapp` | `GET scores` · `precise-scores` · `score-detail?courseId=` | 移动教务成绩与教务精确成绩，两份不在服务端合并；无分项时 `detail: null` |
| `degree` | `GET progress` · `plans` · `plan` · `current-courses` · `POST recalculate` | 学业进度 |
| `library` | `GET campus` · `areas` · `seats` · `my` · `POST action` | 图书馆；`action` ∈ `seat` / `change` / `signin` / `leave` / `back` / `cancel` / `switch_campus` |
| `venue` | `GET products` · `slots` · `orders` · `status` · `captcha` · `POST book` · `POST cancel` · `GET pay` | 场馆；滑块挑战由服务端按值端出、浏览器自己画；`pay` 是 302，支付页是否需再登录不作保证 |
| `card` | `GET balance` · `GET transactions` | 校园卡 |
| `pay` | `GET code` · `GET vouchers` · `POST vouchers/select` | 付款码（借校园卡会话）；客户端本来就没有「支付」动作 |
| `coupon` | `GET list` · `GET detail` · `POST activate` | 加餐券，金额单位为分 |
| `dorm` | `GET rooms` · `GET reading` | 宿舍电费 |
| `dzpz` | `GET transcript` · `POST transcript/submit` · `POST transcript/preview` · `GET transcript/download?ticket=` | 电子成绩单，两段式：先提交拿 ticket，再下载 PDF |
| `attendance` | `GET terms` · `records` · `stats` · `streams` · `leave/window` · `leave/preview` · `leave/records` · `POST leave` · `POST leave/withdraw` · `POST leave/cancel` | 考勤与请假；附件上传没有端点 |
| `iclassface` | `GET records?date=` | 人脸签到流水 |
| `lms` | `GET courses` · `activities` · `activity` · `due` · `course-match` | 思源学堂；`due` 读前按 TTL 采集；附件、回放地址不端（需站点会话） |
| `jiaocai` | `GET search` · `GET ssno` | 教材查询 |
| `jiaocai1` | `GET classify` · `search` · `book` · `page` · `cover` · `shelf` · `POST shelf` · `POST shelf/progress` · `DELETE shelf/{ssno}` | 教材全文库；书架存在 serve 侧 |
| `downloads` | `GET` · `POST` · `GET progress` · `DELETE {id}` · `POST {id}/pause\|resume\|cancel` | 回放下载，队列与落盘都在 serve 那台机器 |
| `feedback` | `GET replies` · `GET public-qa` · `POST submit` | 反馈；无校园会话，工单号由调用方生成 |
| `match` | `GET dining` | 匹配交友的消费统计（只有用餐时段与常去食堂两维） |
| `bus` | `GET runs` | 校车 |
| `webvpn` | `POST convert` | WebVPN 网址互转，纯函数 |
| `accounts` | `GET` · `POST switch` · `add` · `delete` · `password` | 多账号；写操作要求 HTTPS |
| `agent` | `GET/POST config` · `POST models` · `GET/POST sessions` · `GET sessions/{id}` · `POST sessions/{id}/rename\|delete` · `GET memory` · `POST memory/forget\|clear` · `POST chat` · `POST stop` · `GET status` | 屁岱，见下文 |

逐字段形状以 `server/src/main/kotlin/.../*Routes.kt` 和 `server/src/test` 的契约测试为准。

### 会话

- 会话在 `:server` 进程里（`ServeSession`），浏览器不带任何校园凭据。登录成功后凭据落盘（与桌面同一份存储），登出时凭据、cookie、站点快照一起删。
- `authenticated` 表示「登录已跑完」或已从落盘凭据静默恢复；挂在短信验证上时仍为 `false`。恢复不联网验证。
- 同一时间只允许一个登录流程，并发的第二次请求回 `409`。
- MFA 用轮询：`GET mfa` 返回 `{pending, siteName, rejections, attemptsLeft}`（无挂起时后三项为 `null`）；`POST mfa` 交 `{"code"}` 或 `{"cancel": true}`，结论从下一次轮询或登录请求的响应里读。

### 屁岱

- API Key 只存在 serve 侧，浏览器只拿到 `keyConfigured`。对话循环与会话落盘都在 serve 侧。
- `POST chat` 的 SSE 事件：`delta` · `reasoning` · `tool_call` · `tool_result` · `usage` · `mfa` · `widget` · `nav` · `done` · `error`，外加保活注释帧。
- 浏览器端只开放白名单工具（`ServeAgentTools.NAMES`）：`get_calendar`、`get_schedule`、`get_exam_schedule`、`get_empty_rooms`、`search_yellow_page`、`preference`、`find_faculty`、`app_guide`、`calculate`。

## 契约测试与变更纪律

- 契约测试在 `:server:test`：起真 `:server`（端口 `0`），用 `:testkit` 的假上游灌数据，逐字段断言 HTTP 响应。`:shared:data` 的测试保证解析不漂，契约测试保证形状不漂，两者都要有。
- 改契约必须在同一次提交里同时改三处：本文、`:testkit` 夹具与契约测试、`:web` 的消费实现。夹具不一定每次都要改，但每次都要核对。
- `:web` 只认本文定义的形状，不认 campus-api 的字段名。
