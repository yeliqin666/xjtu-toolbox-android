package com.xjtu.toolbox.game.net

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 * 房主页二维码里装的内容：BLE 服务 UUID、一次性口令。
 *
 * 联机只走蓝牙：局域网直连在校园网下几乎永远不通（AP 隔离禁止手机互连），实测只有蓝牙可用，
 * 留着它只会让加入方先白等一轮局域网超时。旧版二维码里的 `lan` 字段 Gson 会直接忽略。
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
    // 这是两台设备之间的协议，字段名必须用 @SerializedName 钉死：不钉的话正式包经 R8 混淆后
    // 键名变成 a/b/c，而且每次编译都可能不同，两台装了不同版本的手机互扫就是「这不是联机对局码」。
    @SerializedName("v") val v: Int = 1,
    @SerializedName("game") val game: String,
    @SerializedName("ble") val ble: BleInfo?,
    @SerializedName("token") val token: String,
) {
    data class BleInfo(@SerializedName("serviceUuid") val serviceUuid: String)

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
                    ?.takeIf { it.token.isNotBlank() && it.game.isNotBlank() && it.ble != null && it.ble.serviceUuid.isNotBlank() }
            } catch (_: JsonSyntaxException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
