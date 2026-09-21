package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineQrPayloadTest {

    @Test
    fun `编解码往返`() {
        val payload = OnlineQrPayload(
            game = GameKind.GOMOKU.wireId,
            lan = OnlineQrPayload.LanInfo("192.168.1.5", 23456),
            ble = OnlineQrPayload.BleInfo("6e6f7401-0000-1000-8000-00805f9b34fb"),
            token = "ABCD1234",
        )
        val text = OnlineQrPayload.encode(payload)
        assertTrue(text.startsWith(OnlineQrPayload.PREFIX))
        val decoded = OnlineQrPayload.decode(text)
        assertEquals(payload, decoded)
    }

    @Test
    fun `不带前缀的内容——比如扫码登录的二维码——直接判定不是本游戏的码`() {
        assertNull(OnlineQrPayload.decode("https://cas.xjtu.edu.cn/qrLogin?uuid=xxx"))
        assertNull(OnlineQrPayload.decode("随便一段文字"))
    }

    @Test
    fun `前缀对但 JSON 坏了也返回 null，不抛异常`() {
        assertNull(OnlineQrPayload.decode(OnlineQrPayload.PREFIX + "{不是合法json"))
    }

    @Test
    fun `只有局域网信息、没有蓝牙信息也是合法的码`() {
        val payload = OnlineQrPayload(
            game = GameKind.GO.wireId,
            lan = OnlineQrPayload.LanInfo("10.0.0.1", 1234),
            ble = null,
            token = "TOKEN123",
        )
        val decoded = OnlineQrPayload.decode(OnlineQrPayload.encode(payload))
        assertEquals(payload, decoded)
    }
}
