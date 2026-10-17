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

/** 体育场馆（`AppRoute.Venue`）在浏览器这一端的宿主。 */

/** 「去支付」这一枪该打开哪个地址（纯函数：屏的宿主与 `:web` 的 wasm 测试共用同一份判据）。 */
internal fun venuePayTarget(loginUrl: String, then: String): String? = when {
    then.isNotBlank() -> then
    loginUrl.isNotBlank() -> loginUrl
    else -> null
}

/** serve 形态下页顶那一行说明（campus-api 形态不画）。 */
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

/** 场馆那一屏（`:core` 的 [VenueScreen]），宿主面由本文件补。 */
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
