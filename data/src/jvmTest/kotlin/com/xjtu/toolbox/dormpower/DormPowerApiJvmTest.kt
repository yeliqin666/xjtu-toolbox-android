package com.xjtu.toolbox.dormpower

import com.xjtu.toolbox.auth.withSsnLogin
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream.Companion.CID
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream.Companion.HOST
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream.Companion.KWH
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream.Companion.OAUTH_CODE
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream.Companion.PAY_PATH
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream.Companion.ROOM_ID
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream.Companion.ROOM_NAME
import com.xjtu.toolbox.platform.keyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 宿舍电费取数的**字段级口径**：`DormPowerApi` （以及它实现的 `:core` 端口 [DormPowerSource]）
 * 对着 `:testkit` 的 [DormPowerFakeUpstream] 逐行读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把宿舍电费的取数（`DormPowerApi` + `DormPowerParsers`）、站点（`SsnSession` +
 * `SsnLogin`）与本地 Store（`DormPowerStore`，模型与落盘进 `:core`）从 `:app` 搬进共享内核
 * （桌面端第 17 条真数据路由）。搬之前先把**搬之前**的口径钉住 —— 这些断言全部来自**夹具原文**
 * 与**搬迁前的代码语义**：`cid` 落在 `localToken["cid"]`、缴费页入口地址是 OAuth 回调里的
 * 「https 化」那一跳、`rooms`/`kwh` 的字段与 null 语义、查完写回本机（`DormPowerStore.refresh`）。
 */
class DormPowerApiJvmTest {

    /** jvm 的 `keyValueStore` 是进程内单例 ⇒ 上一轮测试的 `dorm_power_*` 会留在内存里，先清干净。 */
    private fun clearStore() = keyValueStore("dorm_power_default").clear()

    @Test
    fun `登录：开放平台 + CAS 那条链走完，会话里落着 cid`() {
        withSsnLogin { site, fake ->
            assertEquals(CID, site.localToken["cid"], "cid 来自缴费页 HTML（SsnSession.onLoginSuccess 那两行）")
            assertTrue(site.hasLogin, "真登录应当成功")

            // 开放平台那一跳与缴费页落点真的被打到（不是靠别处 cookie 直通）
            assertTrue(fake.dormPower.oauthLandings.get() > 0, "org 开放平台那一跳应当被访问")
            assertTrue(fake.dormPower.payLandings.get() > 0, "缴费页应当被访问")
            assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")
        }
    }

    @Test
    fun `缴费页入口：freshPayUrl 每次重走 OAuth、拦下带 code 的那一跳，并记下进过这一页`() {
        withSsnLogin { site, fake ->
            clearStore()
            val api = DormPowerApi(site)
            val url = runBlocking { api.freshPayUrl() }

            assertEquals("https://$HOST$PAY_PATH?code=$OAUTH_CODE", url, "回调地址 http→https，code 原样保留")
            assertTrue(DormPowerStore.isUsed(), "拿到地址之后要记「用户进过宿舍电费页」（搬迁前写在屏上，时机未改）")
        }
    }

    @Test
    fun `取数：宿舍列表按 roomId-roomName 读，电量按房间带 cid 与 roomId 查`() {
        withSsnLogin { site, fake ->
            val api = DormPowerApi(site)
            val rooms = runBlocking { api.rooms() }
            assertEquals(
                listOf(DormRoom(ROOM_ID, ROOM_NAME)),
                rooms,
                "字段口径（roomId / roomName）与搬迁前一致",
            )
            assertEquals(CID, fake.dormPower.lastListCid, "列表请求要带会话里的 cid")

            val kwh = runBlocking { api.kwh(ROOM_ID) }
            assertEquals(KWH, kwh ?: 0.0, 1e-9, "电量按 roomId 查，数值与夹具一致")
            assertEquals(ROOM_ID, fake.dormPower.lastKwhRoomId)
        }
    }

    @Test
    fun `refresh：查一遍所有绑定宿舍写回本机，读得出来`() {
        withSsnLogin { site, fake ->
            clearStore()
            val api = DormPowerApi(site)
            val readings = runBlocking { DormPowerStore.refresh(api) }

            assertEquals(1, readings.size, "夹具里只有一间宿舍")
            val reading = readings.single()
            assertEquals(DormRoom(ROOM_ID, ROOM_NAME), reading.room)
            assertEquals(KWH, reading.kwh ?: 0.0, 1e-9)
            assertTrue(readings == DormPowerStore.readings(), "写回本机后原样读得出来")

            assertTrue(fake.dormPower.roomCalls.get() >= 1, "列表应当真被查过")
            assertTrue(fake.dormPower.kwhCalls.get() >= 1, "电量应当真被查过")
        }
    }
}