package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** 教材中心的浏览态（[JiaocaiViewModel]）在 JVM 上的口径。 */
class JiaocaiViewModelTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `检索：关键词非空才发那一发，成功后落 books 与 hasSearched`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val source = FakeJiaocaiSource()
        val vm = JiaocaiViewModel()
        vm.bind(source)

        vm.search()
        assertTrue(source.keywords.isEmpty(), "没绑关键词、没输入关键词 ⇒ 不发请求")

        vm.keyword = "高等数学"
        vm.search()

        assertEquals(listOf("高等数学"), source.keywords)
        assertEquals(listOf("示例教材：高等数学（上册）"), vm.books.map { it.title })
        assertTrue(vm.hasSearched, "搜过一发的标志（屏据此把空结果当成「未找到」）")
        assertTrue(!vm.loading)
        assertNull(vm.error)
    }

    @Test
    fun `检索：searchWith 先落关键词再发那一发`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val source = FakeJiaocaiSource()
        val vm = JiaocaiViewModel()
        vm.bind(source)

        vm.searchWith("大学物理")

        assertEquals("大学物理", vm.keyword)
        assertEquals(listOf("大学物理"), source.keywords)
    }

    @Test
    fun `检索：上游回空表 ⇒ books 空但 hasSearched 为真（不是「还没搜」）`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val source = FakeJiaocaiSource().apply { result = emptyList() }
        val vm = JiaocaiViewModel()
        vm.bind(source)

        vm.searchWith("没有这门课的教材示例")

        assertTrue(vm.books.isEmpty())
        assertTrue(vm.hasSearched)
        assertNull(vm.error)
    }

    @Test
    fun `会话失效：走 authExpired 那条流，不写成错误文案`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val source = FakeJiaocaiSource().apply { failWith = FakeSessionExpired() }
        val vm = JiaocaiViewModel()
        vm.bind(source)

        vm.searchWith("高等数学")

        assertTrue(vm.authExpired, "交给导航层重登")
        assertNull(vm.error)
        assertTrue(!vm.hasSearched)
    }

    @Test
    fun `绑定之前：search 什么都不做，也不抛`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = JiaocaiViewModel()

        vm.keyword = "高等数学"
        vm.search()

        assertTrue(vm.books.isEmpty())
        assertTrue(!vm.hasSearched)
    }
}

/** 会话失效的标记异常：与 `:data` 那边 `AuthExpiredException` 同一形态（见 [SessionExpiredFailure]）。 */
private class FakeSessionExpired : RuntimeException("登录态已失效"), SessionExpiredFailure

/** 记账用的假取数实现：把关键词记下来，并允许注入结果与失败。 */
private class FakeJiaocaiSource : JiaocaiSource {

    var result: List<JiaocaiBook> = listOf(
        JiaocaiBook(id = "general_1", title = "示例教材：高等数学（上册）"),
    )
    var failWith: Exception? = null

    val keywords = mutableListOf<String>()

    override suspend fun search(keyword: String): List<JiaocaiBook> {
        keywords += keyword
        failWith?.let { throw it }
        return result
    }

    override suspend fun fetchSsno(book: JiaocaiBook): String? = null
}
