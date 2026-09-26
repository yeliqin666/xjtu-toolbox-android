package com.xjtu.toolbox.game.net

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 联机二维码和对局消息是两台设备之间的协议：键名和旧版本一致，旧版本发来的报文要读得懂。 */
class WireFieldNamesTest {

    @Test
    fun qrPayload_keys() {
        val encoded = OnlineQrPayload.encode(OnlineQrPayload(game = "gomoku", ble = OnlineQrPayload.BleInfo("uuid"), token = "t"))
        val obj = AppJson.parseToJsonElement(encoded.removePrefix(OnlineQrPayload.PREFIX)).jsonObject
        assertEquals(setOf("v", "game", "ble", "token"), obj.keys)
        assertEquals(setOf("serviceUuid"), obj.getValue("ble").jsonObject.keys)
    }

    @Test
    fun qrPayload_readsOldFormat() {
        val old = OnlineQrPayload.PREFIX + """{"v":1,"game":"go","ble":{"serviceUuid":"abc"},"token":"x","lan":{"host":"1.2.3.4"}}"""
        val decoded = OnlineQrPayload.decode(old)!!
        assertEquals("go", decoded.game)
        assertEquals("abc", decoded.ble?.serviceUuid)
    }

    @Test
    fun envelope_omitsNulls() {
        val line = NetCodec.encode(NetCodec.move(3, "e5"))
        assertEquals(setOf("type", "seq", "move"), AppJson.parseToJsonElement(line).jsonObject.keys)
        val back = NetCodec.decode(line)!!
        assertEquals(3, back.seq)
        assertEquals("e5", back.move)
    }
}
