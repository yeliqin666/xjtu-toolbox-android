package com.xjtu.toolbox.game.net

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable

/**
 * 房主页二维码里装的内容：BLE 服务 UUID、一次性口令。
 *
 * 联机只走蓝牙：局域网直连在校园网下几乎永远不通（AP 隔离禁止手机互连），实测只有蓝牙可用，
 * 留着它只会让加入方先白等一轮局域网超时。旧版二维码里的 `lan` 字段解码时直接忽略。
 *
 * **不放蓝牙地址**：Android 6.0 起 App 读不到本机蓝牙 MAC（`getAddress()` 固定返回
 * `02:00:00:00:00:00`），所以加入方连不了"地址"，只能靠 [bleServiceUuid] 过滤扫描结果，
 * 连接用的地址是扫描回调里给的，不是二维码里的。
 *
 * [PREFIX] 是跟扫码登录（`qrlogin/CasQrLogin`）、匹配交友分享码区分的标记：三处都用同一个
 * [com.xjtu.toolbox.qrlogin.QrScannerView] 扫描，各自只认自己的前缀，扫到别人的码要能
 * 报"这不是联机对局码"，而不是尝试 JSON 解析失败后一头雾水。
 */
@Serializable
data class OnlineQrPayload(
    // 两台设备之间的协议：字段名即线上格式，编译期生成，不受混淆影响
    val v: Int = 1,
    val game: String = "",
    val ble: BleInfo? = null,
    val token: String = "",
) {
    @Serializable
    data class BleInfo(val serviceUuid: String = "")

    companion object {
        const val PREFIX = "xjtutoolbox-game-v1:"

        fun encode(payload: OnlineQrPayload): String = PREFIX + AppJson.encodeToString(payload)

        /** 不是本格式（前缀不对/JSON 解析失败）一律返回 null，调用方显示"这不是联机对局二维码"。 */
        fun decode(text: String): OnlineQrPayload? {
            if (!text.startsWith(PREFIX)) return null
            return runCatching { AppJson.decodeFromString<OnlineQrPayload>(text.removePrefix(PREFIX)) }.getOrNull()
                ?.takeIf { it.token.isNotBlank() && it.game.isNotBlank() && !it.ble?.serviceUuid.isNullOrBlank() }
        }
    }
}
