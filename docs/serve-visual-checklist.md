# serve 真站点逐屏点位清单（验收）

> 口径：会议 D9（`docs/desktop-port-plan.md` §16.0）——**实现方列清单、用户照单点**；点不通的由用户回报【现象 + 截图 + 哪一格】，实现方查。
> 验收基准：**这是重构，不是功能更新 —— Android 怎么做，web 就怎么做**（§16.5 D15）。清单里的「期望」= 与 Android 同口径，不是「应该更好用」。
> 本清单只描述现状与期望，不是「这些功能都该做」的排期。

---

## 怎么用（打开 serve 那一页）

```bash
TOKEN=$(grep -E '^access_token=' ~/.local/share/xjtu-toolbox/serve.properties | cut -d= -f2-)
IP=$(hostname -I | awk '{print $1}')
echo "http://$IP:40961/?token=$TOKEN"
```

浏览器打开打印出的那一串（**令牌不要外传、不要截图、不要写进聊天/书签**）。点错了就在下面清单里找到那一行，回报【现象 + 截图 + 哪一格】。

---

## 前置条件

### 1. 先判断 serve 跑的是哪种形态 / 有多旧

```bash
curl -s http://127.0.0.1:40961/api/status
```

- serve 形态：**裸对象、不含身份**，如 `{"authenticated":true,"uptimeSeconds":…}`（没有 `username` 字段）。
- campus-api 形态：同名端点**带 `username`**——这是两条路在同一个路径上唯一稳定的差别（`docs/api-contract.md` §5.4）。
- `uptimeSeconds` = 进程已跑秒数。**比最新构建还早 = 大概率要重启 serve**（见下一节）。
- 页面自己会探测；书签/排障可加 `?backend=serve` 钉死 serve 模式（§5.4）。

### 2. 哪些点位要**先重启 serve** 才生效

静态页面（`index.html` / `.wasm` / `toolbox-web.js`）是 serve 从盘上 `web/build/dist/wasmJs/productionExecutable/` 直接吐的 ⇒ **重建即生效、不用重启**（§15.6.1）。
但 `/api/*` 端点编译在 `:server` 的 JVM 里 ⇒ **新端点要「重新构建 + 重启 serve 进程」才会存在**，旧进程对新端点答 `404 {"code":404,…,"message":"接口不存在"}`。

带令牌探一发即可判断（404 = 进程太旧，401 = 端点在了但没登录，200 = 就位且已登录）：

```bash
TOKEN=$(grep -E '^access_token=' ~/.local/share/xjtu-toolbox/serve.properties | cut -d= -f2-)
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:40961/api/jwxt/schedule?term=&merged=1"
```

以下端点族都是**新加的**，对应的屏要「重启后的 serve」才有数据（否则整屏落「需要 serve 模式」页或栏位空着）：

| # | 端点族（契约节） | 受影响的清单点位 |
|---|---|---|
| 1 | 只读四域（§5.5）：`/api/dzpz/transcript` · `/api/attendance/{terms,records}` · `/api/coupon/{list,detail}` · `/api/dorm/{rooms,reading}` | ⑥ 只读四域全部四屏 |
| 2 | 第二批（§5.6）：`/api/lms/{courses,activities,activity}` · `/api/jiaocai/{search,ssno}` · `/api/jwapp/{scores,precise-scores}` · `/api/iclassface/records` | ⑦ 第二批四屏全部 |
| 3 | 第三批（§5.7）：`/api/pay/{code,vouchers}` · `/api/feedback/{replies,public-qa}` · `/api/match/dining` · `POST /api/webvpn/convert`；**批③-b / D7（2026-10-15）加** `POST /api/feedback/submit` · `POST /api/pay/vouchers/select` | ⑧ 付款码（拿码 + 勾选券）/ 反馈（读 + 提交）/ WebVPN（匹配屏不消费端点） |
| 4 | 第四批（§5.8）：`/api/jwxt/{changes,exams,textbooks}` · `/api/jwapp/score-detail` · `/api/emptyroom/seat-count` | ③ 日程（考试栏/教材栏/N座）· ⑦ 成绩屏的分项 · 教材屏「本学期我的教材」 |
| 5 | 批①收尾（§5.9）：`/api/jwxt/schedule?merged=1`（同一端点的新参数档） | ③ 日程「格子已合调停补课」 |

> 场馆 / 图书馆两条**写**端点（`§5.3.2 /api/venue/*`、`§5.3.1 /api/library/action`）是 2026-10-11/12 落地，若进程早于那两天同样要重启。判据同上：带令牌 curl 一个写端点看是 404 还是 401。
> 场馆「去支付」那一跳（`GET /api/venue/pay`，批③-a / D11b，契约 §5.3.2 第 6 条）是 **2026-10-15** 落地的：
> 进程早于这一天时点它会拿到 `404 {"code":404,…}`（而不是场馆那一族那种 401）—— 那一格就是⑨里的「场馆去支付」。

### 3. 哪些点位要**真账号登录**

serve 形态下，会话在 serve 进程里。要走登录的点位（AppRoute 的 `loginType`），用户得先在**会话屏**（`?route=serve`，或「学辅」页的「打开会话屏」按钮，或「过不了闸门自动落过去」）走 `POST /api/session/login` + 短信二验：**日程 / 成绩报表 / 全校课表 / 评教 / 校园卡 / 图书馆 / 场馆 / 体测**，以及**只读四域 + 第二批 + 第三批里借站点会话的那几屏**（成绩单 / 考勤 / 加餐券 / 宿舍电费 / 思源学堂 / 教材查询 / 成绩 / 快速考勤流水 / 付款码）。免登录可直接开的：校历 / 黄页 / 通知 / 教师检索 / 空闲教室 / 社区 / 小游戏 / 设置。

---

## 主清单（照单点）

每行 6 栏：`屏幕/路由 id` | `入口怎么点到` | `点哪里` | `期望看到什么` | `不通过的典型现象` | `已知缺口（如实）`。
`?route=<id>` 深链写的是 `AppRoute.id`（见 `core/.../nav/AppRoute.kt`）。

### ① 外壳

- [ ] 底栏五格（逐字一致） | 打开首页，看底栏 | 看五格的顺序 / 文字 / 图标 | 五格依次 **首页 / 日程 / 屁岱 / 学辅 / 我的**，图标 Home / CalendarMonth / SmartToy / MenuBook / Person | 多一格、少一格、顺序或文字与 App 不同 | 无（这是阶段 C 的验收点，`WebShell.kt` 与 `:app BottomTab` 逐字一致）
- [ ] 宽窗变侧栏 | 把窗口拉宽到短边 ≥600dp（或最大化） | 看底栏形态 | 底栏换成**左侧栏**，五格竖排 | 仍是最底下一横条 | 判据 `:core` 的 `calculateIsWideLayout`（与 Android 宽屏/折叠屏同一套）
- [ ] 窄窗回到底栏 | 再把窗口拉窄 | 看底栏形态 | 变回 miuix **经典底栏**（`glassStyle=false`，无玻璃风） | 侧栏不消失 / 出现玻璃底栏 | 无
- [ ] 当前页恢复（刷新） | 打开首页后刷新页面（F5） | 看落在哪一页 | 落**日程**（`?route=` 为空时默认 `AppRoute.Schedule`） | 白屏 / 落错页 / 一直「正在探测数据源…」超过 3 秒 | 探测超时按 campus-api 兜底（3s），不会无限白屏
- [ ] 深链直接进屏 | 地址栏加 `?route=school_calendar` 回车 | 看当前页 | 直接打开**校历**屏，底栏「首页」格不高亮（它不属于任何一格） | 落回日程 / 显示「认不出」 | 认不出的路由兜底落日程（与 `:app` `appRouteOf` 返回 null 同口径）

### ② 首页

- [ ] 宫格 = 入口（36 条） | 打开首页，看宫格 | 数/看宫格条目 | 宫格 = `:core` 服务目录 × `WebSupport.openableRouteIds`（36 条）；**点得进去的才画**，打不开的入口不画 | 出现一个点了报错/落占位页的格子；或缺了很多本该有的入口 | 上一轮的漏正是「屏在、门不在」，现在门与宫格同源（`WebSupport`）
- [ ] 问候不写名字 | 看首页顶部问候 | 看问候文案 | 只有问候 + 日期（`dateLabel` 如「10月14日 · 周二」），**没有名字** | 出现昵称/姓名 | 本端没有账号（`greetingName=""`），凭据在 campus-api / serve 进程里
- [ ] 「第 N 周 / 下一项安排」 | 首页中部 | 看这两格 | 真算（`HomeHero.weekNumber` / `HomeHero.find`）：算出周次、给出下一项；算不出给三种说法之一——「正在读取本机课表…」/「课表还没同步 · 点这里去同步」/「接下来两周都没课 · 点这里看整学期」 | 一直「正在读取…」不散；或把「没同步」说成「没课」 | 与桌面/`:app` 判据逐条一致（`WebHomeHost.nextUp`）
- [ ] 「待办与消息」那一格 | 首页 | 看那一格 | 有真待办/未读才画，读 `:core` 的 `InboxStore`（wasm 上 localStorage）；没有就不画（**不画个 0**） | 画出一个空的「0」格 | 无
- [ ] 不画的块（如实） | 首页扫一遍 | 看有没有这三块 | **不出现**：顶部公告条（BULLETIN_BAR）、速览格（GLANCE）、常用功能（QUICK_ACTIONS） | 出现空的公告条/速览格/随机常用功能 | 三块缺宿主面 ⇒ 一条都不画（`WebHomeHost.honoredCards` 只列六块）

### ③ 日程

> serve 形态 = 与 Android/桌面同口径；campus-api 形态 = 只排课原样。页顶有一行说明把两种形态分开写（`WebScheduleGapNote`）。

- [ ] 页顶说明（serve） | 日程页顶部 | 读那行 11sp 说明 | 开头「这一份日程与 Android / 桌面同口径：格子里的课**已经合过调课 / 停课 / 补课**（/api/jwxt/schedule?merged=1 端的就是 :data 已合并的那一份），考试与教材两栏也真取数。」 | 说明写「只有排课原样」 | 该句在 §5.9 接线后已收紧
- [ ] 格子已合调停补课 | 日程格子里看一门被调/停/补过的课 | 看今天/本周那门课 | 调课换到新时间新地点、停课被挖掉、补课出现在新位置（与 Android 同一张表） | 仍是排课原样（调课没体现） | campus-api 形态无 `merged` ⇒ 仍是原样；合并走服务端 `?merged=1`，本端一行没自己合
- [ ] 考试栏 | 日程页考试栏 | 看内容 | 真取数（`/api/jwxt/exams`，六格：课程/课程号/日期/时间/地点/座号），倒计时横幅 + 完整列表 | 栏空着且无说明 | campus-api 形态取不到 ⇒ 空着（字段面不认，§7.2）
- [ ] 教材栏 | 日程页教材栏 | 看内容 | 真取数（`/api/jwxt/textbooks`，八格 + `hasTextbook`），「无教材」排在后面 | 栏空着 | campus-api 形态取不到 ⇒ 空着；学号由服务端从会话取（请求不含学号）
- [ ] 详情「N 座」 | 点开一门课的详情 | 看「N座」那一格 | serve 形态真显示「N座」（`/api/emptyroom/seat-count`）；`seats:null` 时**不画那一格**（不写成「0座」） | 显示「0座」；或 serve 形态也不出现 | campus-api 形态 `honored` 不列 `SEAT_COUNT`（列了是假开关）
- [ ] 永远不画的三处 | 日程页工具栏/详情/今日栏 | 看这三处 | **不出现**：①「导出日历 (ICS)」② 详情里的「思源学堂」「本次考勤」下钻 ③ 今日栏「接下来」的作业截止 | 出现点了没反应的按钮 | 三处两种形态都做不到（无系统分享面板 / 要那两个站会话 / 要思源落盘缓存）
- [ ] 自建日程 | 工具栏「+」/「编辑日程」 | 加一条自建日程 | 真落盘（`KeyValueCustomCourseStore` → localStorage），刷新还在 | 加了刷新就没了 | `CUSTOM_COURSE_EDIT` 两种形态都列

### ④ 学辅

- [ ] 可点目录 | 底栏第四格「学辅」 | 看目录 | 标题「学辅（Web 这一端）」；按 `ServiceCategory` 分组列出**本端真能点开的屏**（一行一屏，点一行进那一屏） | 纯文字说明页 / 列了但点了不动 | 与首页宫格共用 `WebSupport.openableCatalog` 同一真源
- [ ] 「本端还没有这一屏」如实列出 | 学辅页往下翻 | 看那一段 | 一行小字列出「目录里有、这一端还没有屏的」（`unsupportedCatalog`），**点不动** | 把这些当成能点的 | 该段是「如实」，不是漏
- [ ] 会话入口（serve） | 学辅页「Web 专有的一屏」段 | 看有没有「打开会话屏」按钮 | serve 形态有「打开会话屏」按钮，点了进会话屏（登录/二验/登出）；campus-api 形态显示「页面上没有登录这回事」 | serve 形态却没有这个按钮 | 会话屏不进五格（进任何一格都会破坏「五格逐字一致」）
- [ ] 后端自检 | 学辅页底部「后端自检」按钮 | 点它 | 进自检屏（`?route=probe`），列出本端能开/不能开的清单 | 404 / 白屏 | 无

### ⑤ 我的

- [ ] 设置屏（不是账号页） | 底栏第五格「我的」 | 看当前页 | 落在 `:core` 共享**设置屏**（本端没有账号这回事），版本行写「Web 构建（wasm）」 | 落成账号页 / 显示 `vX.Y.Z` 版本号 | `WebSettingsHost.versionText` 如实写自己是哪份构建
- [ ] 深色模式当场生效 | 设置页「深色模式」 | 改成「始终深色」 | **当场**整页变深色（`onAppearanceChanged` 让外壳重读 `SettingsStore.darkMode`） | 改了不变色 / 要刷新才变 | 键值缝无监听器，靠回调重读（与桌面同写法）
- [ ] 首页主题 | 设置页「首页主题」 | 切卡片/图标主题，回首页 | 首页宫格在两套布局间切换（读 `SettingsStore.homeTheme`，键名与 `:app` 相同） | 改了首页没变 | 本端一次只组合当前页，回首页重新读一次
- [ ] 不列的行（如实） | 设置页扫一遍 | 看少了哪些行 | **不出现**：动态取色 / 常用功能 / 玻璃·经典 / 启动 Tab / 触感 / 网络模式 / 课表来源 / 考勤角标 / 场馆自动识别验证码（九项各有「列了就是假开关」的理由） | 出现一个改了没反应的开关 | `WebSettingsHost.honoredRows` 只列 `DARK_MODE` + `HOME_THEME`

### ⑥ 只读四域（成绩单 / 考勤 / 加餐券 / 宿舍电费）

> serve 形态才画真屏；campus-api 形态四屏都落「（Web 这一端：需要 serve 模式）」如实页（`WebServeOnlyScreen`）。

- [ ] 电子成绩单 | `?route=transcript` | 看整屏 | serve：类型选择 + 申请信息（「申请日期」=`defaultDate`），页顶缺口说明写「只画得了『选类型』这一步」，**没有**「一键申请」和那五步预览 | campus-api：落「电子成绩单（Web 这一端：需要 serve 模式）」页 | 第 2~7 步无端点（出 PDF 要 App/桌面端）。**D7 ⑤ 查过并于 2026-10-15 如实不端**：`:data` 侧有 PDF 能力，但那是**真的替用户提交一份申请**（要签章 + 审批）而浏览器这一侧没有“二次确认”这一格 ⇒ 依据与最小可行方案见契约 §5.5 末尾
- [ ] 考勤 | `?route=new_attendance` | 看整屏，走一遭请假 | serve：学期选择器 + 流水表（节次/状态/地点/日期）；**「请假」那一栏真能用**（窗口 / 审批人下拉（**入的是人名，不是 `userId`**）/ 列表 / 提交 / 撤回 / 销假）；页顶写缺「**流水那一栏的**上课教师名/统计/打卡原始流水/附件」 | campus-api：落「考勤（…需要 serve 模式）」页 | 考勤**流水那一栏**的上课教师名不投影（红线）；请假那一族的两类姓名（审批人姓名、请假单里那节课的任课教师姓名）自 2026-10-15 起按 §5.2 例外 ④ 投影；`status=-1` 按「未知」显示；附件那一格没有端点（本端没有文件选择宿主，与桌面同一条）
- [ ] 加餐券 | `?route=coupon` | 看整屏，领一张 | serve：四档 + 翻页 + 金额（单位分）+ 有效期；「可领取」那一档的券卡里**有**「领取」按钮且**点了真能领**（领完重拉当前档）；券卡退成图标（无封面图） | 封面图当成了真图 / 点了「领取」没反应 | 封面图要 egc 会话；领券被上游拒与会话失效**都落 502**（分不出「上游说不能领」，见契约 §5.5）
- [ ] 宿舍电费 | `?route=dorm_power` | 看整屏 | serve：房间列表 + 剩余电量（`kwh:null` 单独说「这一间没有电表」）+ **本机**查询时刻；页顶写「去缴费在浏览器里不可达」 | campus-api：落「宿舍电费（…需要 serve 模式）」页 | 用的是 `:web` 自己的读数页 `WebDormPowerScreen`，不是 `:core` 那张缴费页浏览器

### ⑦ 第二批四屏（思源学堂 / 教材查询 / 移动教务成绩 / 人脸签到）

- [ ] 思源学堂 | `?route=lms` | 看整屏 | serve：课程卡（名称/课程号/学分/学期/教学班/人数）+ 活动列表 + 详情；教师名显示「未知」；附件/播放地址/提交记录**不画** | campus-api：落「思源学堂（…需要 serve 模式）」页 | 教师名不投影（红线）；附件预览下载/播放/提交记录都不端
- [ ] 教材查询 | `?route=jiaocai` | 看整屏 | serve：「查教材」一栏（关键词输入 + 结果卡 + 详情）+【本学期我的教材】一段（走 `GET /api/jwxt/textbooks`）；**没有**「书架/全文库」两栏、没有「读全文」入口 | campus-api：落「教材查询（…需要 serve 模式）」页 | 全文库（jiaocai1）另一域名另一套会话不端；ssno 列表里恒空、进详情才补
- [ ] 移动教务成绩 | `?route=jwapp_score` | 看整屏 | serve：学期成绩 + GPA 卡 + 选课算均分（两份上游合并）；「点开一门课看分项」serve 下能展开（`/api/jwapp/score-detail`） | campus-api：落「成绩（…需要 serve 模式）」页 | 未评教名单/按学号补报表两路不接（学号红线）；`precise-scores` 502 可容忍、只出 jwapp 那份
- [ ] 快速考勤流水 | `?route=iclassface` | 看整屏 | serve：日期条 + 一天一张卡（时刻/地点/类型），**不画姓名学号**；时间是**服务端墙上时间**（不做本地时区转换） | campus-api：落「快速考勤流水（…需要 serve 模式）」页 | 姓名学号红线不投影；上游无照片字段；无分页

### ⑧ 第三批四屏（付款码 / 反馈 / 匹配交友 / WebVPN）

- [ ] 付款码 = 真出码 + 勾选真同步 | `?route=payment_code` | 看整屏，拿手机扫一下那张码，再勾一张券 | serve：**二维码 + Code128 条形码都真画**（与 Android 同一张矩阵：D12 ① 把编码器搬进了 `:core` 的 commonMain），手机扫出来就是**付款码那串数字**；加餐券几行（名称/金额分/到期）**有勾选框、勾了真同步**（POST /api/pay/vouchers/select） | 二维码/条形码画不出来（或扫码枪/手机扫不出那串数字）、券勾了不生效 | campus-api：落「付款码（…需要 serve 模式）」页 | 三端编码器现在是同一份纯 Kotlin 实现（`QrCodec` / `Code128Codec`，与 zxing 逐模块相同）；「支付」那一档**上游客户端本来就没有**（不是缺口）；⚠️ 这一屏是**能付钱的东西**，`0.0.0.0` 部署要先解决鉴权（§3.3）
- [ ] 反馈：读 + 提交 | `?route=feedback` | 看整屏，提一条 | serve：「大家在问」真画 + 提交表单**真画**（能提交，提完那条工单进本地账本、「我提过的」出现）+「去 GitHub 提 Issue」外链仍在 | 提交按钮点了无反应 / 仍显示「只能看、不能提」那张卡 | 提交要 serve 配全 `FEEDBACK_*` 三环境变量，否则三条端点 502（含提交那一枪）且屏吞掉 ⇒ 显示「没有新回复/问答」；工单号是**屏生成**的（换浏览器找不回旧工单，服务端不代管也不下发新号） |
- [ ] 匹配交友真屏 | `?route=schedule_match` | 看整屏；点「复制」拿自己的码，再把它粘回「对方的码」点「算一算」 | **与 App / 桌面同一张共享屏**（`:core` 的 `MatchScreen` + 本端 `WebMatchHost`）：二维码真画、码真出、粘回来真算（离线算，一条请求都不发）；课表那几维来自本页 localStorage（先逛一次日程页） | 出不了码 / 二维码画不出来 / 自己的码粘回去解不开 | 「吃」与「人」那两维本端**没有数据源**（校园卡流水与学工档案都没有落盘）⇒ 分享设置里那两项是禁用态；**扫码那一档没有**（浏览器相机属 D12 ③）⇒ 只留「粘一段文字」；服务端不代出码（码含昵称+课表网格）
- [ ] 匹配交友真屏 | `?route=schedule_match` | 看整屏；点「复制」拿自己的码，再把它粘回「对方的码」点「算一算」 | **与 App / 桌面同一张共享屏**（`:core` 的 `MatchScreen` + 本端 `WebMatchHost`）：二维码真画、码真出、粘回来真算（离线算，一条请求都不发）；课表那几维来自本页 localStorage（先逛一次日程页） | 出不了码 / 二维码画不出来 / 自己的码粘回去解不开 / 点「复制文字」没反应（看下面缺口那一格） | 「吃」与「人」那两维本端**没有数据源**（校园卡流水与学工档案都没有落盘）⇒ 分享设置里那两项是禁用态；**扫码那一档没有**（D12 ③ 查清：D11 之后「安全上下文」这个前置已经有了，真正缺的是**浏览器里没有二维码解码器** —— 编码器搬进了 `:core`，解码那一侧（`QrBitmap.read`）还在 JVM 上、`decodeImagePixels` 在 wasm 上恒 null，桌面 Chrome 也没有 `BarcodeDetector` ⇒ 最小可行方案见 §16.11）⇒ 只留「粘一段文字」；另外那几颗**复制按钮只在安全上下文里真能用**（非安全上下文时 CMP 只 `console.warn`，点了没反应、也不报错 —— 拿 ⑪ 那张能力表可以当场核）；服务端不代出码（码含昵称+课表网格） |

### ⑨ campus-api 形态 vs serve 形态 的差别

- [ ] 判断两种形态 | 见「前置条件 1」`/api/status` | 看响应 | serve = 裸对象无 `username`；campus-api = 带 `username` | 分不清 | 页面自己探测，`?backend=serve` 可钉死
- [ ] 只读四域 + 第二批 + 第三批在 campus-api 下 | 分别打开这 12 屏 | 看落点 | 全部落「（Web 这一端：需要 serve 模式）」页，写清端点 + 为什么 + 「切到 serve 模式就能用」 | 落「这一屏还没搬到 Web」/ 白屏 | 这些域在 campus-api 里没有端点，屏在 `:core`（不是没搬）
- [ ] 日程在 campus-api 下 | 打开日程 | 看页顶 + 格子 | 页顶写「只有排课原样…考试与教材两栏空着、详情里的『N座』也不画；调课/停课/换教室同样不体现」 | 显示成与 serve 一样 | 本端只认 serve 契约字段面（§7.2）
- [ ] 图书馆/场馆在两种形态 | 打开 library / venue | 看可点性 | campus-api：只读（无约座/预订按钮，时段不可选）；serve：写端点落地 ⇒ 能约座/预订/取消（滑块在本端画） | campus-api 出现点了会失败的写按钮 | 能力开关随形态变（§2「点了会失败的按钮一个都不画」）
- [ ] 场馆「去支付」（**可用**，D11b） | serve：订完场（或「我的订单」里那一行）→ 点「去支付」 | 点「去支付」 | 新标签先到 serve 的代跳地址 `GET /api/venue/pay?orderId=…`（302，不当信封），随后落在站点 80 端口那台的 `pay/show.html?id=<订单号>`（顶层跳转，浏览器一律跟）；扣款在那一页上完成。⚠️ **那一页要不要再登录，本端不担保** —— 它要的是**浏览器这一侧**的场馆站会话，而 serve 的会话带不过去（页面替不了别的域名写 cookie；Android 能是因为 WebView 在同一个进程里，被 `syncCookiesToWebView` 灌了 cookie 罐）⇒ 第一次很可能先看到**该站点自己的登录页**：**若它要求登录，请用 App / 桌面端支付**（或按那一页自己的登录流程过一次再回来） | 点了根本没动作 / 新标签白屏 / 落一个写着「接口不存在」的 JSON 页（= serve 进程太旧，见「前置条件 2」） | **302 这一跳一定走完，但落成能付款的一页不担保** —— 三条依据在契约 §5.3.2 第 6 条；`browserLoginUrl` 仍是空串；「第一次点到底落在哪一页」本仓**没能在线验证**（不许真发起支付、也没探测那台站），以你点出来的现象为准

### ⑩ 明确不可用项（逐个为什么）

- [ ] 屁岱（`Agent`） | 底栏第三格「屁岱」 | 看落点 | 落降级页「屁岱（Web 这一端）」，写清缺 `AgentHost` 四件（配置/会话/记忆落盘、流式传输、工具层） | 出现聊天界面 | 设计稿 `docs/agent-web-spec.md` 未实现（循环要跑在 serve，D10）
- [ ] 下载管理（`DownloadManager`） | `?route=download_manager` | 看落点 | 落「这一屏还没搬到 Web」+「AppRoute.DownloadManager」 | 出现下载列表 | 取数挂 okhttp/Context
- [ ] 教材全文（`Jiaocai1`） | `?route=jiaocai1` | 看落点 | 落「这一屏还没搬到 Web」 | 出现全文库书架 | 另一域名另一套会话，本轮不碰（D13 之后要做）
- [ ] 教材全文阅读器（`Jiaocai1Reader`） | `?route=jiaocai1_reader/<ssno>?title=` | 看落点 | 落「这一屏还没搬到 Web」 | 出现阅读器 | 同上
- [ ] 账号管理（`Accounts`） | `?route=accounts` | 看落点 | 落「这一屏还没搬到 Web」 | 出现账号页 | 多账号归批②（D9）
- [ ] 内置浏览器（`Browser`） | `?route=browser?url=…` | 看落点 | **不是一屏**：同标签直接导航到那个网址（`navigateSameTab`），回来靠浏览器后退 | 出现一个「浏览器」页面 | 它把 URL 交给浏览器本身，不算一屏（口径上不算差集）
- [ ] 桌面端「GameMerge 载体」 | 桌面端看合成西交大 | —— | 桌面**仍卡载体**（无浏览器内核、也没有 JVM 上的 JS 引擎） | —— | 见 §16.2 第 1 条与§⑪（web 端已是真游戏，桌面那一端本批一行未改）

---

### ⑪ 本轮点亮的：合成西交大在浏览器上真能玩 + 浏览器能力自检（D12 ⑤ / ③，2026-10-15）

- [ ] 合成西交大（`GameMerge`） | 首页宫格「小游戏」→ 合集第一张卡「合成西交大」（或 `?route=game_merge`） | 看整屏，玩一局 | **真跑起来**：顶栏「合成西交大」+ 返回箭头下面就是**Android 那一张游戏**（同一个 HTML + `index.js` + 本地 Matter.js + 9 张校徽 webp，构建期从 `:app` 的 assets 原样拷进 web 产物）—— 能投放、能合、能出「本局得分」与「再来一局」；顶栏与底栏都还在，点别的格子能走 | 白屏 / 顶栏下面空着 / 落「这一屏还没搬到 Web」（后者= serve 进程或在吐旧产物，见「前置条件 2」） | 两条如实差异：① **没音效**（浏览器里没有 App 那座 JS 桥 ⇒ 游戏侧静默；这其实是本端所有小游戏的共同事实）；② **成绩回传的时机**不同（App 是游戏结束那一刻由桥回调；这里是**离开这一屏**时把游戏页记的成绩收回合集页那一行）—— 端上看到的最高分两边一致。桌面端仍卡载体（见⑩最后一行）
- [ ] 浏览器能力自检（D12 ③） | 底栏「学辅」→ 页底「后端自检」（或 `?route=probe`） | 往下翻到「浏览器能力（这一份部署的）」那张卡 | 五行逐条带 ✓/✗ **加一句「本端谁在用 / 差什么」**：安全上下文 / 剪贴板写入 / 相机 / 浏览器自带二维码识别器 / WebCrypto | 没这张卡 / 只有✓没有说明 | 这几条**跟地址走**：HTTPS 或 localhost 才有，明文 HTTP + 非 localhost 一律 ✗（后者下**页面上几颗复制按钮会静默失败**）。三样「能不能用」的结论与最小可行方案（含 PWA）见 `docs/desktop-port-plan.md` §16.11

---

## 判据出处（期望值从哪来，不许凭印象）

| 清单分组 | 判据来源 |
|---|---|
| ① 外壳（五格/宽窗/当前页） | `web/.../web/WebShell.kt`（`WEB_SHELL_TABS` 五格与 `:app BottomTab` 逐字一致、`calculateIsWideLayout`）；`ToolboxWebApp.kt`（`ShellPageHost.CURRENT_ONLY`、`initialWebTarget()` 默认落日程）；`docs/desktop-port-plan.md` §15.1 / §15.2 |
| ② 首页（宫格=入口） | `web/.../web/WebHomeHost.kt`（`honoredCards` 六块、`greetingName=""`、`openableRouteIds=WebSupport.openableRouteIds`）；`web/.../web/WebSupport.kt`（唯一真源 36 条）；`core/.../home/AppServices.kt`（宫格与学辅目录条目） |
| ③ 日程 | `web/.../web/WebScheduleHost.kt`（`honored`：`CUSTOM_COURSE_EDIT`/`SCHEDULE_CHANGE_BUBBLE`/serve 下 `SEAT_COUNT`）；`web/.../web/WebScheduleSource.kt`（七个端点，`?merged=1` 取 `:data JwxtScheduleApi.schedule()`）；`web/.../web/WebFallbackScreens.kt` 的 `WebScheduleGapNote`（页顶两种形态文案）；`docs/api-contract.md` §5.8 / §5.9 |
| ④ 学辅 | `web/.../web/WebFallbackScreens.kt` 的 `WebServicesScreen`（可点目录 + 会话入口 + 后端自检）；`WebSupport.openableCatalog` / `unsupportedCatalog` |
| ⑤ 我的 | `web/.../web/WebSettingsHost.kt`（`honoredRows={DARK_MODE,HOME_THEME}`、`versionText="Web 构建（wasm）"`、九个不列的理由）；`ToolboxWebApp.kt` 的 `AppRoute.Settings` 分支 |
| ⑥ 只读四域 | `ToolboxWebApp.kt` 四个分支（`AppRoute.Transcript/Attendance/Coupon/DormPower`）；`WebFallbackScreens.kt` 的 `WebServeOnlyScreen` + `WebGapNote`；`web/.../web/WebDormPowerScreen.kt`；`docs/api-contract.md` §5.5（含末尾消费方表） |
| ⑦ 第二批四屏 | `ToolboxWebApp.kt` 四个分支（`AppRoute.Lms/Jiaocai/JwappScore/Iclassface`）；`web/.../web/WebJiaocaiHost.kt`（`WebJiaocaiTermTextbooks`）；`docs/api-contract.md` §5.6（含消费方表） |
| ⑧ 第三批四屏 | `ToolboxWebApp.kt` 四个分支（`AppRoute.PaymentCode/Feedback/Match/WebVpnConverter`）；匹配交友那一屏是 `:core` 的 `MatchScreen` + 本端 `WebMatchHost`（旧的那张「匹配说明页」`WebMatchGapScreen` 自 D12 ② 起已删）；`docs/api-contract.md` §5.7（含消费方表） |
| ⑨ 形态差别 | `docs/api-contract.md` §5.4（模式开关/登录/令牌/消费方不画）；§5.3.2 第 6 条（支付那一跳的 302、三条如实说明）；`WebServeOnlyScreen` / `WebScheduleGapNote` 两种形态分支；`WebVenueHost`（`venuePayTarget`）|
| ⑩ 明确不可用项 | `WebSupport.kt` 文件头（「什么不在清单里」）；`ToolboxWebApp.kt` 的 `AppRoute.Agent` 降级分支 + `NotPortedScreen`；`docs/desktop-port-plan.md` §16.2 第 1 条（43 条路由的差集，2026-10-15 起剩 5 条：`Agent`/`Browser`/`DownloadManager`/`Jiaocai1`/`Jiaocai1Reader`）；`docs/agent-web-spec.md`（屁岱设计稿未实现） |
| ⑪ 本轮点亮的两格 | 合成西交大：`web/.../WebGameMerge.kt`（`iframe` + `MergeGameScoreBridge`）+ `web/build.gradle.kts` 的 `mergeGameResources`（`:app` 的 assets 原样拷进产物）+ `ToolboxWebApp.kt` 那条分支 + `WebSupport.openableRouteIds` 的 `AppRoute.GameMerge`；`docs/desktop-port-plan.md` §16.11。浏览器能力自检：`web/.../WebBrowserCapabilities.kt` + `ProbeScreen.kt` 那张卡 + `WebBrowserCapabilitiesTest` |

---

## 本清单覆盖不到的东西

- **逐像素/逐状态全等（丙口径）的自动化**：`:web` 没有测试源集（`web/src/` 只有 `wasmJsMain`），本清单是**人工照单点**，不能替代 D14 要建的 `:web` 测试源集（路由清单覆盖测试 + 存档层互认 + 关键屏 wasmJs 测试）。
- **深色/侧栏的视觉细节**：本清单只验「变了/没变」，不验与 Android 的像素级差异。
- **错误态 / 空态 / 加载态的逐条文案**：除各屏页顶缺口说明外，其余文案逐条对 Android 没在本清单展开（那是 D14/丙口径的活）。
- **统一存档层 / 多账号（批②）**：`Accounts` / 存档互认 / serve 侧 DPAPI 等，见 `docs/storage-layout-plan.md`，不在本清单（本清单只管「serve 这一页现在长什么样」）。
- **HTTPS / 鉴权（D11）**已于 2026-10-15 落地（批④：`--https` 开箱可启用，`docs/linux-delivery.md` / `docs/windows-delivery.md` §10）；场馆「去支付」（D11b）也已落地（见⑨那一行）。**D12 的能力边界收口**：① 付款码矩阵 / ② 分享码（批⑤-a）与 ⑤ 合成西交大、③ 相机扫码·剪贴板·WebCrypto 的逐条查清（本批，见 ⑪）都已落地；**④ PWA 仍未做**（缺什么 + 最小可行方案见 `docs/desktop-port-plan.md` §16.11）。

## 下一步

1. 用户照单点，逐行勾 `- [x]`；点不通的按【现象 + 截图 + 哪一格】回报。
2. 涉及「需重启 serve」的点位，先跑一遍「前置条件 2」的探针确认新端点是否就位，再点对应屏。
3. 回报口径里把「campus-api 形态下该屏就是『需要 serve 模式』页」与「serve 形态下真出 bug」分开说——前者是现状、后者要查。
4. 验收完后按 D14 把「人工照单点」沉淀成 `:web` 测试源集，本清单留在 `docs/` 作为人工点位记录。
