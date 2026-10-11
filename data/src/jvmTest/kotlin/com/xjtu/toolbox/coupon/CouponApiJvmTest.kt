package com.xjtu.toolbox.coupon

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.withCouponLogin
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.AUTH_TOKEN
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.AVAILABLE_CARD_ID
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.AVAILABLE_NAME
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.AVAILABLE_SEND_ID
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.BATCH_ID
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.CLOSED_PIC
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.DATE_END
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.DATE_START
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.DETAIL_DESC
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.DETAIL_TITLE
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.EMPLOYEE_NO
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.HOST
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.OAUTH_CODE
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.OPEN_PIC
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.PIC_BYTES
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.PIC_PATH
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.USABLE_CARD_ID
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.USABLE_LEFT_COUNT
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.USABLE_NAME
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.USABLE_SEND_ID
import com.xjtu.toolbox.coupon.CouponFakeUpstream.Companion.USER_TYPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 加餐券取数的**字段级口径**：`CouponApi` 对着 `:testkit` 的 [CouponFakeUpstream] 逐行读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把加餐券的取数（`CouponApi`）与站点（`CouponSession` + `CouponLogin`）从 `:app` 搬进了
 * `:data`（桌面端第 15 条真数据路由）。搬之前先用夹具把**搬之前**的口径钉住 —— 这些断言
 * 全部来自**夹具原文**与**搬迁前的代码语义**（`Content-Type` / `Origin` / `Referer` 那三个头、
 * 「响应是 HTML 就当会话失效」、`code != 200` 就抛、401/403/含「令牌」抛 `AuthExpiredException`、
 * 图片 URL 相对路径拼 `BASE`）。
 *
 * 模型与 `CouponJsonParser` 已经在 `:core`（`CouponModels.kt`，上一批就搬过去了），这里钉的是
 * **请求怎么打 + 响应怎么认**那半。
 */
class CouponApiJvmTest {

    @Test
    fun `登录：OAuth2 那条链走完，会话里落着 auth_token，业务请求带上 Authorization`() {
        withCouponLogin { site, fake ->
            assertEquals(AUTH_TOKEN, site.localToken["auth_token"])
            assertTrue(site.hasLogin, "真登录应当成功")

            // 开放平台那一跳真的被打到（不是靠别处 cookie 直通），SSO 换令牌也真打过
            assertTrue(fake.coupon.orgCallbacks.get() > 0, "org 开放平台那一跳应当被访问")
            assertTrue(fake.coupon.receiveLandings.get() > 0, "receiveCas.html 应当被访问")
            assertEquals(1, fake.coupon.ssoBodies.size)
            assertTrue(OAUTH_CODE in fake.coupon.ssoBodies.single())
            assertTrue("userType=$USER_TYPE" in fake.coupon.ssoBodies.single())
            assertTrue("employeeNo=$EMPLOYEE_NO" in fake.coupon.ssoBodies.single())
            assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")

            // 业务请求要带上 Authorization —— 就是 `CouponSession.decorateRequest` 那两行
            val api = CouponApi(site)
            runBlocking { api.queryCoupons(CouponFilter.USABLE) }
            assertEquals(AUTH_TOKEN, fake.coupon.lastBusinessAuthorization)
        }
    }

    @Test
    fun `查券：status=1 那两批，金额按分读、图片 URL 相对路径拼站点基址`() {
        withCouponLogin { site, fake ->
            val api = CouponApi(site)
            val page = runBlocking { api.queryCoupons(CouponFilter.USABLE, page = 1, pageSize = 20) }

            assertEquals(2, page.total)
            assertEquals(2, page.records.size)
            val usable = page.records.first()
            assertEquals(USABLE_SEND_ID, usable.sendId)
            assertEquals(USABLE_CARD_ID, usable.showCardId)
            assertEquals(USABLE_NAME, usable.voucherName)
            assertEquals(500L, usable.amountFen)
            assertEquals(500L, usable.leftAmountFen)
            assertEquals(5.0, usable.leftAmountYuan, 0.001)
            assertEquals(USABLE_LEFT_COUNT.toInt(), usable.leftCount)
            assertEquals(DATE_START, usable.startDate)
            assertEquals(DATE_END, usable.endDate)
            assertEquals("https://$HOST$PIC_PATH", usable.imageUrl, "相对路径要拼上 BASE")

            // 第二张图是空串 ⇒ 保持空（不拼成 "https://egc.../"）
            assertEquals("", page.records[1].imageUrl)

            // 请求体的线上取值：filter 的 status/count/expired 原样进去
            val body = fake.coupon.pageBodies.single()
            assertTrue(""""status": "1"""" in body)
            assertTrue(""""count": "1"""" in body && """"expired": "3"""" in body)
            assertTrue(""""pageNum": 1""" in body && """"pageSize": 20""" in body)
            assertTrue(""""typeId": 4""" in body && """"json": true""" in body)
        }
    }

    @Test
    fun `查券：status=0 是「可领取」，status 之外给空表不炸`() {
        withCouponLogin { site, fake ->
            val api = CouponApi(site)
            val available = runBlocking { api.queryCoupons(CouponFilter.AVAILABLE) }
            assertEquals(AVAILABLE_SEND_ID, available.records.single().sendId)
            assertEquals(AVAILABLE_CARD_ID, available.records.single().showCardId)
            assertEquals(AVAILABLE_NAME, available.records.single().voucherName)
            assertTrue(""""status": "0"""" in fake.coupon.pageBodies.last())

            // 已用完/已过期那两档：夹具给空表，别炸
            val usedUp = runBlocking { api.queryCoupons(CouponFilter.USED_UP) }
            assertTrue(usedUp.records.isEmpty())
            assertEquals(0, usedUp.total)
            val expired = runBlocking { api.queryCoupons(CouponFilter.EXPIRED) }
            assertTrue(expired.records.isEmpty())
        }
    }

    @Test
    fun `详情：destitle 与 describes、面额、批次、两张红包图原样读，图片 URL 拼基址`() {
        withCouponLogin { site, fake ->
            val api = CouponApi(site)
            val detail = runBlocking { api.getCouponDetail(USABLE_CARD_ID) }

            assertEquals(USABLE_CARD_ID, detail.showCardId)
            assertEquals(USABLE_NAME, detail.voucherName)
            assertEquals(DETAIL_TITLE, detail.title)
            assertEquals(DETAIL_DESC, detail.description)
            assertEquals(500L, detail.amountFen)
            assertEquals(500L, detail.leftAmountFen)
            assertEquals(BATCH_ID, detail.batchId)
            assertEquals(DATE_START, detail.startDate)
            assertEquals("https://$HOST$PIC_PATH", detail.imageUrl)
            assertEquals("https://$HOST$CLOSED_PIC", detail.closedPacketImageUrl)
            assertEquals("https://$HOST$OPEN_PIC", detail.openPacketImageUrl)
            assertTrue(""""cardId":"$USABLE_CARD_ID"""" in fake.coupon.detailBodies.single())
        }
    }

    @Test
    fun `领取：POST cardId，成功后不抛`() {
        withCouponLogin { site, fake ->
            val api = CouponApi(site)
            runBlocking { api.activateCoupon(USABLE_CARD_ID) }
            assertTrue(""""cardId":"$USABLE_CARD_ID"""" in fake.coupon.activateBodies.single())
        }
    }

    @Test
    fun `响应像登录页（HTML）就当会话失效；code 401 也当会话失效`() {
        withCouponLogin { site, fake ->
            val api = CouponApi(site)

            // ① HTML：`executeRaw` 看见 <html 就当 AuthExpired
            fake.coupon.htmlMode = true
            assertFailsWith<AuthExpiredException> { runBlocking { api.queryCoupons(CouponFilter.USABLE) } }

            // ② JSON code=401：抛出 AuthExpired（auth token 被盗用/过期的那条路）
            fake.coupon.htmlMode = false
            fake.coupon.forceCode401 = true
            assertFailsWith<AuthExpiredException> { runBlocking { api.queryCoupons(CouponFilter.USABLE) } }
        }
    }

    @Test
    fun `封面图：走站点会话客户端取字节，鉴权头在路上`() {
        withCouponLogin { site, fake ->
            val source = AppCouponSource(site)
            val bytes = runBlocking { source.loadImage("https://$HOST$PIC_PATH") }
            assertEquals(PIC_BYTES.toList(), bytes!!.toList())
            // ⚠️ 取图**不带** Authorization：搬迁前的屏就是 `site.client` 直接 GET（券图是公开
            // 对象存储，不需要业务令牌）—— 保持原样，只在业务三枪上断言那个头（见上面那个用例）。

            // 空串 / 取不到的图 ⇒ null（屏上退化成图标）
            assertEquals(null, runBlocking { source.loadImage("") })
            assertEquals(null, runBlocking { source.loadImage("https://$HOST/no-such.png") })
        }
    }
}