package com.xjtu.toolbox.coupon

import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 加餐券**编排**（[CouponViewModel]）在 JVM 上的口径 —— 搬屏之前先钉住，搬完一个字不改。
 *
 * ## 它钉的是什么
 *
 * 屏搬进 `:core` 时，取数从 `CouponApi(site)` 换成了 [CouponSource] 端口（IO 由实现方自己包），
 * 首页摘要从 `HomeStats.push(appContext, …)` 换成了 [CouponViewModel] 的 `onSummary` 槽位
 * —— **分页 / 换分类取消上一条 / 翻页失败只提示不丢列表 / 领取先查详情再激活**的编排一行未改。
 * 这里用一个记账用的假 [CouponSource] 把这些行为钉住。
 *
 * ## 为什么在 jvmTest
 *
 * 与 `TranscriptViewModelTest` 同一条理由：`viewModelScope` 要一个 Main 调度器，
 * `Dispatchers.setMain` 只在 JVM 侧稳定。
 */
class CouponViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `打开即加载「可使用」，摘要只回写两档`() {
        val source = FakeCouponSource()
        val summary = mutableListOf<Pair<String, String?>>()
        val vm = CouponViewModel(source) { value, detail -> summary += value to detail }

        assertEquals(CouponFilter.USABLE, vm.filter)
        assertEquals(2, vm.records.size)
        assertEquals(2, vm.total)
        assertTrue(!vm.isLoading)
        assertNull(vm.errorMessage)

        // 打开那次是 USABLE ⇒ 只回写「待使用」那一档；「待领取」还没查过不算数
        assertEquals(listOf("2 个待使用" to (null as String?)), summary)
        assertEquals(listOf("queryCoupons(USABLE, 1)"), source.calls)
    }

    @Test
    fun `换分类：清列表重拉，取消还没回来的上一条`() {
        val source = FakeCouponSource()
        val vm = CouponViewModel(source)
        vm.selectFilter(CouponFilter.AVAILABLE)

        assertEquals(CouponFilter.AVAILABLE, vm.filter)
        assertEquals(1, vm.records.size)
        assertEquals(1, vm.total)
        // 打开（USABLE,1）之后，换分类取消了它并发了（AVAILABLE,1）
        assertEquals(listOf("queryCoupons(USABLE, 1)", "queryCoupons(AVAILABLE, 1)"), source.calls)
        // 同一个分类再点一次是 no-op
        vm.selectFilter(CouponFilter.AVAILABLE)
        assertEquals(2, source.calls.size)
    }

    @Test
    fun `触底翻页：追加到列表尾部，翻页失败只提示、保住已有列表`() {
        val source = FakeCouponSource()
        val vm = CouponViewModel(source)

        vm.loadMore()
        assertEquals(4, vm.records.size, "第二页追加到已有两条后面")
        assertEquals(listOf("queryCoupons(USABLE, 1)", "queryCoupons(USABLE, 2)"), source.calls)

        // 翻页失败：loadMoreError 有值、列表还在
        source.failNext = IllegalStateException("boom")
        vm.loadMore()
        assertNotNull(vm.loadMoreError)
        assertEquals(4, vm.records.size, "翻页失败不丢列表")
        assertNull(vm.errorMessage, "翻页失败不占整页错误")
    }

    @Test
    fun `初始加载失败：错误文案 + 可重试`() {
        val source = FakeCouponSource().apply { failNext = IllegalStateException("boom") }
        val vm = CouponViewModel(source)

        assertNotNull(vm.errorMessage)
        assertTrue(vm.records.isEmpty())

        source.failNext = null
        vm.load()
        assertNull(vm.errorMessage)
        assertEquals(2, vm.records.size)
    }

    @Test
    fun `领取：先查详情再激活，激活成功说详情标题，随后静默刷新`() {
        val source = FakeCouponSource()
        val vm = CouponViewModel(source)
        val coupon = vm.records.first()

        vm.receive(coupon)

        // 打开那次（init）已经打了一枪；领取 = 详情 + 激活 + 静默刷新
        assertEquals(
            listOf("queryCoupons(USABLE, 1)", "activate(CARD-假-0001)", "queryCoupons(USABLE, 1)"),
            source.calls,
        )
        assertEquals("劳动节放假加餐（假）", vm.statusMessage, "详情标题优先，兜底才是『已领取 X』")
        assertTrue(coupon.showCardId !in vm.receivingIds, "领完要退场")
    }

    @Test
    fun `会话失效走 authExpired 那条流，不写成错误文案`() {
        val source = FakeCouponSource().apply { failNext = FakeSessionExpired() }
        val vm = CouponViewModel(source)

        assertNull(vm.errorMessage, "会话失效不是「加载失败」，交给导航层去重登")
        assertTrue(runBlocking { withTimeout0(vm) }, "authExpired 应当发出一枪")
    }

    /** 收一枪 `authExpired`（CONFLATED 通道；用单测调度器时它已经发出来了）。 */
    private suspend fun withTimeout0(vm: CouponViewModel): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(1_000L) { vm.authExpired.first() } != null
}

/**
 * 「站点登录态已失效」的假异常：只实现 `:core` 的标记接口（内容是 `:app` 的
 * `AuthExpiredException` 那一族的形状，而 `:core` 看不见它们）。
 */
private class FakeSessionExpired : RuntimeException("登录态已失效"), SessionExpiredFailure

/**
 * 记账用的假取数实现：把**调用顺序**记下来，并允许在某一步注入失败。
 *
 * 不碰网络、不碰 Android —— 它存在的意义只有一个：让「搬屏时编排一行没改」这句话可验证。
 */
private class FakeCouponSource : CouponSource {

    val calls: MutableList<String> = mutableListOf()
    var failNext: Throwable? = null

    override suspend fun queryCoupons(
        filter: CouponFilter,
        page: Int,
        pageSize: Int,
    ): CouponPage {
        calls += "queryCoupons(${filter.name}, $page)"
        failNext?.let { throw it }
        val records = when (filter) {
            CouponFilter.USABLE ->
                listOf(record("CARD-假-0001", "劳动节加餐券（假）"), record("CARD-假-0003", "毕业季加餐券（假）"))
            CouponFilter.AVAILABLE ->
                listOf(record("CARD-假-0002", "校庆加餐券（假）"))
            else -> emptyList()
        }
        return CouponPage(records, records.size)
    }

    override suspend fun getCouponDetail(showCardId: String): CouponDetail =
        CouponDetail(
            showCardId = showCardId,
            voucherName = "劳动节加餐券（假）",
            title = "劳动节放假加餐（假）",
            description = "凭券在指定食堂兑换（假）",
            amountFen = 500L,
            leftAmountFen = 500L,
            startDate = "2026-05-01",
            endDate = "2026-05-31 23:59",
            batchId = "BATCH-1",
            imageUrl = "",
            closedPacketImageUrl = "",
            openPacketImageUrl = "",
        )

    override suspend fun activateCoupon(showCardId: String) {
        calls += "activate($showCardId)"
    }

    override suspend fun loadImage(url: String): ByteArray? = null

    private fun record(cardId: String, name: String) = CouponRecord(
        sendId = "S-$cardId",
        showCardId = cardId,
        voucherName = name,
        typeName = "加餐券",
        amountFen = 500L,
        leftAmountFen = 500L,
        leftCount = 1,
        startDate = "2026-05-01",
        endDate = "2026-05-31 23:59",
        imageUrl = "",
    )
}