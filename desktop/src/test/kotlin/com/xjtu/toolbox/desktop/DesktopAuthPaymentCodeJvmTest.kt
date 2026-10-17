package com.xjtu.toolbox.desktop

import com.google.zxing.common.BitArray
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Reader
import com.google.zxing.qrcode.decoder.Decoder
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.pay.PaymentCodeApi
import com.xjtu.toolbox.pay.PaymentSelectionStore
import com.xjtu.toolbox.platform.CodeMatrix
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.code128Modules
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 付款码（`AppRoute.PaymentCode`）在桌面端的装配验收。 */
class DesktopAuthPaymentCodeJvmTest {

    private lateinit var fake: FakeCampusProxy
    private lateinit var dataRoot: File

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRoot = Files.createTempDirectory("xjtu-desktop-pay-test").toFile()
        dataRootOverride = dataRoot
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
            wipeSecureStore("${base}_default")
            wipeSecureStore("$base$suffix")
        }
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        wipeSecureStore("payment_code")
        for (name in listOf(
            "cookies_normal_default", "cookies_webvpn_default",
            "cookies_normal$suffix", "cookies_webvpn$suffix",
        )) {
            PersistentCookieJar(name).clear()
        }
        AccountContext.activeAccountId = null

        FakeCampusProxy.installFakeUpstreams()
        fake.start()
        try {
            block()
        } finally {
            fake.close()
            dataRootOverride = null
            AccountContext.activeAccountId = null
        }
    }

    /** 只准备一份落盘根目录（不跑假上游）—— 落盘那条用例用它。 */
    private fun withTempDataRoot(block: () -> Unit) {
        dataRoot = Files.createTempDirectory("xjtu-desktop-pay-test").toFile()
        dataRootOverride = dataRoot
        wipeSecureStore("payment_code")
        try {
            block()
        } finally {
            dataRootOverride = null
        }
    }
    private fun newAuth(): DesktopAuth {
        val store = JvmCredentialStore()
        store.rsaPublicKey = TestRsaKey.publicKeyBase64
        return DesktopAuth(store)
    }

    private fun login(auth: DesktopAuth): Boolean {
        auth.username = LibraryFakeUpstream.USERNAME
        auth.password = LibraryFakeUpstream.PASSWORD
        return runBlocking { auth.login() }
    }

    /** 登录 + 把校园卡会话建起来（桌面那条路由进门时由 `DesktopSiteGate` 做同一件事）。 */
    private fun loggedInCampusCard(): PaymentCodeApi {
        val auth = newAuth()
        assertTrue(login(auth), "用假上游的账号密码应当登得上：${auth.loginState}")
        runBlocking { auth.ensureSession(DesktopAuth.CAMPUS_CARD_SITE_KEY) }
        val site = assertNotNull(
            auth.sessionManager.getSiteOrNull(DesktopAuth.CAMPUS_CARD_SITE_KEY),
            "校园卡会话应当真的建起来",
        )
        assertTrue(site.hasLogin, "校园卡会话应当真的登进去")
        return PaymentCodeApi(site)
    }

    @Test
    fun `真登录之后：桌面自己能取付款码与加餐券，码阵能解回同一串数字`() = withFakeCampus {
        val api = loggedInCampusCard()

        // 屏进门第一枪就是这个（`:app` 那边同一条）：没登录时它抛，屏显示「认证失败」
        api.authenticate()

        val code = runBlocking { api.getBarCode() }
        assertEquals(CampusCardFakeUpstream.BAR_CODE, code, "取回来的就是夹具那串编出来的码")
        assertTrue(fake.campusCard.barCodeCalls.get() >= 1, "付款码那一枪真打到了 ncard")

        val vouchers = runBlocking { api.getVouchers() }
        assertEquals(2, vouchers.size, "夹具摆了两张券")
        assertEquals(CampusCardFakeUpstream.VOUCHER_CANTEEN_ID, vouchers[0].showCardId)
        assertEquals(CampusCardFakeUpstream.VOUCHER_CANTEEN_FEN, vouchers[0].amountFen)
        assertEquals(CampusCardFakeUpstream.VOUCHER_MILK_FEN, vouchers[1].amountFen)
        assertEquals("2026-12-31", vouchers[0].endDate)

        // ── 真出码：编出来的模块阵列要能解回同一串数字 ──
        val qr = assertNotNull(qrCodeModules(code), "桌面这一端应当真能编出二维码（jvm actual = zxing）")
        assertEquals(code, decodeQrModules(qr), "解回来必须是同一串数字（掩码/纠错/格式信息都对）")

        val bars = assertNotNull(code128Modules(code), "条形码同样应当编得出来")
        assertEquals(1, bars.height, "条纹阵列只有一行（高度交给渲染方拉伸）")
        assertEquals(code, decodeCode128Row(bars), "条形码同样要能读回同一串数字")

        // 勾选同步（屏上勾一张券就走这一枪）
        runBlocking { api.updateVoucherStatus(listOf(CampusCardFakeUpstream.VOUCHER_CANTEEN_ID)) }
        assertEquals(1, fake.campusCard.voucherUpdateCalls.get(), "同步那一枪真打到了 ncard")
        assertTrue(
            fake.campusCard.lastVoucherUpdateBody.orEmpty().contains(CampusCardFakeUpstream.VOUCHER_CANTEEN_ID),
            "请求体里就是勾中的那个 id：${fake.campusCard.lastVoucherUpdateBody}",
        )
    }

    @Test
    fun `加餐券勾选真落盘：磁盘上那份 Properties 里就是那些键值，重开还在`() = withTempDataRoot {
        val file = File(dataRoot, "payment_code.properties")
        val store = PaymentSelectionStore(secureKeyValueStore("payment_code"))
        assertEquals(emptySet(), store.selectedIds(), "还没勾过 ⇒ 空表")
        assertTrue(!store.isConfigured(), "还没勾过 ⇒ 不算「配过」")

        store.save(setOf(CampusCardFakeUpstream.VOUCHER_MILK_ID, CampusCardFakeUpstream.VOUCHER_CANTEEN_ID))

        assertTrue(file.exists(), "落盘文件应当真的建出来：${file.absolutePath}")
        val props = Properties().apply { file.inputStream().use { load(it) } }
        assertEquals(
            "${CampusCardFakeUpstream.VOUCHER_MILK_ID},${CampusCardFakeUpstream.VOUCHER_CANTEEN_ID}",
            props.getProperty("selected_voucher_ids"),
        )
        assertEquals("true", props.getProperty("voucher_selection_configured"))

        val reopened = PaymentSelectionStore(secureKeyValueStore("payment_code"))
        assertEquals(
            setOf(CampusCardFakeUpstream.VOUCHER_MILK_ID, CampusCardFakeUpstream.VOUCHER_CANTEEN_ID),
            reopened.selectedIds(),
        )
        assertTrue(reopened.isConfigured(), "「用户动过这份勾选」也要落盘（没动过就不往上游同步）")
    }

    /** 用 zxing 的低层解码器把模块阵列解回文本。 */
    private fun decodeQrModules(matrix: CodeMatrix): String {
        val bits = BitMatrix(matrix.width, matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) bits.set(x, y)
            }
        }
        return Decoder().decode(bits).text
    }

    /** 同上，但走 Code128 那条路（一维码：只扫一行）。 */
    private fun decodeCode128Row(matrix: CodeMatrix): String {
        val row = BitArray(matrix.width)
        for (x in 0 until matrix.width) {
            if (matrix[x, 0]) row.set(x)
        }
        return Code128Reader().decodeRow(0, row, null).text
    }
}
