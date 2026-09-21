package com.xjtu.toolbox.game.net

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * 房主页二维码里装的内容：局域网地址、BLE 服务 UUID、一次性口令。
 *
 * **不放蓝牙地址**：Android 6.0 起 App 读不到本机蓝牙 MAC（`getAddress()` 固定返回
 * `02:00:00:00:00:00`），所以加入方连不了"地址"，只能靠 [bleServiceUuid] 过滤扫描结果，
 * 连接用的地址是扫描回调里给的，不是二维码里的。
 *
 * [PREFIX] 是跟扫码登录（`qrlogin/CasQrLogin`）、匹配交友分享码区分的标记：三处都用同一个
 * [com.xjtu.toolbox.qrlogin.QrScannerView] 扫描，各自只认自己的前缀，扫到别人的码要能
 * 报"这不是联机对局码"，而不是尝试 JSON 解析失败后一头雾水。
 */
data class OnlineQrPayload(
    val v: Int = 1,
    val game: String,
    val lan: LanInfo?,
    val ble: BleInfo?,
    val token: String,
) {
    data class LanInfo(val ip: String, val port: Int)
    data class BleInfo(val serviceUuid: String)

    companion object {
        const val PREFIX = "xjtutoolbox-game-v1:"

        private val gson = Gson()

        fun encode(payload: OnlineQrPayload): String = PREFIX + gson.toJson(payload)

        /** 不是本格式（前缀不对/JSON 解析失败）一律返回 null，调用方显示"这不是联机对局二维码"。 */
        fun decode(text: String): OnlineQrPayload? {
            if (!text.startsWith(PREFIX)) return null
            val json = text.removePrefix(PREFIX)
            return try {
                gson.fromJson(json, OnlineQrPayload::class.java)
                    ?.takeIf { it.token.isNotBlank() && it.game.isNotBlank() && (it.lan != null || it.ble != null) }
            } catch (_: JsonSyntaxException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
