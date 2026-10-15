package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusVenueApi
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.venue.VenueScreen
import io.ktor.client.HttpClient

/**
 * 体育场馆（`AppRoute.Venue`）在浏览器这一端的**宿主**：屏本体是 `:core` 的 [VenueScreen]，
 * 本文件只管两件在浏览器上另有说法的事 —— **取数形态**与**「去支付」那一跳**。
 *
 * ## 取数（沿用 `CampusVenueApi` 的两种形态）
 *
 * | 形态 | 能做什么 |
 * |---|---|
 * | campus-api（旧路） | 只读：`canBook`/`canCancel` 都是 false ⇒ 屏上不画「确认预订」「去支付」「取消订单」，时段格子不可选 |
 * | serve（契约 §5.3.2） | 写端点已落地 + 滑块在本端画（[WebSlideCaptchaHost]）⇒ 真能下单/取消；「去支付」见下 |
 *
 * ## 「去支付」：浏览器里只能打开**一个**地址，而那一跳**不担保落成能付款的一页**（契约 §5.3.2 第 6 条）
 *
 * 屏把两个地址一起交给宿主（见 [VenueScreen] 的 `onOpenBrowser`）：第一站是站点登录页
 * （`browserLoginUrl`），之后是支付页（`paymentUrl`）。Android 的内置浏览器能自己串这两跳 ——
 * `BrowserScreen.syncCookiesToWebView` 把 App 的 cookie 罐灌进 WebView 的 `CookieManager`，
 * 加载完 Login 那一页、`isAuthHop` 判不中转页之后 `loadUrl(thenUrl)`；**浏览器不能**：页面
 * 读不到别的域名的 location，更不能替它写 cookie，也没有「把宿主进程里的 cookie 罐注入页面」这一手。
 *
 * 这一端因此是「优先支付页、只开一个标签」（[venuePayTarget]）：serve 形态下
 * `CampusVenueApi.paymentUrl` 给的是 serve 的代跳地址（`GET /api/venue/pay`）⇒ 302 到
 * 80 端口那台站点上的支付页。**302 这一跳一定会走完**（顶层跳转，任何浏览器都跟）；**但落在
 * 那一页上能不能付款，本端不担保**——如实三条：
 *
 * ① **扣款在站点那一页上由人点**，本端与 serve 都不代付（客户端本来就没有这个动作）；
 * ② **serve 的会话带不过去**：那是 serve 进程 cookie 罐里的场馆站 `SESSION`（8080 那套），而页面
 *    不能替别的域名写 cookie ⇒ 浏览器这一侧的会话只能由站点自己那次登录给。依据是 `:data` 的
 *    `VenueLogin.PAY_BASE_URL` 那句 KDoc：支付页「要求浏览器自身的会话，不能把 App 内的 cookie
 *    拼进 URL」——Android 之所以能，靠的就是上面那手 `CookieManager` 注入，浏览器里没有对应的东西；
 * ③ 于是**第一次点很可能先落在该站点自己的登录页**（由它自己的跳转去要，统一认证过完一般回到这一页）。
 *    ⚠️ 这一条**没能在线验证**（不许真发起支付、也没去探测那台支付站）⇒ 页面上照 ③ 如实写一句
 *    （见 [PAY_HOP_NOTE]）：「跳过去后若要求登录，请用 App / 桌面端支付」。**不许**读成「本端已支持真支付」。
 *
 * 要真做到「浏览器里就能付」，得让 serve 去代理那一页（取回 HTML 再同源发出去，连它的表单与 JS 一起转）——
 * 那是另一个量级的新暴露面，本批没做，缺口与理由写在契约 §5.3.2 第 6 条。
 */

/**
 * 「去支付」这一枪该打开哪个地址（纯函数：屏的宿主与 `:web` 的 wasm 测试共用同一份判据）。
 *
 * 三种写法（顺序就是优先级）：
 * - `then` 非空 ⇒ 打开它。serve 形态走的就是这一支（`/api/venue/pay` 那条 302），
 *   支付页才是用户要的东西（那一跳会不会再要一次登录见文件头：不担保）；
 * - 否则 `loginUrl` 非空 ⇒ 打开它：只剩登录站可去时也得有个动作（把这一枪吞掉就是**静默没动作**）；
 * - 两个都空 ⇒ `null`，由调用方在屏上如实说一句 —— **不许什么都不做**（serve 形态从前就是
 *   把空串交给宿主，点下去无声无息；那一格正是本工单要清掉的）。
 */
internal fun venuePayTarget(loginUrl: String, then: String): String? = when {
    then.isNotBlank() -> then
    loginUrl.isNotBlank() -> loginUrl
    else -> null
}

/** serve 形态下页顶那一行如实说明（内容见文件头；campus-api 形态不画它，屏与今天逐字相同）。 */
private const val PAY_HOP_NOTE =
    "「去支付」会把浏览器新标签送到 serve 的代跳地址（GET /api/venue/pay → 302 到场馆站 80 端口上的支付页），" +
        "扣款在那一页上完成 —— 本端与 serve 都不代付。⚠️ 这一跳不保证直接落成能付款的一页：场馆站的会话在" +
        "浏览器自己这边（serve 的会话在它自己的 cookie 罐里，页面替不了别的域名写 cookie），所以" +
        "跳过去后若要求登录，请用 App / 桌面端支付（或按那一页自己的登录流程过一次再回来）。" +
        "判据：docs/api-contract.md §5.3.2 第 6 条。"

/** 真到了「两个地址都空」那一档才会出现的一句（见 [venuePayTarget]：不许静默什么都不做）。 */
private const val NO_PAY_TARGET_NOTE =
    "这一枪没有可打开的地址：服务端如实报了 canBook:false（campus-api 形态没有场馆写端点）⇒ " +
        "屏上本不该出现「去支付」。请切到 serve 模式（docs/api-contract.md §5.3.2）。"

/**
 * 场馆那一屏（`:core` 的 [VenueScreen]），宿主面由本文件补。
 *
 * @param client 这一份 `:web` 的 HTTP 客户端（带 serve 令牌，见 [ServeTokenHolder]）
 * @param mode [ApiMode]：`serve` 时写端点与支付跳转都在（见文件头那两张表）
 * @param onBack 返回上一屏（壳给的导航；共享屏的返回箭头就是它）
 */
@Composable
internal fun WebVenueScreen(
    client: HttpClient,
    mode: ApiMode,
    onBack: () -> Unit,
) {
    // 「功能说明弹过了没」在 Web 存 localStorage（`:app` 存 feature_hints 那个 SharedPreferences），
    // 键名与 `:app` 同一个；文案里那两条只在能下单那端成立的话，屏会自己按 canBook 少说。
    val hintPrefs = remember { keyValueStore("feature_hints") }
    // 只有「点了却拿不到地址」那一档会写上（见 NO_PAY_TARGET_NOTE）。
    var payGap by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (mode == ApiMode.SERVE) WebGapNote(PAY_HOP_NOTE)
        payGap?.let { WebGapNote(it) }
        Box(Modifier.weight(1f)) {
            VenueScreen(
                source = remember(client, mode) { CampusVenueApi(client, API_BASE, mode) },
                onBack = onBack,
                onOpenBrowser = { loginUrl, then ->
                    when (val target = venuePayTarget(loginUrl, then)) {
                        null -> payGap = NO_PAY_TARGET_NOTE
                        else -> openInNewTab(target)
                    }
                },
                // 滑块宿主槽位（serve 模式下下单要它；campus-api 的 canBook=false ⇒ 验证码弹窗不出现）。
                captchaHost = remember { WebSlideCaptchaHost },
                showFirstUseHint = !hintPrefs.getBoolean("venue_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("venue_hint_shown", true) },
            )
        }
    }
}
