package com.xjtu.toolbox

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.coupon.CouponFakeUpstream
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream
import com.xjtu.toolbox.media.DownloadFakeUpstream
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream
import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream
import com.xjtu.toolbox.fitness.FitnessFakeUpstream
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.jwapp.JwappFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.lms.LmsFakeUpstream
import com.xjtu.toolbox.iclassface.IclassfaceFakeUpstream
import com.xjtu.toolbox.community.CommunityFakeUpstream
import com.xjtu.toolbox.notification.NotificationFakeUpstream
import com.xjtu.toolbox.venue.VenueFakeUpstream
import com.xjtu.toolbox.ywtb.YwtbFakeUpstream
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream

/** 把假的校园上游起成一个本地端口，按请求行里的 host 分派给各个夹具（裸 TCP 前置 + 明文/TLS 两个后端，为什么不是一个 `HttpServer` 就够见 [FakeUpstreamFront]）。 */
class FakeCampusProxy(private val casEnabled: Boolean = true) : AutoCloseable {

    /** 图书馆座位系统 + 统一认证（可选扮演）。 */
    val library: LibraryFakeUpstream = LibraryFakeUpstream(LibraryFakeUpstream.LIBRARY_BASE, casEnabled)

    /** 校历门户。 */
    val calendar: SchoolCalendarFakeUpstream = SchoolCalendarFakeUpstream()

    /** 体测系统（`tyxylp.xjtu.edu.cn`，**https**，见 [FakeUpstreamFront]）。 */
    val fitness: FitnessFakeUpstream = FitnessFakeUpstream()

    /** 教务系统（`jwxt.xjtu.edu.cn`，https）：全校课表 + 成绩报表两条链共用这一个上游。 */
    val jwxt: JwxtFakeUpstream = JwxtFakeUpstream()

    /** 校园卡（`ncard.xjtu.edu.cn`，https）：卡面 + 流水两条取数共用一个上游。 */
    val campusCard: CampusCardFakeUpstream = CampusCardFakeUpstream()

    /** 空闲教室：智慧教室平台 `js.xjtu.edu.cn` + 课表快照 CDN `gh-release.xjtutoolbox.com`（都是 https）。 */
    val emptyRoom: EmptyRoomFakeUpstream = EmptyRoomFakeUpstream()

    /** 体育场馆：开放平台 `org.xjtu.edu.cn` + 场馆站 `202.117.17.144:8080`（明文 http）。 */
    val venue: VenueFakeUpstream = VenueFakeUpstream()

    /** 消息收纳：一网通办门户 `ywtb.xjtu.edu.cn` + 收纳那四路各自的域名（消息 / 事务中心 / 预约 / 校车，五个 host 都是 https）——五个域名一个夹具：它们属于同一条登录链的「登录后取数」（见 [YwtbFakeUpstream] 的 KDoc）；CAS 那半台在 [library] 里，它对一网通办那个 service 会签一枚 JWT ticket（真正发 idToken 的就是它）。 */
    val ywtb: YwtbFakeUpstream = YwtbFakeUpstream()

    /** 通知公告：三个代表性源的域名。 */
    val notification: NotificationFakeUpstream = NotificationFakeUpstream()

    /** 加餐券：业务在 `egc.xjtu.edu.cn`（https），开放平台那一跳`org.xjtu.edu.cn/openplatform/oauth/authorizesw` 也归它（登录链的一部分，见 KDoc）。 */
    val coupon: CouponFakeUpstream = CouponFakeUpstream()

    /** 电子凭证：成绩单所在的 `dzpz.xjtu.edu.cn`（https）。 */
    val dzpz: DzpzFakeUpstream = DzpzFakeUpstream()

    /** 宿舍电费：业务在 `ssn.xjtu.edu.cn/cems`，开放平台那一跳`org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1759` 也归它（登录链的一部分，见 KDoc）。 */
    val dormPower: DormPowerFakeUpstream = DormPowerFakeUpstream()


    /** 考勤：本科生业务站 `bk-kq.xjtu.edu.cn/sa`（https）。 */
    val attendance: AttendanceFakeUpstream = AttendanceFakeUpstream()

    /** 思源学堂：业务在 `lms.xjtu.edu.cn`（https）。 */
    val lms: LmsFakeUpstream = LmsFakeUpstream()

    /** 移动教务：开放平台 `org.xjtu.edu.cn` + 业务站 `jwapp.xjtu.edu.cn`。 */
    val jwapp: JwappFakeUpstream = JwappFakeUpstream()

    /** 人脸识别签到：开放平台 `authorizesw` + 业务站 `iclassface.xjtu.edu.cn`（https）。 */
    val iclassface: IclassfaceFakeUpstream = IclassfaceFakeUpstream()

    /** 社区：两个域名 —— `github.com`（设备码那两个端点）与`api.github.com`（GraphQL）：社区三屏的取数与登录都在 GitHub 上，与学校站点无关。 */
    val community: CommunityFakeUpstream = CommunityFakeUpstream()

    /** 回放视频的假直链（`video-download.xjtu.edu.cn`，明文 http） */
    val download: DownloadFakeUpstream = DownloadFakeUpstream()
    /** 反馈：上游是飞书开放平台 `open.feishu.cn`（https），与学校站点无关，也不套 `DesktopSiteGate`（没有校园会话要建）。 */
    val feedback: FeedbackFakeUpstream = FeedbackFakeUpstream()

    /** 教材全文库：两个域名一个夹具。 */
    val jiaocai1: Jiaocai1FakeUpstream = Jiaocai1FakeUpstream()

    private val server: FakeUpstreamFront =
        FakeUpstreamFront(::dispatch, HTTPS_HOSTS)

    /** 代理端口（`start()` 之后有效）。 */
    val port: Int get() = server.port

    /** 起服务并把 selector 指过来。返回自身，便于 `.start().use { }`。 */
    fun start(): FakeCampusProxy = apply {
        server.start()
        FakeUpstreamProxySelector.port = port
    }

    override fun close() {
        FakeUpstreamProxySelector.port = 0
        server.close()
    }

    /** 按目标 host 分派。 */
    private fun dispatch(exchange: HttpExchange) {
        val host = exchange.requestURI.host?.lowercase()
            ?: exchange.requestHeaders.getFirst("Host")?.substringBefore(':')?.lowercase()
        when (host) {
            LibraryFakeUpstream.LIBRARY_HOST, LibraryFakeUpstream.CAS_HOST -> library.handle(exchange)
            FitnessFakeUpstream.HOST -> fitness.handle(exchange)
            JwxtFakeUpstream.HOST -> jwxt.handle(exchange)
            CampusCardFakeUpstream.HOST -> campusCard.handle(exchange)
            EmptyRoomFakeUpstream.JS_HOST -> emptyRoom.handleJs(exchange)
            EmptyRoomFakeUpstream.CDN_HOST -> emptyRoom.handleCdn(exchange)
            VenueFakeUpstream.OAUTH_HOST ->
                when {
                    exchange.requestURI.path.startsWith("/openplatform/oauth/authorizesw") ->
                        if (exchange.requestURI.query?.contains(IclassfaceFakeUpstream.HOST) == true) {
                            iclassface.handleOrg(exchange)
                        } else {
                            coupon.handleOrg(exchange)
                        }
                    exchange.requestURI.query?.contains("appId=1759") == true -> dormPower.handleOauth(exchange)
                    exchange.requestURI.query?.contains("appId=${JwappFakeUpstream.APP_ID}") == true -> jwapp.handleOauth(exchange)
                    else -> venue.handleOauth(exchange)
                }
            CouponFakeUpstream.HOST -> coupon.handleBusiness(exchange)
            DormPowerFakeUpstream.HOST -> dormPower.handle(exchange)
            AttendanceFakeUpstream.HOST -> attendance.handle(exchange)
            LmsFakeUpstream.HOST -> lms.handle(exchange)
            JwappFakeUpstream.HOST -> jwapp.handle(exchange)
            VenueFakeUpstream.HOST -> venue.handle(exchange)
            YwtbFakeUpstream.HOST -> ywtb.handlePortal(exchange)
            YwtbFakeUpstream.MESSAGE_HOST -> ywtb.handleMessages(exchange)
            CommunityFakeUpstream.LOGIN_HOST -> community.handleLogin(exchange)
            CommunityFakeUpstream.API_HOST -> community.handleApi(exchange)
            FeedbackFakeUpstream.HOST -> feedback.handle(exchange)
            Jiaocai1FakeUpstream.JIAOCAI_HOST -> jiaocai1.handleJiaocai(exchange)
            Jiaocai1FakeUpstream.JIAOCAI1_HOST -> jiaocai1.handleJiaocai1(exchange)
            IclassfaceFakeUpstream.HOST -> iclassface.handle(exchange)
            YwtbFakeUpstream.TRANSACTION_HOST -> ywtb.handleTodos(exchange)
            YwtbFakeUpstream.RESERVATION_HOST -> ywtb.handleBookings(exchange)
            YwtbFakeUpstream.BUS_HOST -> ywtb.handleBus(exchange)
            DzpzFakeUpstream.HOST -> dzpz.handle(exchange)
            NotificationFakeUpstream.JWC_HOST -> notification.handleJwc(exchange)
            NotificationFakeUpstream.CLET_HOST -> notification.handleClet(exchange)
            NotificationFakeUpstream.OA_HOST -> notification.handleOa(exchange)
            SchoolCalendarFakeUpstream.HOST ->
                if (exchange.requestURI.path == SchoolCalendarFakeUpstream.PATH) {
                    calendar.handle(exchange)
                } else {
                    // 路径漂了要响亮地失败（否则「换了个 URL 但假上游照答」会静默成立）
                    val body = "no such calendar path: ${exchange.requestURI.path}".toByteArray()
                    exchange.sendResponseHeaders(404, body.size.toLong())
                    exchange.responseBody.write(body)
                    exchange.close()
                }
            // 回放视频的假直链（下载管理那一条路由的证据与端到端测试）。
            DownloadFakeUpstream.HOST -> download.handle(exchange)
            // host 识别不出来（既没 absolute-form 也没 Host 头）⇒ 按图书馆夹具处理。
            else -> library.handle(exchange)
        }
    }

    companion object {
        /** 装上两件进程级前置 —— 都必须在碰 `HttpClients`（建会话 / 建任何客户端）之前调。 */
        fun installFakeUpstreams() {
            FakeUpstreamProxySelector.install()
            FakeUpstreamTls.installTrustStore(HTTPS_HOSTS)
        }

        /** 需要 CONNECT 隧道的那几个域名 —— 一枚证书带这几个 SAN（信任库是单个系统属性，只能装一份）。 */
        private val HTTPS_HOSTS = setOf(
            FitnessFakeUpstream.HOST,
            JwxtFakeUpstream.HOST,
            CampusCardFakeUpstream.HOST,
            LibraryFakeUpstream.CAS_HOST,
            // 空闲教室那两档（都是 https）；域名集合变了自签证书会重生。
            EmptyRoomFakeUpstream.JS_HOST,
            EmptyRoomFakeUpstream.CDN_HOST,
            // 消息收纳那五个域名（门户 + 消息 / 事务中心 / 预约 / 校车四路）：全是 https。
            // 它们占四个 SAN —— 也就是这枚自签证书又重签一份的原因（本校只有这一处同时扮这么多域名）。
            YwtbFakeUpstream.HOST,
            YwtbFakeUpstream.MESSAGE_HOST,
            YwtbFakeUpstream.TRANSACTION_HOST,
            YwtbFakeUpstream.RESERVATION_HOST,
            YwtbFakeUpstream.BUS_HOST,
            // 体育场馆的登录入口那一跳（`org.xjtu.edu.cn` 的 OAuth 授权页）；业务那半台是**明文**
            // `202.117.17.144:8080`，不走隧道，所以不在这里。
            VenueFakeUpstream.OAUTH_HOST,
            // 移动教务的回跳落点那一半（`jwapp.xjtu.edu.cn`）：回跳那一枪是**明文 http**（不走隧道），
            // 但它三个业务接口都是 https —— 同一个 host 两种形态，所以这枚 SAN 必须有。
            JwappFakeUpstream.HOST,
            // 加餐券：`egc.xjtu.edu.cn` 是 https（业务接口都是；登录链上的开放平台
            // `org.xjtu.edu.cn` 与场馆共用一枚 SAN，见 VenueFakeUpstream.OAUTH_HOST）。
            CouponFakeUpstream.HOST,

            // 宿舍电费：`ssn.xjtu.edu.cn` 是 https（业务接口都是；缴费页入口是明文 http，不走隧道 ——
            // 与场馆同款，所以这里只有业务那半台占一枚 SAN）。
            DormPowerFakeUpstream.HOST,
            // 考勤：`bk-kq.xjtu.edu.cn` 是 https（登录入口与业务接口都是；本科生直连业务站那条链）。
            AttendanceFakeUpstream.HOST,
            LmsFakeUpstream.HOST,
            IclassfaceFakeUpstream.HOST,
            CommunityFakeUpstream.LOGIN_HOST,
            CommunityFakeUpstream.API_HOST,
            // 电子凭证（成绩单）：`dzpz.xjtu.edu.cn` 是 https（它的登录入口页与业务接口都是）。
            DzpzFakeUpstream.HOST,
            FeedbackFakeUpstream.HOST,
            Jiaocai1FakeUpstream.JIAOCAI_HOST,
            Jiaocai1FakeUpstream.JIAOCAI1_HOST,
        ) + NotificationFakeUpstream.HOSTS
    }
}

