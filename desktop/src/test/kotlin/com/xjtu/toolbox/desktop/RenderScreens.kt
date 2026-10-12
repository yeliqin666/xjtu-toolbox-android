package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.coupon.CouponFakeUpstream
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream
import com.xjtu.toolbox.feedback.FeedbackStore
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.lms.LmsFakeUpstream
import com.xjtu.toolbox.jwapp.JwappFakeUpstream
import com.xjtu.toolbox.iclassface.IclassfaceFakeUpstream
import com.xjtu.toolbox.community.CommunityFakeUpstream
import com.xjtu.toolbox.community.GithubDeviceAccessToken
import com.xjtu.toolbox.community.SecureGithubSession
import com.xjtu.toolbox.media.DownloadFakeUpstream
import com.xjtu.toolbox.media.DownloadItem
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.desktop.media.DesktopDownloads
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.yellowpage.YellowPageApi
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.util.todayInSystemZone
import com.xjtu.toolbox.notification.NotificationFakeUpstream
import com.xjtu.toolbox.ywtb.YwtbFakeUpstream
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/**
 * `./gradlew :desktop:renderScreens` —— 把屏**离屏**渲染成 PNG，作为桌面端的可视化证据。
 *
 * ## 它回答哪几条验收
 *
 * | 图 | 内容 | 证明什么 |
 * |---|---|---|
 * | `login.png` | 登录页（空输入框） | `:core` 的共享登录屏在桌面真画得出来；**不预填学号**（红线） |
 * | `library-after-login.png` | 图书馆座位屏，**用刚输入的凭据真登进去**之后的 | Stage A 的验收本体：桌面端自己登录、自己取数（`:data` 的会话内核 + `LibraryApi`） |
 * | `shell-after-login.png` | 整个外壳（底栏 + 图书馆屏；首屏显式给图书馆） | 外壳与底栏在登录后正确切换。外壳的**默认**首屏是首页（见 `home.png` / `main.png`）—— 这一张显式给图书馆，因为要拍的正是「外壳 + 某一屏」这件事本身 |
 * | `fitness.png` | 体测屏（真路由：外壳 → `AppRoute.Fitness` → `FitnessApi`） | 第五条真数据路由：https 站点、CAS 回跳后的 launch 会话、v3 取数 |
 * | `schoolcourse.png` | 全校课表屏（真路由 + 真查询：外壳 → `AppRoute.SchoolCourse` → `AppSchoolCourseSource`） | 第六条真数据路由：登录后的学期 / 开课单位 / 课程卡片都是夹具样本（屏不会自己发查询，这张图靠 semantics 点一下「搜索」，见 [clickByLabel]） |
 * | `scorereport.png` | 成绩报表屏（真路由：外壳 → `AppRoute.ScoreReport` → `scoreReportSource`） | 第七条真数据路由：学号取自登录时的 `AccountContext.activeAccountId`，图上按学期分组的成绩是夹具样本 |
 * | `judge.png` | 学生评教屏（真路由：外壳 → `AppRoute.Judge` → `UndergraduateJudgeSource`） | 第八条真数据路由：与课表/成绩同一个教务站点（同一份会话），上游是 `wspjyyapp`；图上未评那三张卡片的课名/教师/标签都是夹具样本 |
 * | `campuscard.png` | 校园卡屏（真路由：外壳 → `AppRoute.CampusCard` → `AppCampusCardSource`） | 第九条真数据路由：**https** 站点（ncard）、CAS 回跳那一跳上换 JWT，缓存传 `null`；图上余额 / 待入账 / 今日三餐与流水都是夹具样本 |
 * | `payment_code.png` | 付款码屏（真路由：外壳 → `AppRoute.PaymentCode` → `DesktopSiteGate(校园卡)` → `PaymentCodeApi`） | 第二十六条真数据路由：站点就是**校园卡**（付款码借它的 JWT，不另建会话），屏与取数本轮才搬进 `:core`/`:data`；图上那串码（`60041234000000000001`）与两张加餐券都是夹具样本，二维码/条形码是拿那串码**现场编出来的**（判据见 `DesktopAuthPaymentCodeJvmTest` 里那个「解回同一串数字」） |
 * | `emptyroom.png` | 空闲教室屏（真路由：外壳 → `AppRoute.EmptyRoom` → `AppEmptyRoomSource`） | 第十条真数据路由：三档数据源里那一档「实时状态」要智慧教室站点（https，CAS 回跳后把票换成 `TOKEN-AUTH`）—— 会话由**源自己 ensure**（所以不套 `DesktopSiteGate`）；图上楼分组、四间教室、空闲/上课中/「其它使用」与「实时 · HH:MM」都是夹具样本 |
 * | `venue.png` | 体育场馆屏（真路由：外壳 → `AppRoute.Venue` → `DesktopSiteGate` → `AppVenueSource`） | 第十一条真数据路由：登录要先过 `org.xjtu.edu.cn` 的 OAuth2 → CAS → 回跳（场馆站本身是明文 http）；图上九个场馆名（两页拼起来）都是夹具样本。这一端 `canBook = false`（滑块控件搬不到桌面）⇒ 只有读的那一半 |
 * | `inbox.png` | 消息收纳屏（真路由：外壳 → `AppRoute.Inbox` → `AppInboxSource`） | 第十二条真数据路由：一网通办那四路（消息 / 事务中心 / 预约 / 校车），会话由**源自己 ensure**（不套 Gate）；图上待办那两栏的条目与「预约中心 有 3 个…」都是夹具样本 |
 * | `notification.png` | 通知公告屏（真路由：外壳 → `AppRoute.Notification` → `AppNoticeSource`） | 第十三条真数据路由：29 个公开的公告页里夹具扮了 3 个（教务处 / 化工学院 / OA），这是最后一条 pending 路 —— 图上那三条教务处通知（默认来源）的标题/日期/标签都是夹具样本，不是错误页或转圈 |
 * | `transcript.png` | 电子成绩单屏（真路由：外壳 → `AppRoute.Transcript` → `DesktopSiteGate` → `AppTranscriptSource`） | 第十四条真数据路由：`dzpz` **有自己的独立登录页**（`Login.jsp` → CAS OAuth2 → 回跳换 `loginidweaver`），屏与七步流程都在 `:core`，PDF 落盘在桌面上写用户的下载目录；图上那一条成绩单类型、申请日期与「一键申请成绩单」按钮都是夹具样本 |
 * | `coupon.png` | 加餐券屏（真路由：外壳 → `AppRoute.Coupon` → `DesktopSiteGate` → `AppCouponSource`） | 第十五条真数据路由：`egc.xjtu.edu.cn` **有自己的独立登录页**（CAS OAuth2 `client_id=1596` → `org.xjtu.edu.cn` 开放平台 → 回落到 receiveCas.html 换 `auth_token`），屏与分页/领取的编排都在 `:core`；图上「可使用」那两批券卡与摘要行都是夹具样本 |
 * | `dormpower.png` | 宿舍电费屏（真路由：外壳 → `AppRoute.DormPower` → `DesktopSiteGate` → `DormPowerApi`） | 第十七条真数据路由：`ssn.xjtu.edu.cn/cems` **有自己的独立登录链**（`org.xjtu.edu.cn` 开放平台 OAuth2 `appId=1759` → CAS → 带 `code` 回缴费页换会话），屏在 `:core`；桌面端没有内置浏览器，`browser` 槽位用系统浏览器打开、本窗给一句去向（图上那句「缴费页已交给系统浏览器打开…」就是夹具样本） |
 * | `attendance.png` | 考勤屏（真路由：外壳 → `AppRoute.Attendance` → `DesktopSiteGate` → `AppAttendanceSource`） | 第十六条真数据路由：`bk-kq.xjtu.edu.cn` **有自己的独立登录入口**（`AttendanceLogin` 直连业务站的 CAS `student-pc` 入口 → 回调带 loginRequestId+ticket 换业务令牌），屏/VM 与取数端口都在 `:core`，桌面端没有 SAF 文件选择器 ⇒ `pickEvidenceFiles` 用默认；图上「流水」那一栏的夹具课名（光电子学（假））与姓名行都是夹具样本 |
 * | `lms.png` | 思源学堂屏（真路由：外壳 → `AppRoute.Lms` → `DesktopSiteGate` → `AppLmsSource`） | 第十八条真数据路由：`lms.xjtu.edu.cn` **直接 CAS 回跳**（`LmsLogin.loginUrl` = lms 根路径，与教务同型、没有独立登录页），屏在 `:core`，落盘是 `DesktopLmsSaver`；图上课程列表那门夹具课（数据结构（示例课））与老师名都是夹具样本 |
 * | `jwapp.png` | 成绩屏（真路由：外壳 → `AppRoute.JwappScore` → `DesktopSiteGate` → `AppJwappScoreSource`） | 第十九条真数据路由：`jwapp.xjtu.edu.cn` **有自己的独立登录链**（开放平台 `appId=1370` → CAS → 回跳带 `token`，令牌从回调 URL 里抠出来当 `Authorization`）；这一屏的上游是**两个站点**（jwapp 那份粗分 + 教务 `xscjcx` 的精确总评，各自一份会话）；图上那门课（高等数学（上））与精确成绩都是夹具样本 |
 * | `iclassface.png` | 快速考勤流水屏（真路由：外壳 → `AppRoute.Iclassface` → `DesktopSiteGate` → `AppIclassfaceSource`） | 第二十条真数据路由：`iclassface.xjtu.edu.cn` 走 CAS OAuth2 `client_id=1543` → 开放平台 `authorizesw` 中转 → 回落 `/oauth/callback/xjtu_new`（凭据就是 `PHPSESSID`）；图上那行签到记录（姓名 / 时间 / 地点 / 类型）是夹具样本 |
 * | `community.png` | 社区屏（真路由：外壳 → `AppRoute.Community`；登录态与取数都是 `:data` 的实现） | 第二十一条真数据路由：上游是 GitHub（`github.com/login` 的设备码 + `api.github.com` 的 GraphQL），不套 `DesktopSiteGate`（没有学校会话可建）；这张图先把一份夹具令牌写进 `SecureGithubSession`（同一个存储名与键），于是图上那条帖子（示例讨论…）与分类都是从假 GitHub 拉回来的 |
 * | `feedback.png` | 反馈屏（真路由：外壳 → `AppRoute.Feedback` → `AppFeedbackSource`） | 第二十二条真数据路由，也是唯一一条**没有校园站点会话**的：上游是飞书开放平台（`open.feishu.cn`），凭据是宿主注入的 `app_id` + `app_secret` 换一张租户令牌；屏与本地账本在 `:core`（键值缝，与 Android 同一份 `feedback` 存储），取数在 `:data`。图上那张工单与那句开发者回复、两条公开问答都是从假飞书拉回来的（证据运行先往本地账本补一张工单） |
 * | `jiaocai1.png` | 教材全文库屏（真路由：外壳 → `AppRoute.Jiaocai1` → `DesktopSiteGate(教材中心)` → `AppJiaocai1Source`） | 第二十三条真数据路由：站点是**教材中心**（`jiaocai.lib`）—— 全文库借它的会话（`/entry/login` → CAS → 超星 SSO），业务侧那张 `var logoutUrl=…` 的跳转页由 `PortalRedirect` 自己跟；屏（书架 + 全文库）在 `:core`，取数与页字节在 `:data`，书架落盘在 `:desktop`。图上那本书是证据运行点两下分类树检索出来的夹具书目 |
 * | `jiaocai1-reader.png` | 教材阅读器（同一条路由的参数形态：`AppRoute.Jiaocai1Reader(ssno, title)`） | 同一条真数据路由的**页加载**那一半：页图是夹具现场画的 JPEG（700×1050，程序生成、不是截图），解码走 `:core` 的图片缝、磁盘缓存在 `:data`；底栏那个「1 / 34」就是夹具那本书的线性页表 |
 * | `jiaocai.png` | 教材查询屏（真路由：外壳 → `AppRoute.Jiaocai` → `DesktopSiteGate(教材中心)` → `AppJiaocaiSource`） | 第二十四条真数据路由：同一个站点（教材中心）的**另一条路由** —— `jiaocai` 域的合并页 `TextbookScreen` 现在也在 `:core`，桌面这一支默认落在「查教材」栅（与 `:app` 的 `initialTab = 0` 同一条口径）。这个栅进屏时关键词是空的，证据运行点了一下搜索建议（`高等数学`）才发起检索；图上那本教材与「本地全文」那个标志都是从假教材中心拉回来的。**「本学期我的教材」那一段在桌面上不显示**（桌面还没有教务课表缓存 ⇒ 端口回空表），这是如实的降级 |
 * | `settings.png` | 设置屏（真路由：外壳 → `AppRoute.Settings` → `:core` 的 `SettingsScreen`） | 第二十七条真数据路由：屏与偏好存储（`SettingsStore`，键值缝）本轮才搬进 `:core`，这一端把偏好落到 0600 的 `app_settings.properties`（与 `:app` 同一批键）。图上是**深色**的 —— 外壳按「深色模式」偏好套主题，所以那一项不只是存下来、这一端真照着办了；拍的过程中还点了一下「跟随系统取色」并断言它落盘。桌面没有的那三组（通知与提醒 / 数据与隐私 / 更新）与那几行（默认启动 Tab / 课表来源 / 触感 / 连接模式）**一个字都不画** —— 见 `DesktopSettingsHost` 的表与 TODO |
 * | `webvpn_converter.png` | WebVPN 转换器屏（真路由：外壳 → `AppRoute.WebVpnConverter` → `:core` 的 `WebVpnConverterScreen`） | 第二十八条真数据路由：屏本轮才搬进 `:core`，转换本体是 `:data` 的 `WebVpnUtil`（`:core` 的 `WebVpnCodec` 缝，与 Android **同一份实现**）。图上的输入与结果都是拍的时候现场做的：往输入框里塞 `https://jwxt.xjtu.edu.cn/`（夹具网址，公开站点名、无凭据）再点「转换」，结果里那串密文域名（`…fae05988692862446b468ca88d1b203b`）就是 AES-128-CFB 真算出来的 —— 与 `DesktopWebVpnCodecJvmTest` 里那一条同值 |
 * | `accounts.png` | 账号管理屏（真路由：外壳 → `AppRoute.Accounts` → `:core` 的 `AccountManagerScreen`） | 第二十九条真数据路由：多账号。屏本轮才搬进 `:core`，动作本体是 `:desktop` 的 `DesktopAccounts`（`:core` 的 `AccountsController` 缝）。拍之前**真切了一次**：上面那次真登录把夹具账号写进了账号表（`AccountStore`），再加一条夹具账号（值都是编的），然后 `switchTo` 第二条 ⇒ 图上的「当前账号」卡与列表高亮都是第二条（`2021000002`），列表里还有第一条（`2021000001`）。头像那一格不传（画首字母圆）、昵称一直为空 —— 两处如实降级见 `DesktopAccounts` 的 KDoc |
 * | `game_merge.png` | 合成西交大的**如实说明页**（`AppRoute.GameMerge`） | 这条路由卡的是**载体**：游戏本体是 `:app` 的 `assets/games/merge/`（HTML + 776 行 JS + Matter.js 物理引擎），跑在 Android 的 WebView 里；桌面端没有 WebView、依赖里也没有 JVM 上的 JS 引擎 ⇒ 这一端没有能执行它的地方。这张图就是那张说明页本身（**不进** `DESKTOP_SUPPORTED_ROUTES`），不是「没搬过来」那个通用页 —— 卡的不是取数 |
 * | `schedule_match.png` | 匹配交友的**如实说明页**（`AppRoute.Match`） | 这条路由卡的是**本机数据**：它全程离线，交集全从本机缓存算。课表缓存那一层随「我的课表」搬过来了（见 `schedule.png`），但学工系统档案（`HelloProfileStore`）、校园卡消费（`DiningHabit`）与算法本体（`MatchProfile`，900 行）还在 `:app`；分享码本体还要 `java.util.zip` 的 deflate 与 `java.util.Base64`（`:core` 的 commonMain 里不存在）、扫码要 Android 相机 ⇒ 三件都得先切成平台缝。图上是那张说明页（**不进** `DESKTOP_SUPPORTED_ROUTES`）|
 * | `schedule.png` | 我的课表屏（真路由：外壳 → `AppRoute.Schedule` → `DesktopSiteGate(教务)` → `:core` 的 `ScheduleScreen` + 本端 `DesktopScheduleHost`） | 第三十条真数据路由，也是本批的主角：屏与 ViewModel 按五步法搬进 `:core`（模型 / `CustomCourseStore` 端口 / 宿主槽位 / `CourseLinks` 按能力切开），这一端只提供宿主面（键值落盘 / 教务会话 / 节假日 / 思源入口）。图上的课名与教室都是夹具样本，而且**是格子画出来的**（`CourseCell` 那两行文本节点），不是只有一段说明文字 |
 * | `home.png` | 首页（真路由：外壳 → `AppRoute.Main` → `:core` 的 `HomeScreen` + 本端 `DesktopHomeScreen`） | 第三十一条真数据路由，也是 Stage C 「宿主机能族」的第一条：首页就是底栏的第一格（不另造一套壳）。这张图上的**第 4 周**、**下周那节课（夹具课名 + 教室）**、**考试倒计时（还有 5 天 · 主楼B-201）**与顶部那条**公告条**都是从**本端键值缓存**算出来的（`HomeHero` / `HomeScheduleStats` / `BulletinFeed` 都是 `:core` 的）；课表/考试/教材是拍之前写进去的夹具（值都是编的），公告那一枪注入的是夹具公告（`.invalid` 域名）。首页一个网络请求都不发 |
 * | `main.png` | 主壳（同一个 `AppRoute.Main`，宽窗 900×900） | 首页接进**现有导航壳**之后的形态：底栏六格（首页 / 校历 / 图书馆 / 黄页 / 游戏 / 全部）都在，第一格「首页」是当前页；同一份夹具缓存与同一条夹具公告。外壳的**默认首屏**这一轮也改成了首页（与 `:app` 落在「Tab 1」同一条口径）—— 原来那个默认（图书馆）的理由是「它是这一端唯一有真数据的屏」，首页点亮之后这条不再成立。**桌面小组件这一端没有**（设计文档 §6 Stage C 的口径是「用系统通知替代」，那条路还没做）—— 它不在这一端任何一个宿主面里，不是「画不出来」 |
 * | `routes.png` | 「全部页面」索引页 | 如实列出「真能用 / 还没有数据源」，并给出退出登录入口 |
 * | `library-demo.png` | 同一屏 + 固定假数据 | 布局与组件本身可复现（不依赖网络/会话，改屏时用它对比） |
 *
 * ## 「真登进去」是怎么在没有真账号的机器上做到的
 *
 * 用 [LibraryFakeUpstream]（`:testkit`）扮演学校那一侧：座位系统 → 302 到统一认证 →
 * **表单 POST 凭据（密码 RSA 加密）** → 种 TGC → 签 ticket 回跳 → 座位系统会话。
 * 本地 HTTP 服务器当 **代理**（`ProxySelector`），URL 一个字符都不改 —— 会话内核里有一批
 * 按 host 判断的判据（`XJTULogin.casPath` 只认 `login.xjtu.edu.cn`、`LibrarySession.validateLogin`
 * 认 `rg.lib.xjtu.edu.cn`），重写 URL 会让它们全部落空，那测的就不是真内核
 * （这条与 `:data:jvmTest` 的 `LibraryLoginSessionJvmTest` 是同一条口径）。
 *
 * ⚠️ 两个坑（都是实测踩到的，注释在代码里）：
 * 1. `HttpClients.base` 是进程级 `by lazy`，会把当时的默认 `ProxySelector` **抄进自己的配置** ⇒
 *    selector 必须**常驻**、读一个可变端口，且在**建任何会话之前**装上；
 * 2. `XJTULogin` 取 RSA 公钥那一枪硬编码 `https://login.xjtu.edu.cn/cas/jwt/publicKey`，
 *    本地假上游给不了（要 CONNECT+TLS）⇒ 照真实路径预置一份缓存公钥（真机上那份由首次登录
 *    自动取回并存进凭据文件，见 [JvmCredentialStore.rsaPublicKey]）。
 *
 * ## ⚠️ 为什么这里**不画** MFA 弹窗
 *
 * `:core` 的 `MfaCodeDialog` 用的是 miuix 的 `WindowDialog`，它在桌面上的实现是
 * `androidx.compose.ui.window.Dialog` ⇒ **一个独立的 AWT 窗口**（`DialogWindow`）。
 * 离屏场景（[ImageComposeScene]）画不进另一扇窗，而没有显示服务器时连创建它都不行。
 * 所以二验那一屏的证据只能由 `:desktop:run` 在真窗口里给（也是它与 `:app` 那份逐条对齐的原因：
 * 同一个组件、同一套文案）。
 *
 * ## 为什么它在 test 源集里
 *
 * 它要 [LibraryFakeUpstream]（`:testkit`）——「任何密码都收」的假统一认证绝不能出现在交付物里。
 * 见 `desktop/build.gradle.kts` 的文件头。
 *
 * `exitProcess(0)` 是必要的：skiko 与 AWT 的事件线程不是守护线程，不主动退出这个 JavaExec 会挂着。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    // 先清掉上一次的 PNG：Stage 0 那几张（`library-live.png` / `shell-live.png`）是 campus-api 脚手架
    // 时代的证据，已经不对应今天的实现 —— 留着只会被当成「现在的桌面端长这样」。
    outDir.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.forEach { it.delete() }

    // ── 两个进程级前置，顺序都不能换 ──────────────────────────────────────────
    //
    // ① 落盘存储指到临时目录：证据运行绝不碰用户真实数据（`~/.local/share/xjtu-toolbox`），
    //    也绝不会把「假登录」产生的 cookie 留在一个真账号旁边。必须在任何
    //    `secureKeyValueStore` / `PersistentCookieJar` 被碰之前设。
    val shotsRoot = Files.createTempDirectory("xjtu-desktop-shots").toFile()
    dataRootOverride = shotsRoot
    // ② 常驻代理 selector（此刻端口 0 = 不走代理）+ 假上游那枚自签 https 证书的信任库。
    //    两者都必须在 `HttpClients.base` 第一次被初始化**之前**装：它抄下的是「没有代理」，
    //    而客户端建出来那一刻就把平台 trust manager 也抄走了（体测是 https）。
    //    详见文件头第 1 条坑与 `:testkit` 的 `FakeUpstreamFront`。
    FakeCampusProxy.installFakeUpstreams()

    fun shot(
        name: String,
        width: Int = 520,
        height: Int = 900,
        frames: Int = 12,
        /** 每帧渲染**之前**在 EDT 上跑一次 —— 需要「点一下」的屏用它（见 [clickByLabel]）。 */
        onFrame: ((frame: Int, scene: ImageComposeScene) -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        // 每个场景一个独立的 ViewModelStoreOwner：:core 的屏用 `viewModel { }` 建 VM，
        // 桌面不像 Android 那样自带一个（与 Main.kt 里给真窗口装的是同一个东西）。
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val scene = onEdt {
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    XJTUToolBoxTheme { content() }
                }
            }
        }
        try {
            var image = onEdt { scene.render(0L) }
            repeat(frames) { i ->
                // 帧间让出调用线程：EDT 就在这段空档里把 VM 的取数协程跑完
                Thread.sleep(400)
                image = onEdt {
                    // 需要「点一下」的屏在这里动手（见 [clickByLabel]）：必须在渲染前、且在 EDT 上
                    onFrame?.invoke(i, scene)
                    scene.render((i + 1) * 400_000_000L)
                }
            }
            val bytes = requireNotNull(image.encodeToData()) { "PNG 编码失败：$name" }.bytes
            val file = File(outDir, name)
            file.writeBytes(bytes)
            println("wrote ${file.absolutePath} (${bytes.size} bytes)")
        } finally {
            onEdt { scene.close() }
        }
    }

    /** 深度优先找第一个满足 [predicate] 的语义节点（合并树里按钮自己就带着子文本）。 */
    fun findNode(root: SemanticsNode, predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        if (predicate(root)) return root
        return root.children.firstNotNullOfOrNull { findNode(it, predicate) }
    }

    /**
     * 在离屏场景里「点一下」写着 [label] 的那个控件 —— 靠 **semantics** 找它、直接调它的 `OnClick`，
     * **不靠坐标**：坐标会随布局漂，而 semantics 是屏自己声明的（`clickable`/`Button` 都会带上）。
     *
     * 为什么需要它：全校课表那一屏**不会**自己发查询 —— 那一枪要用户点「搜索」，而离屏场景没有鼠标。
     * 没有语义树（或按钮那时候还是 `enabled = false`）时返回 false，调用方下一帧再试。
     */
    fun clickByLabel(scene: ImageComposeScene, label: String): Boolean {
        val root = scene.semanticsOwners.firstOrNull()?.rootSemanticsNode ?: return false
        val target = findNode(root) { node ->
            val click = node.config.getOrNull(SemanticsActions.OnClick)
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            click != null && texts.any { it.text == label }
        } ?: return false
        return target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false
    }

    /**
     * 往**唯一那个带 `SetText` 能力的输入框**里塞一段文本（靠 semantics，不靠坐标）。
     *
     * 为什么不是「按 label 找」：`TextField` 的 label 是旁边另一个 `Text` 节点，不在输入框自己的
     * 语义里（只有占位符/当前文本在）。调用方都在**只有一个输入框**的屏上用（WebVPN 转换器那张图），
     * 所以按能力找是确定的；多输入框的屏要再写一份按序号的。
     */
    fun setTextOnFirstField(scene: ImageComposeScene, text: String): Boolean {
        val root = scene.semanticsOwners.firstOrNull()?.rootSemanticsNode ?: return false
        val target = findNode(root) { it.config.getOrNull(SemanticsActions.SetText) != null } ?: return false
        return target.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text)) ?: false
    }

    /**
     * 语义树里有没有写着（含）[text] 的节点 —— 用作「这一枪真打到屏上了」的判据。
     * 消息收纳那一屏用它：它的第一枪是**外壳**打的（屏自己只在「下拉刷新」里调 `refresh`），
     * 没拉到数据时屏上就是一个空列表 —— 那种图不能当「真数据路由」的证据交出去。
     */
    fun hasText(scene: ImageComposeScene, text: String): Boolean {
        val root = scene.semanticsOwners.firstOrNull()?.rootSemanticsNode ?: return false
        return findNode(root) { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(text) }
        } != null
    }


    // ── ① 登录页（空输入框 · 红线：不预填学号）────────────────────────────────
    // 走真外壳：没登录 ⇒ 它自己落在 LoginScreen 上，这张图同时证明「壳的切换」是对的。
    // 单给一个 DesktopAuth（临时数据目录里没有任何凭据 ⇒ 不会被 restore 静默带走）。
    shot("login.png", frames = 4) { ToolboxDesktopApp(DesktopAuth()) }

    // ── ② 图书馆屏 + 固定假数据：布局/组件本身可复现，与网络无关 ────────────────
    shot("library-demo.png", frames = 4) {
        LibraryScreen(
            source = DemoLibrarySource(),
            onBack = {},
            showFirstUseHint = false,
            onFirstUseHintRead = {},
        )
    }


    // ── ③ 黄页（免登录；Ktor 接口 ⇒ MockEngine 喂假响应，不需要服务器/代理）────────
    // 这张图要的是「有数据时屏长什么样」：类别标签、部门行、可拨号码。
    // 它**不走** FakeCampusProxy —— 黄页根本不需要会话（见 FakeKtorClients.kt 的 KDoc）。
    shot("yellowpage.png", frames = 8) {
        YellowPageScreen(
            api = YellowPageApi(mockYellowPageClient()),
            onBack = {},
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
    }

    // ── ④ 教师检索（免登录；OkHttpClient 可注入 ⇒ 拦截器喂假上游，不需要服务器/代理）────
    shot("faculty.png", frames = 12) {
        FacultyScreen(
            source = FacultyApiSource(FacultyApi(mockFacultyClient())),
            onBack = {},
            onOpenUrl = {},
        )
    }

    // ── ⑤⑥⑦⑧⑨ 真登录之后（同一份假上游：图书馆与体测要登录、校历不要）──────────────
    withFakeCampus { auth ->
        // 「用户在登录页敲了字」这一步：用的是假上游那组**编出来的**账号密码
        //（`LibraryFakeUpstream.USERNAME` = 2021000001，不是任何人的学号）。
        // 真窗口里这两个值来自键盘，这里来自夹具 —— 除此之外与 `:desktop:run` 走的是同一条路。
        auth.username = LibraryFakeUpstream.USERNAME
        auth.password = LibraryFakeUpstream.PASSWORD
        val ok = runBlocking { auth.login() }
        println(
            "真登录结果=$ok  状态=${auth.loginState}  " +
                "CAS 提交凭据次数=${fake.library.credentialPosts.get()}  签发的 ticket 数=${fake.library.tickets.get()}"
        )
        check(ok) { "假上游上的真登录应当成功：${auth.loginState}" }

        shot("library-after-login.png", frames = 20) {
            LibraryScreen(
                source = auth.librarySource,
                onBack = {},
                reAuthenticate = {
                    auth.sessionManager.ensureSite(DesktopAuth.LIBRARY_SITE_KEY, userInitiated = true)
                },
                showFirstUseHint = false,
                onFirstUseHintRead = {},
            )
        }
        // 外壳 + 图书馆屏：这一张要拍的是**外壳本身**（登录之后底栏与当前页正确切换），
        // 所以首屏显式给图书馆 —— 外壳的默认首屏现在是**首页**（`AppRoute.Main`，见 `home.png`）。
        shot("shell-after-login.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Library)) }

        // 体测（第五条真数据路由）：这张图走**外壳 + 真路由**（`AppRoute.Fitness` → `FitnessApi`），
        // 所以它同时证明「路由真接上了」与「数据是真的」（学年胶囊 / 姓名 / 总分 / 七个分项）。
        // 站点是 **https**：假上游为此自带 CONNECT 前置与一枚自签证书（见 `:testkit` 的
        // `FakeUpstreamFront`）—— 这是「URL 一个字符都不改」那条口径在 https 站点的代价。
        shot("fitness.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Fitness)) }

        // 校历：**免登录**的公开门户接口 —— 与上面那张不同，它不需要任何会话。
        // 这是「屏在 :core + 取数在 :data」的直接报偿：搬一个 35 行的 IO 适配器就多一屏。
        // 基址指向假上游（`SchoolCalendarApi` 的基址本来就是构造参数）：真机那条是 https，
        // 而这里只需要「取数链路是真的」—— 直接指过来最省事，也不用为它再签一枚证书。
        shot("calendar.png", frames = 12) {
            SchoolCalendarScreen(
                source = SchoolCalendarApi(SchoolCalendarFakeUpstream.URL),
                onBack = {},
            )
        }
        // 全校课表（第六条真数据路由）：也是**真路由**（外壳 → `DesktopSiteGate` → `AppSchoolCourseSource`）。
        // ⚠️ 这一屏**不会**自己发查询 —— 那一枪要用户点「搜索」，所以这里用 semantics 点一下
        // （见 [clickByLabel]）：点不到就多试几帧，因为「搜索」按钮要等当前学期加载完才 `enabled`。
        var searched = false
        shot(
            "schoolcourse.png",
            frames = 22,
            onFrame = { frame, scene -> if (!searched && frame >= 5) searched = clickByLabel(scene, "搜索") },
        ) {
            ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.SchoolCourse))
        }
        // 点不到就是「这张图里没有课程」，响亮地失败 —— 别把空屏当证据交出去
        check(searched) { "schoolcourse.png：没点到「搜索」按钮（semantics 里没找到）" }

        // 成绩（第七条）：学号取自登录时写进 `AccountContext.activeAccountId` 的那个（屏上就是这么取的），
        // 所以这张图里是真能取到数据的；真取不到学号时屏会画「要一个学号」那张说明页（不是崩）。
        shot("scorereport.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.ScoreReport)) }

        // 评教（第八条）：假账号默认是本科生（`credentials.accountType` 的默认值）⇒ 走的是本科那一条
        // （与课表/成绩同一个教务站点、同一份会话）。图上应是**未评那三张卡片**（过程评教一行 + 期末评教
        // 两行，标签由 `PGLXDM` 换算），而不是错误页/转圈 —— 这是拿屏当证据时最容易糊过去的一格。
        shot("judge.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Judge)) }

        // 校园卡（第九条）：走**真路由**（外壳 → `AppRoute.CampusCard` → `DesktopSiteGate` →
        // `AppCampusCardSource`，缓存传 `null`）。图上应是夹具那张家底：余额/待入账/今日三餐 +
        // 七条流水的商户与金额，而不是错误页或转圈。
        shot("campuscard.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.CampusCard)) }

        // 付款码（第二十六条）：走**真路由**（外壳 → `AppRoute.PaymentCode` → `DesktopSiteGate`
        // → `:data` 的 `PaymentCodeApi`）。它借的就是上面那条校园卡会话（`loginType = CAMPUS_CARD`），
        // 所以这一屏进门不需要再走一趟 CAS。图上应当是夹具那串**编出来的**付款码号 + 两张加餐券
        // —— 不是错误页、也不是转圈。`hasText` 钉的就是那串号（屏上它是纯文本那一行）。
        var paymentShown = false
        shot(
            "payment_code.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!paymentShown && frame >= 4) paymentShown = hasText(scene, CampusCardFakeUpstream.BAR_CODE)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.PaymentCode)) }
        check(paymentShown) {
            "payment_code.png：屏上没有夹具那串付款码（${CampusCardFakeUpstream.BAR_CODE}）—— 多半没取到数"
        }
        // 顺带把「真出码」这一半也在这里响亮地钉一下：屏幕那一发刷新会把取回的码编成模块阵列，
        // 这里拿同一份夹具码再编一次，编不出来就当场失败（「解回同一串数字」那条判据在
        // `DesktopAuthPaymentCodeJvmTest` 里）。
        val codeMatrix = requireNotNull(qrCodeModules(CampusCardFakeUpstream.BAR_CODE)) {
            "付款码在桌面这一端应当真编得出二维码"
        }
        println("付款码模块阵列：${codeMatrix.width}x${codeMatrix.height}（解回来见 DesktopAuthPaymentCodeJvmTest）")

        // 空闲教室（第十条）：走**真路由**（外壳 → `AppRoute.EmptyRoom` → `AppEmptyRoomSource`）——
        // 这一屏的会话由源自己 ensure，所以这张图里智慧教室 `js` 那一发 CAS + 换令牌是**屏**自己登的
        // （其余需要会话的屏走 `DesktopSiteGate`，由外壳先建）。屏一进来按默认档筛「空闲」
        // （`LIVE_FILTERS` 的第一档）⇒ 图上只有空闲那两间；这里用 semantics 点一下「全部 N」，
        // 把四间都摆出来（含「上课中」那一间的课程/教师与「其它使用」那一间的人数）——
        // 点不到就响亮地失败（见 [clickByLabel]），别把只画了一半的图当证据交出去。
        var pickedAll = false
        shot(
            "emptyroom.png",
            frames = 22,
            // 计数来自夹具那四间（`EmptyRoomFakeUpstream.liveJson`）；夹具改了这里就会响亮地失败
            onFrame = { frame, scene -> if (!pickedAll && frame >= 6) pickedAll = clickByLabel(scene, "全部 4") },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.EmptyRoom)) }
        check(pickedAll) { "emptyroom.png：没点到「全部 4」这个筛选（semantics 里没找到）" }
        // 体育场馆（第十一条）：走**真路由**（外壳 → `AppRoute.Venue` → `DesktopSiteGate` →
        // `AppVenueSource`）。这一端 `canBook = false`（没有滑块控件 ⇒ 下单那一步走不完，见
        // `ToolboxDesktopApp` 那一段的注释），所以图上只有**读**的那一半：场馆列表（夹具那 9 个，
        // `id=0` 那一行被跳过）—— 不是错误页，也不是转圈。
        shot("venue.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Venue)) }

        // 消息收纳（第十二条）：也是**真路由**（外壳 → `AppRoute.Inbox` → `AppInboxSource`）。
        // 这一屏**没有 ViewModel**（数据在 `:core` 的 `InboxStore` 里），屏自己只在「下拉刷新」里
        // 调 `refresh` ⇒ 进屏那一发由外壳打（见 `ToolboxDesktopApp` 那一段的注释）。
        // 图上应当是夹具那两条事务中心待办 + 「预约中心 有 3 个待使用的预约」那一行，
        // 而不是空列表、错误页或转圈 —— 空列表正是「不该把没取到数的屏当证据」那种假绿。
        // 图上必须有夹具那两条待办里的一条 —— 拿不到就是「没拉到数据」，响亮地失败（见 [hasText]）。
        var inboxShown = false
        shot(
            "inbox.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!inboxShown && frame >= 4) inboxShown = hasText(scene, YwtbFakeUpstream.TODO_TITLE)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Inbox)) }
        check(inboxShown) { "inbox.png：屏上没有夹具那条待办（${YwtbFakeUpstream.TODO_TITLE}）—— 多半没拉到数据" }

        // 通知公告（第十三条，也是最后一条 pending 路）：走**真路由**（外壳 → `AppRoute.Notification`
        // → `AppNoticeSource`，由 `:data` 的 `NotificationApi` 爬 29 个公开的公告页）。这一屏**不需要
        // 登录**，屏一进门就取数；默认来源就是教务处（夹具覆盖面里的第一个）⇒ 不必点任何筛选。
        // 图上应当是夹具那几条教务处通知（标题 + 日期 + 「通知」标签），而不是空列表、错误页或转圈；
        // 拿不到夹具那条标题就是「没取到数」，响亮地失败（见 [hasText]）。
        var noticeShown = false
        shot(
            "notification.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!noticeShown && frame >= 4) noticeShown = hasText(scene, NotificationFakeUpstream.JWC_TITLE_1)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Notification)) }
        check(noticeShown) {
            "notification.png：屏上没有夹具那条通知（${NotificationFakeUpstream.JWC_TITLE_1}）—— 多半没取到数"
        }

        // 加餐券（第十五条真数据路由）：走**真路由**（外壳 → `AppRoute.Coupon` → `DesktopSiteGate`
        // → `AppCouponSource`）。登录链里有 `org.xjtu.edu.cn` 开放平台那一跳（与场馆共用一台 host，
        // 按路径分派）。图上应当是「可使用」那两批券卡中的一张（标题 + 面额 + 日期，那行
        // 「剩余 / 面额 ¥5.00」）+ 顶栏的分段页签 —— 不是转圈，也不是错误页。
        var couponShown = false
        shot(
            "coupon.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!couponShown && frame >= 4) couponShown = hasText(scene, CouponFakeUpstream.USABLE_NAME)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Coupon)) }
        check(couponShown) {
            "coupon.png：屏上没有夹具那张券卡（${CouponFakeUpstream.USABLE_NAME}）—— 多半没取到数"
        }

        // 电子成绩单（第十四条真数据路由）：走**真路由**（外壳 → `AppRoute.Transcript` →
        // `DesktopSiteGate` → `AppTranscriptSource`）。站点 `dzpz.xjtu.edu.cn` **有自己的独立登录页**
        // （`Login.jsp` → CAS OAuth2 `client_id=new9940` → 回跳换 `loginidweaver`），登录页那一步已经把
        // 它预热好（`SESSION_SITE_KEYS` 里有它）。图上应当是**表单那一档**：成绩单类型（夹具那条）+
        // 申请信息（日期 / 所属单位 / 份数）+「一键申请成绩单」按钮 —— 不是转圈，也不是错误页。
        var transcriptFormShown = false
        shot(
            "transcript.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!transcriptFormShown && frame >= 4) transcriptFormShown = hasText(scene, DzpzFakeUpstream.TYPE_NAME)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Transcript)) }
        check(transcriptFormShown) {
            "transcript.png：屏上没有夹具那个成绩单类型（${DzpzFakeUpstream.TYPE_NAME}）—— 多半没取到表单"
        }


        // 宿舍电费（第十七条真数据路由）：走**真路由**（外壳 → `AppRoute.DormPower` →
        // `DesktopSiteGate` → `DormPowerApi`）。登录链里有 `org.xjtu.edu.cn` 开放平台那一跳
        //（与场馆共用一台 host，按 appId 分派）。图上应当是「缴费页已交给系统浏览器打开」那句
        // 去向说明 —— 不是转圈卡住，也不是错误页（`freshPayUrl` 真拿到带 code 的回调地址）。
        var payShown = false
        shot(
            "dormpower.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!payShown && frame >= 4) payShown = hasText(scene, "缴费页已交给系统浏览器打开")
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.DormPower)) }
        check(payShown) {
            "dormpower.png：屏上没有「缴费页已交给系统浏览器打开」—— 多半没取到缴费页入口"
        }

        // 考勤（第十六条真数据路由）：走**真路由**（外壳 → `AppRoute.Attendance` →
        // `DesktopSiteGate` → `AppAttendanceSource`）。登录链直连本科生业务站（bk-kq 的
        // student-pc 入口，不经过门户）。图上应当是「流水」那一栏的夹具课名（`光电子学（假）`）
        // —— 不是转圈，也不是错误页。
        var attendanceShown = false
        shot(
            "attendance.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!attendanceShown && frame >= 5) attendanceShown = hasText(scene, AttendanceFakeUpstream.COURSE_NAME)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Attendance)) }
        check(attendanceShown) {
            "attendance.png：屏上没有夹具那条课名（${AttendanceFakeUpstream.COURSE_NAME}）—— 多半没取到数"
        }

        // 思源学堂（第十八条真数据路由）：走**真路由**（外壳 → `AppRoute.Lms` → `DesktopSiteGate`
        // → `AppLmsSource`）。登录链直连 lms 根路径 → 统一认证 CAS 回跳（与教务同型，没有独立
        // 登录页；那台 CAS 仍由 [LibraryFakeUpstream] 扮演）。图上应当是课程列表：夹具那门课
        //（数据结构（示例课））的课程卡 —— 不是错误页，也不是转圈。
        // ⚠️ 先把「功能说明弹过了没」置成已读：lms 的说明弹窗用的是 WindowDialog（在离屏场景里
        // 是独立 AWT 窗口，建不出来 —— 见文件头那条坑），置已读就不会弹。
        keyValueStore("feature_hints").putBoolean("lms_hint_shown", true)
        var lmsShown = false
        shot(
            "lms.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!lmsShown && frame >= 5) lmsShown = hasText(scene, LmsFakeUpstream.COURSE_NAME)
            },
        // ⚠️ `Lms` 是带 `courseId` 的 data class（`@Serializable` ⇒ 它有一个合成的 Companion）
        // ⇒ 这里必须写默认实例 `AppRoute.Lms()`；只写 `AppRoute.Lms` 拿到的是那个 Companion，
        // 类型对不上（而且不会报在报错看得见的地方）。
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Lms())) }
        check(lmsShown) {
            "lms.png：屏上没有夹具那门课（${LmsFakeUpstream.COURSE_NAME}）—— 多半没取到数"
        }

        // 成绩（移动教务，第十九条真数据路由）：走**真路由**（外壳 → `AppRoute.JwappScore` →
        // `DesktopSiteGate(JWAPP_SITE_KEY)` → `AppJwappScoreSource`）。登录链走开放平台
        // `appId=1370` → 统一认证 → 带 `token` 回 `jwapp.xjtu.edu.cn/app/index`（那台 CAS 仍由
        // [LibraryFakeUpstream] 扮演）；这一屏的第二条上游是**教务**（`xscjcx` 的精确总评），
        // 它的会话在登录页那一步已经预热过。图上是那门夹具课（高等数学（上））：屏上显示的总评是
        // jwapp 的粗分（86），**绩点 3.9 是教务精确成绩那一份**（`withPreciseScores` 换的是
        // scoreValue/gpa，不动 `score` 那个字符串 —— 搬迁前的行为就是这样）。下面「报表·…」那一栏
        // 是第三条上游（未评教名单 + 成绩报表）补上来的同名课 —— 三条上游都真的走了，不是转圈也
        // 不是错误页。
        var scoreShown = false
        shot(
            "jwapp.png",
            frames = 24,
            onFrame = { frame, scene ->
                if (!scoreShown && frame >= 6) scoreShown = hasText(scene, JwappFakeUpstream.COURSE_NAME)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.JwappScore)) }
        check(scoreShown) {
            "jwapp.png：屏上没有夹具那门课（${JwappFakeUpstream.COURSE_NAME}）—— 多半没取到数"
        }

        // 快速考勤流水（第二十条真数据路由）：走**真路由**（外壳 → `AppRoute.Iclassface` →
        // `DesktopSiteGate(ICLASSFACE_SITE_KEY)` → `AppIclassfaceSource`）。登录链是 CAS OAuth2
        // `client_id=1543` → 开放平台 `authorizesw` 中转 → 回落本域的 `/oauth/callback/xjtu_new`
        //（凭据就是 `PHPSESSID`）。图上应当是那行夹具签到记录（地点「中3-2306」）。
        var iclassfaceShown = false
        shot(
            "iclassface.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!iclassfaceShown && frame >= 5) {
                    iclassfaceShown = hasText(scene, IclassfaceFakeUpstream.RECORD_LOCATION)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Iclassface)) }
        check(iclassfaceShown) {
            "iclassface.png：屏上没有夹具那条记录（${IclassfaceFakeUpstream.RECORD_LOCATION}）—— 多半没取到数"
        }

        // 社区（第二十一条真数据路由）：**不套 `DesktopSiteGate`**（上游是 GitHub，没有学校会话要建）。
        // 这一屏是「登录之后才看得到帖子」的：先把一份夹具令牌写进同一份登录态（`SecureGithubSession`
        // 的存储名与键与搬迁前逐字相同），`signIn` 会先用它查一次用户名（那一枪也打向假 GitHub）——
        // 于是图上那条夹具帖子、分类与作者都是从假 API 拉回来的，而不是登录页。
        val communityLogin = runBlocking {
            SecureGithubSession.get().signIn(
                GithubDeviceAccessToken(
                    accessToken = CommunityFakeUpstream.ACCESS_TOKEN,
                    tokenType = "bearer",
                    scopes = emptySet(),
                )
            )
        }
        check(communityLogin.getOrNull() == CommunityFakeUpstream.VIEWER_LOGIN) {
            "社区那张图要先拿到一份夹具登录态：$communityLogin"
        }
        var communityShown = false
        shot(
            "community.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!communityShown && frame >= 5) {
                    communityShown = hasText(scene, CommunityFakeUpstream.DISCUSSION_TITLE)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Community)) }
        check(communityShown) {
            "community.png：屏上没有夹具那条帖子（${CommunityFakeUpstream.DISCUSSION_TITLE}）—— 多半没取到数"
        }

        // 反馈（第 22 条真数据路由）：走**真路由**（外壳 → `AppRoute.Feedback` → `AppFeedbackSource`
        // → 搬进 `:data` 的 `FeedbackApi`）。它**不套 `DesktopSiteGate`** —— 反馈没有校园站点会话，
        // 上游是飞书开放平台（`open.feishu.cn`，https，由 [FeedbackFakeUpstream] 扮）。
        //
        // 两件事先摆好这张图才看得出东西：
        //  ① 注入一份指向夹具的凭据（真机上那五个值由构建期烘进 `DesktopBuildConfig`，见 `DesktopFeedback`）；
        //  ② 往本地账本里补一张工单（真机上那是用户自己提的）—— 不然屏上只有表单与公开问答，
        //    「我提过的」那一栏空空，看不出「开发者的回复真的从上游回来了」。
        FeedbackApi.config = FeedbackApi.Config(
            appId = FeedbackFakeUpstream.APP_ID,
            appSecret = FeedbackFakeUpstream.APP_SECRET,
            baseToken = FeedbackFakeUpstream.BASE_TOKEN,
            submitTable = FeedbackFakeUpstream.SUBMIT_TABLE,
            replyTable = FeedbackFakeUpstream.REPLY_TABLE,
            versionLabel = "${DesktopBuildConfig.VERSION_NAME} (desktop)",
            deviceLabel = "Linux amd64（证据运行）",
            osLabel = "Linux / Java（证据运行）",
        )
        FeedbackStore.addTicket(
            FeedbackStore.Ticket(
                id = FeedbackFakeUpstream.TICKET,
                category = "功能坏了",
                content = "示例反馈：桌面端的按钮点不动（证据运行自己写进账本的那一条）",
                createdAt = java.lang.System.currentTimeMillis(),
            ),
        )
        var feedbackShown = false
        shot(
            "feedback.png",
            // 比其余图高：这一屏从上到下是「表单 → 大家在问 → 我提过的」，
            // 那张工单下面的开发者回复（就是 [FeedbackFakeUpstream.REPLY_TEXT]）一定要框进来，
            // 否则「回复真从上游回来了」这条判据就只剩折叠着的「点开看回复」。
            height = 1400,
            frames = 20,
            onFrame = { frame, scene ->
                if (!feedbackShown && frame >= 5) {
                    feedbackShown = hasText(scene, FeedbackFakeUpstream.REPLY_TEXT)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Feedback)) }
        check(feedbackShown) {
            "feedback.png：屏上没有夹具那条回复（${FeedbackFakeUpstream.REPLY_TEXT}）—— 多半没取到数"
        }

        // ⚠️ 先把「使用声明读过了没」置成已读：那个弹窗用的是 miuix 的 `WindowDialog`，在离屏场景里
        // 是独立 AWT 窗口（建不出来 —— 与 lms 的说明弹窗同一条坑）。置已读就不会弹，图上才是屏本身。
        // 桌面那份键值缝是进程内内存实现 ⇒ 这次置值只活在这一次证据运行里（真机上是它自己记的）。
        keyValueStore("feature_hints").putBoolean("jiaocai1_hint_shown", true)

        // 教材全文库（第 22 批里的 `jiaocai1`）：走**真路由**（外壳 → `AppRoute.Jiaocai1` →
        // `DesktopSiteGate(教材中心)` → `AppJiaocai1Source`）。全文库借教材中心的会话（`/entry/login`
        // → CAS），而**业务侧还有一段**：会话没建立时上游返回一张 `var logoutUrl=…` 的跳转页，
        // 由 `PortalRedirect` 自己跟（夹具把那一跳也扮了，所以这张图里那条链真跑过）。
        //
        // 图上要有夹具那本书：一进屏默认是**分类树**（关键词为空且没选分类），所以这里用 semantics
        // 点两下 —— 先展开一级分类，再点子类（叶子）⇒ 那一发触发检索（与 `:app` 同一段编排）。
        // 点不到就是「这张图里没有书目」，响亮地失败。
        var jiaocaiCategoryOpened = false
        var jiaocaiSearched = false
        var jiaocaiBookShown = false
        shot(
            "jiaocai1.png",
            frames = 30,
            onFrame = { frame, scene ->
                if (frame >= 4) {
                    if (!jiaocaiCategoryOpened) {
                        jiaocaiCategoryOpened = clickByLabel(scene, Jiaocai1FakeUpstream.ROOT_CATEGORY_NAME)
                    } else if (!jiaocaiSearched) {
                        jiaocaiSearched = clickByLabel(scene, Jiaocai1FakeUpstream.CHILD_CATEGORY_NAME)
                    } else if (!jiaocaiBookShown) {
                        jiaocaiBookShown = hasText(scene, Jiaocai1FakeUpstream.BOOK_TITLE)
                    }
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Jiaocai1)) }
        check(jiaocaiCategoryOpened) { "jiaocai1.png：没点到一级分类（${Jiaocai1FakeUpstream.ROOT_CATEGORY_NAME}）" }
        check(jiaocaiSearched) { "jiaocai1.png：没点到子类（${Jiaocai1FakeUpstream.CHILD_CATEGORY_NAME}）" }
        check(jiaocaiBookShown) {
            "jiaocai1.png：屏上没有夹具那本书（${Jiaocai1FakeUpstream.BOOK_TITLE}）—— 多半没取到数"
        }

        // 阅读器：直接进那条参数路由（`AppRoute.Jiaocai1Reader(ssno, title)`）。这一张证明的是
        // **页真的加载、解码、画上去了**：页图是夹具现场画的 JPEG（不是截图），底栅报的是夹具
        // 那本书的线性页表（34 页）。屏上没拿到「1 / 34」就是没打开成功。
        var readerShown = false
        shot(
            "jiaocai1-reader.png",
            frames = 24,
            onFrame = { frame, scene ->
                if (!readerShown && frame >= 6) readerShown = hasText(scene, "1 / 34")
            },
        ) {
            ToolboxDesktopApp(
                auth,
                DesktopTarget.App(AppRoute.Jiaocai1Reader(Jiaocai1FakeUpstream.SSNO, Jiaocai1FakeUpstream.BOOK_TITLE)),
            )
        }
        check(readerShown) { "jiaocai1-reader.png：屏上没有「1 / 34」—— 多半没打开那本书" }
        // 教材查询（`jiaocai` 域，第 24 条真数据路由）：同一个站点（教材中心）、同一个外壳，
        // 走的是那个合并页的**第一栅**（`AppRoute.Jiaocai` → `initialTab = 0`）。
        // 图上要有夹具那本书：进屏时搜索框是空的（先看「搜索建议」那行词），所以用 semantics
        // 点一下建议词 ⇒ 触发教材中心检索。点不到、或结果里没有那本书就是响亮地失败。
        // 「本学期我的教材」那一段在桌面上不显示（没有教务课表缓存 ⇒ 端口回空表，见
        // `DesktopJiaocaiTermTextbooks`）—— 这张图会如实把它拍成「没有那一段」。
        var jiaocaiSuggestTapped = false
        var jiaocaiSearchBookShown = false
        shot(
            "jiaocai.png",
            frames = 24,
            onFrame = { frame, scene ->
                if (frame >= 4) {
                    if (!jiaocaiSuggestTapped) {
                        jiaocaiSuggestTapped = clickByLabel(scene, JIAOCAI_SUGGESTION)
                    } else if (!jiaocaiSearchBookShown) {
                        jiaocaiSearchBookShown = hasText(scene, Jiaocai1FakeUpstream.SEARCH_BOOK_TITLE)
                    }
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Jiaocai)) }
        check(jiaocaiSuggestTapped) { "jiaocai.png：没点到搜索建议（$JIAOCAI_SUGGESTION）" }
        check(jiaocaiSearchBookShown) {
            "jiaocai.png：屏上没有夹具那本教材（${Jiaocai1FakeUpstream.SEARCH_BOOK_TITLE}）—— 多半没取到数"
        }
        // 下载管理（本批的 `download_manager` 路由）：走**真路由**（外壳 → `AppRoute.DownloadManager`，
        // 不要任何站点会话）；但这一张图不是「屏画得出来」的证明，而是**那条队列真下得动**的证明 ——
        // 先让证据运行自己排一条（夹具那条直链，走假上游），**等它真落到磁盘**，再截图。
        // 图上那行「示例活动（下载夹具）」与那个体积就是真下载的结果，不是插进去的假记录。
        // TODO（如实）：真机上这条队列目前还**没有入口** —— 思源学堂的落盘在桌面端是
        // `DesktopLmsSaver`，它的 `supportsVideoQueue = false`（批量对话框不露「课堂回放」那一档）。
        // 也就是：队列、状态机、执行器、落盘都是真的，但「谁来排一条」还差那一步。
        val downloads = DesktopDownloads.manager
        runBlocking {
            downloads.enqueueDownloads(
                courseName = DownloadFakeUpstream.COURSE_NAME,
                activityTitle = DownloadFakeUpstream.ACTIVITY_TITLE,
                activityId = 7,
                videos = listOf(
                    DownloadItem("instructor", DownloadFakeUpstream.INSTRUCTOR_URL),
                    DownloadItem("encoder", DownloadFakeUpstream.ENCODER_URL),
                ),
            )
        }
        val downloadDeadline = System.currentTimeMillis() + 20_000
        var finished = downloads.let { runBlocking { it.getAllTasks() } }
        while (System.currentTimeMillis() < downloadDeadline &&
            finished.any { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
        ) {
            Thread.sleep(50)
            finished = runBlocking { downloads.getAllTasks() }
        }
        check(finished.all { it.status == DownloadStatus.COMPLETED }) {
            "download_manager.png：夹具那条直链没下完（${finished.map { "${it.activityTitle}=${it.status}" }}）"
        }
        val downloadedFiles = finished.map { java.io.File(it.filePath) }
        check(downloadedFiles.all { it.isFile && it.length() == DownloadFakeUpstream.SIZE_BYTES.toLong() }) {
            "download_manager.png：文件没真落到磁盘（${downloadedFiles.map { it.absolutePath }}）"
        }
        var downloadShown = false
        shot(
            "download_manager.png",
            frames = 16,
            onFrame = { frame, scene ->
                if (!downloadShown && frame >= 4) downloadShown = hasText(scene, DownloadFakeUpstream.ACTIVITY_TITLE)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.DownloadManager)) }
        check(downloadShown) {
            "download_manager.png：屏上没有夹具那场活动（${DownloadFakeUpstream.ACTIVITY_TITLE}）—— 多半没取到队列"
        }
        // ── 首页 / 主壳（`AppRoute.Main`，第三十一条真数据路由）─────────────────────────
        // 走**真路由**：外壳 → `AppRoute.Main` → 本端 `DesktopHomeScreen` → `:core` 的 `HomeScreen`。
        // 首页这一端**一个请求都不发**（它只读本机课表缓存），所以「真数据」是**写进本端键值缓存**
        // 的一份夹具课表/考试/教材（值都是编的）—— 与 `schedule.png` 那张读的是同一份缓存
        //（`DesktopScheduleHost.cache`，0600 的 properties 文件）。
        //
        // 公告那一枪注入夹具：证据运行不该去打真站点，更不该把真公告画进图里（与 `library-demo.png`
        // 注入 `DemoLibrarySource` 同一条口径）。`url` 用 `.invalid` 保留域，点了也不会真打开什么。
        val homeTerm = "2026-2027-1"
        val homeToday = todayInSystemZone()
        val homeCache = DesktopScheduleHost(auth).cache(AccountContext.activeAccountId)
        // 两节课同名字同教室，分放「今天晚些」与「明天早八」：不论证据运行在一天里的哪个钟点跑，
        // 「下一项安排」都有东西可画（否则 22:00 之后跑就变成「接下来两周都没课」了）。
        // `courseCode` 用自建日程那个前缀：节假日停的是教务排的课，自建日程照常 ——
        // 这张图不该因为「今天恰好是夹具里的假期」而少一格。
        fun homeFixtureCourse(dayOfWeek: Int, startMinute: Int, endMinute: Int) = CourseItem(
            courseName = "示例课程（夹具）",
            teacher = "示例老师（夹具）",
            location = "主楼A-101",
            weekBits = "1".repeat(20),
            dayOfWeek = dayOfWeek,
            startSection = 1,
            endSection = 2,
            courseCode = "custom_fixture_home",
            startMinuteOfDay = startMinute,
            endMinuteOfDay = endMinute,
        )
        ScheduleCache.writeCurrentTerm(homeCache, homeTerm)
        // 开学日期 = 三周前 ⇒ 首页那一行固定是「第 4 周」（与 `DesktopHomeJvmTest` 同一个夹具）。
        // 日期加减走 `fromEpochDays`：与 `:core` 的 `SchoolCalendarModels.plusDays` 同一个写法。
        val homeStart = LocalDate.fromEpochDays(homeToday.toEpochDays() - 21)
        ScheduleCache.writeStartDate(homeCache, homeTerm, homeStart, weeks = 20)
        ScheduleCache.writeOptimizedCourses(
            homeCache,
            homeTerm,
            listOf(
                homeFixtureCourse(homeToday.dayOfWeek.ordinal + 1, 22 * 60, 23 * 60 + 30),
                homeFixtureCourse(
                    LocalDate.fromEpochDays(homeToday.toEpochDays() + 1).dayOfWeek.ordinal + 1,
                    8 * 60,
                    9 * 60 + 35,
                ),
            ),
        )
        ScheduleCache.writeExams(
            homeCache,
            homeTerm,
            listOf(
                ExamItem(
                    courseName = "示例考试（夹具）",
                    examDate = LocalDate.fromEpochDays(homeToday.toEpochDays() + 5).toString(),
                    examTime = "09:00-11:00",
                    location = "主楼B-201",
                ),
            ),
        )
        val homeBulletinTitle = "首页公告条（夹具）"
        val homeBulletinJson = """{"bulletins":[{"id":"fixture-home-bar","level":"info","title":"$homeBulletinTitle",""" +
            """"body":"值都是编的","url":"https://example.invalid/fixture"}]}"""
        val homeBulletinFetch: suspend (String) -> BulletinApi.FetchResult? = {
            BulletinApi.FetchResult(BulletinRules.parsePayload(homeBulletinJson), homeBulletinJson)
        }

        var homeCourseShown = false
        var homeWeekShown = false
        var homeExamShown = false
        var homeBulletinShown = false
        shot(
            "home.png",
            frames = 12,
            onFrame = { frame, scene ->
                if (frame >= 3) {
                    if (!homeCourseShown) homeCourseShown = hasText(scene, "示例课程（夹具）")
                    if (!homeWeekShown) homeWeekShown = hasText(scene, "第4周")
                    if (!homeExamShown) homeExamShown = hasText(scene, "还有 5 天")
                    if (!homeBulletinShown) homeBulletinShown = hasText(scene, homeBulletinTitle)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Main), homeBulletinFetch) }
        check(homeCourseShown) { "home.png：屏上没出现夹具那节课 —— 首页没读出课表缓存？" }
        check(homeWeekShown) { "home.png：屏上没有「第4周」—— 开学日期那一格没算出来？" }
        check(homeExamShown) { "home.png：屏上没有考试倒计时 —— 考试表那一格没读出来？" }
        check(homeBulletinShown) { "home.png：顶部没有夹具那条公告 —— 公告条没接上共享层？" }

        // 主壳：同一个路由、宽窗拍一张 —— 首页接进**现有导航壳**之后的形态（底栏六格 + 首页高亮）。
        var mainShellShown = false
        var mainHomeTabShown = false
        var mainBulletinShown = false
        shot(
            "main.png",
            width = 900,
            height = 900,
            frames = 8,
            onFrame = { frame, scene ->
                if (frame >= 3) {
                    if (!mainShellShown) mainShellShown = hasText(scene, "示例课程（夹具）")
                    if (!mainHomeTabShown) mainHomeTabShown = hasText(scene, "首页")
                    if (!mainBulletinShown) mainBulletinShown = hasText(scene, homeBulletinTitle)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Main), homeBulletinFetch) }
        check(mainShellShown) { "main.png：宽窗下的首页没画出夹具那节课" }
        check(mainHomeTabShown) { "main.png：底栏上没有「首页」那一格 —— 首页没接进现有壳？" }
        check(mainBulletinShown) { "main.png：顶部没有夹具那条公告" }


        // 「全部页面」：这一页的 height 单独调大（不是其余图那个 900）—— 它是一张要读完的长表，
        // 而这一轮要证明的正是那个 0。把整页都框进来，免得拿半张图交差。
        shot("routes.png", height = 1900, frames = 4) { ToolboxDesktopApp(auth, DesktopTarget.Routes) }

        // 设置（第二十七条真数据路由）：走**真路由**（外壳 → `AppRoute.Settings` → `:core` 的
        // `SettingsScreen` + 本端那份落盘 store）。
        //
        // 这张图要证明两件事，所以先做两件事再拍：
        //  ① 先往落盘的 store 里写 `dark_mode=dark` —— 外壳按这两个外观偏好套主题，于是图上
        //    是**深色**的：设置项不只是存下来了，这一端真照着办了；
        //  ② 拍的过程中用 semantics 点一下「跟随系统取色」那个开关 ⇒ 断言它落到了磁盘上
        //    （`dynamic_color=true`）—— 「设置项真能读写落盘」的判据。
        secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE).putString("dark_mode", SettingsStore.DARK_MODE_DARK)
        var settingsShown = false
        var settingsToggled = false
        var settingsDegraded = false
        shot(
            "settings.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (frame >= 4) {
                    if (!settingsShown) settingsShown = hasText(scene, "版本号")
                    if (!settingsToggled) settingsToggled = clickByLabel(scene, "跟随系统取色")
                    if (settingsShown && settingsToggled && !settingsDegraded) {
                        // 桌面没有的那三组 + 那几行：屏上**一个字都不该出现**
                        settingsDegraded = listOf("新通知提醒", "立即检查更新", "默认启动 Tab", "课表显示考勤")
                            .none { hasText(scene, it) }
                    }
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Settings)) }
        check(settingsShown) { "settings.png：屏上没有「版本号」那一行 —— 多半没渲染出设置页" }
        check(settingsToggled) { "settings.png：没点到「跟随系统取色」那个开关（semantics 里没找到）" }
        check(settingsDegraded) {
            "settings.png：屏上出现了桌面没有的那几项（通知提醒 / 检查更新 / 默认启动 Tab / 课表考勤）"
        }
        check(desktopSettings.dynamicColor) { "settings.png：点了开关，但偏好没落盘" }
        val settingsFile = File(shotsRoot, "${SettingsStore.APP_SETTINGS_FILE}.properties")
        check(settingsFile.exists()) { "settings.png：落盘文件不存在：${settingsFile.absolutePath}" }
        println("设置落盘：${settingsFile.absolutePath}")

        // ── WebVPN 转换器（第二十八条真数据路由）────────────────────────────────────
        // 走**真路由**（外壳 → `AppRoute.WebVpnConverter` → `:core` 的 `WebVpnConverterScreen`），
        // 注入的是 `:data` 的 `WebVpnUtil`（`:core` 的 `WebVpnCodec` 缝，与 Android 同一份实现）。
        //
        // 这张图要证明的是「真能转」，所以拍之前做两件事（都在屏上看得见）：
        //  ① 往唯一的那个输入框里塞 `https://jwxt.xjtu.edu.cn/`（夹具网址，公开站点名、无凭据）；
        //  ② 用 semantics 点一下「转换」—— 断言结果里出现了**密文域名那一段的定值**
        //     （`fae05988692862446b468ca88d1b203b`，与 `DesktopWebVpnCodecJvmTest` 里那一条同值）。
        var vpnTyped = false
        var vpnConverted = false
        shot(
            "webvpn_converter.png",
            frames = 12,
            onFrame = { frame, scene ->
                if (frame >= 3) {
                    if (!vpnTyped) vpnTyped = setTextOnFirstField(scene, "https://jwxt.xjtu.edu.cn/")
                    if (vpnTyped && !vpnConverted) vpnConverted = clickByLabel(scene, "转换")
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.WebVpnConverter)) }
        check(vpnTyped) { "webvpn_converter.png：没找到那个输入框（semantics 里没有 SetText）" }
        check(vpnConverted) { "webvpn_converter.png：没点到「转换」" }

        // ── 我的课表（第三十条真数据路由，本批主角）───────────────────────────────
        // 走**真路由**：外壳 → `AppRoute.Schedule` → `DesktopSiteGate(教务)` → `:core` 的
        // `ScheduleScreen` + 本端 `DesktopScheduleHost`（键值落盘 / 教务会话 / 节假日）。
        //
        // ⚠️ 这一段必须排在「账号管理」之前：那一块会**真切账号**（换绑命名空间），
        // 而课表的缓存、会话都按激活账号走。
        //
        // 这张图要证明的不是「有一屏」，而是**课表真画出来了**，所以断言分两层：
        //  ① 夹具那门课的名字（`高等数学（上）`）出现在屏上 —— 教务会话建起来了、课表取到了；
        //  ② 夹具那个教室（`主楼A-101`，`CourseCell` 画成 `@主楼A-101` 那一行）也在 ——
        //     说明**格子真渲染**了（只有名字出现的话，也可能是哪行说明文字里带的）。
        var scheduleCourseShown = false
        var scheduleCellShown = false
        shot(
            "schedule.png",
            width = 900,
            height = 900,
            // 进屏那一枪要走：建教务会话 → 拉课表 + 考试 + 学期列表 → 落缓存 → 上屏。
            frames = 24,
            onFrame = { frame, scene ->
                if (frame >= 6 && !scheduleCourseShown) {
                    scheduleCourseShown = hasText(scene, JwxtFakeUpstream.SCHEDULE_1_COURSE)
                }
                if (scheduleCourseShown && !scheduleCellShown) {
                    scheduleCellShown = hasText(scene, JwxtFakeUpstream.SCHEDULE_1_ROOM)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Schedule)) }
        check(scheduleCourseShown) { "schedule.png：屏上没出现夹具的课名 —— 多半没取到课表（会话或取数断了）" }
        check(scheduleCellShown) { "schedule.png：课名在、教室不在 —— 课程格子没画出来？" }
        // 顺手确认上屏的数据真落到了本端那份缓存里（逐条钉落盘的是 `DesktopScheduleJvmTest`）。
        check(
            com.xjtu.toolbox.schedule.ScheduleCache.readCurrentTerm(
                DesktopScheduleHost(auth).cache(AccountContext.activeAccountId),
            ) != null,
        ) { "schedule.png：拍完了但课表缓存是空的 —— 上屏的数据没落盘" }

        // ── 账号管理（多账号，第二十九条真数据路由）────────────────────────────────
        // 走**真路由**（外壳 → `AppRoute.Accounts` → `:core` 的 `AccountManagerScreen`），注入的是
        // `DesktopAccounts`（切/增/删真落盘：账号表在 `AccountStore`，凭据与会话按账号后缀分文件）。
        //
        // 拍之前做两件事，让这张图同时是「多账号真能用」的证据：
        //  ① 上面那次「真登录」已经把夹具账号写进了账号表；这里再**加一条夹具账号**（值都是编的）
        //     —— 于是列表里是两条，「已保存的账号（2）」；
        //  ② **真切一次**到第二条（走 `DesktopAccounts.switchTo`，与窗口里点「切换」是同一个动作本体）
        //     ⇒ 顶部那张「当前账号」卡与列表里的高亮都会变成第二条 —— 图上看得见，代码里也断言。
        val secondAccountId = "2021000002"
        AccountStore().upsert(
            Account(
                accountId = secondAccountId,
                password = "fixture-password-2",
                accountType = com.xjtu.toolbox.auth.AccountType.POSTGRADUATE,
                lastUsedAt = System.currentTimeMillis(),
            ),
        )
        runBlocking { DesktopAccounts(auth).switchTo(secondAccountId) }
        check(AccountContext.activeAccountId == secondAccountId) {
            "accounts.png：切账号没真的换绑命名空间（当前账号还是 ${AccountContext.activeAccountId}）"
        }
        var accountsShown = false
        shot(
            "accounts.png",
            frames = 12,
            onFrame = { frame, scene ->
                if (!accountsShown && frame >= 4) accountsShown = hasText(scene, "已保存的账号（2）")
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Accounts)) }
        check(accountsShown) { "accounts.png：屏上没有「已保存的账号（2）」—— 多半没读到账号表" }
        check(AccountStore().list().map { it.accountId }.contains(LibraryFakeUpstream.USERNAME)) {
            "accounts.png：夹具账号不在账号表里（真登录那一步应当把它写进去）"
        }

        // ── 合成西交大（`game_merge`）：如实说明它为什么跑不了 ───────────────────
        // 这条路由卡的是**载体**（WebView + Matter.js 物理引擎），不是取数。这张图是那张说明页
        // 本身 —— 它**不进** `DESKTOP_SUPPORTED_ROUTES`（那里只放真能用的），见 `HostUnavailableScreen`。
        var mergeShown = false
        shot(
            "game_merge.png",
            frames = 4,
            onFrame = { _, scene ->
                if (!mergeShown) mergeShown = hasText(scene, "合成西交大在桌面上跑不了")
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.GameMerge)) }
        check(mergeShown) { "game_merge.png：没画到那张说明页" }

        // ── 匹配交友（`schedule_match`）：如实说明它卡的是本机数据 ────────────────
        var matchShown = false
        shot(
            "schedule_match.png",
            frames = 4,
            onFrame = { _, scene ->
                if (!matchShown) matchShown = hasText(scene, "匹配交友在桌面上还没有数据")
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Match)) }
        check(matchShown) { "schedule_match.png：没画到那张说明页" }
    }
    exitProcess(0)
}

/**
 * 「查教材」栅那行搜索建议里的一个词（与 `JiaocaiSearchContent` 里的字面量一致）。
 * 证据图点它一下就会发起那一发检索 —— 点的是夹具认得的任意关键词都行（夹具对
 * `MISS_SEARCH_KEYWORD` 之外的关键词都回同一行书目）。
 */
private const val JIAOCAI_SUGGESTION = "高等数学"

/** 假上游：`withFakeCampus` 期间有效（把端口灌给常驻 selector 的就是它）。 */
private lateinit var fake: FakeCampusProxy

/**
 * 把假的校园上游起起来（`:testkit` 的 [FakeCampusProxy]：一个本地 HTTP 代理，按 host 分派
 * 图书馆与校历），并在里面跑 [block]。
 *
 * 与 `:data:jvmTest` 的 `LibraryLoginSessionJvmTest.withFakeCas` 是同一套手法，
 * 差别有两处：那边每条测试自己清理落盘存储（这边整个进程只有一次，所以前置在 `main` 里）；
 * 这边的代理同时扮两个域名（校历那张图要一次跑通两条链路）。
 */
private fun withFakeCampus(block: (DesktopAuth) -> Unit) {
    // 下载队列（`:desktop` 那份 0600 的 JSON）也清一下：`DesktopDownloads.manager` 是进程级单例，
    // 它那份存储的根在**第一次碰它时**才定下来（见下面 `download_manager.png` 那一段）。
    wipeSecureStore("download_tasks")
    // 账号表（`AccountStore`，存储名 `xjtu_accounts`）同样清一下：多账号之后它会在「真登录」那一步
    // 长出一条夹具账号，而 `restore()` 会直接拿它恢复成已登录 —— 跑两次就该是同一种起点。
    wipeSecureStore(AccountStore.FILE_NAME)
    AccountContext.activeAccountId = null
    fake = FakeCampusProxy(casEnabled = true)

    // 保险：临时目录里本来不该有东西，但这两行让「跑了两次」也不会互相污染。
    // ⚠️ 账号命名空间也要清（桌面登录会把 backends 换到 `cookies_normal_<学号>`）。
    val suffix = com.xjtu.toolbox.account.AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
    for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
        wipeSecureStore("${base}_default")
        wipeSecureStore("$base$suffix")
    }
    wipeSecureStore(JvmCredentialStore.FILE_NAME)
    for (name in listOf(
        "cookies_normal_default", "cookies_webvpn_default",
        "cookies_normal$suffix", "cookies_webvpn$suffix",
    )) {
        PersistentCookieJar(name).clear()
    }

    // 预置一份缓存的 RSA 公钥（照真机的路径：首次登录取回后存进凭据文件，之后复用）。
    // 没有它，`XJTULogin` 会去 `https://login.xjtu.edu.cn/cas/jwt/publicKey` 取 —— https，
    // 本地假上游给不了（见文件头第 2 条坑）。
    val credentials = JvmCredentialStore()
    credentials.rsaPublicKey = TestRsaKey.publicKeyBase64

    fake.start()
    try {
        block(DesktopAuth(credentials))
    } finally {
        fake.close()
    }
}

/**
 * 在 AWT 事件队列上执行并等结果（**所有 Compose 调用都必须落在 EDT 上**）。
 *
 * 为什么：`:core` 的屏一律用 `viewModel { }` + `viewModelScope`，而 viewModelScope 跑在
 * `Dispatchers.Main.immediate` 上 —— 桌面端的 Main 就是 AWT 事件队列。真窗口里这没问题
 * （Compose Desktop 的渲染循环本身就在 EDT 上），但离屏场景是在**调用线程**上渲染的，
 * 不在 EDT 就会出现「VM 在 EDT 上更新状态、渲染线程同时在 measure/layout」⇒ Skia 直接抛
 * `performMeasureAndLayout called during measure layout`（实测踩到，且表现为「屏上永远没有数据」）。
 *
 * `SwingUtilities.invokeAndWait` 自己会把异常包进 `InvocationTargetException`，
 * 这里解开一层，好让失败的堆栈指到真正的出错位置。
 */
private fun <T> onEdt(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (t: Throwable) {
            failure = t
        }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
