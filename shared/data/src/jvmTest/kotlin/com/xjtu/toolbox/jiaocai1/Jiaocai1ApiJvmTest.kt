package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.auth.withJiaocaiLogin
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.BOOK_AUTHOR
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.BOOK_CALL_NO
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.BOOK_CLASSIFY_PATH
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.BOOK_PAGES
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.BOOK_PUBLISH_DATE
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.BOOK_THEME_WORD
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.BOOK_TITLE
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.CHILD_CATEGORY_ID
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.CHILD_CATEGORY_NAME
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.MISS_KEYWORD
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.ROOT_CATEGORY_ID
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.ROOT_CATEGORY_NAME
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.SSNO
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 教材全文库取数（[Jiaocai1Api]）与页字节（[Jiaocai1PageLoader]）的字段级口径。 */
class Jiaocai1ApiJvmTest {

    @Test
    fun `登录形态：教材中心走 CAS 回跳，全文库那一半靠自己跟完跳转页`() = withJiaocaiLogin { site, fake ->
        assertTrue(site.hasLogin, "真登录应当成功")
        assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")
        assertTrue(fake.jiaocai1.loginEntryCalls.get() > 0, "应当摸过教材中心的登录入口")

        // 业务侧：第一次请求拿到的是跳转页，客户端跟完 chain 再重试
        val tree = runBlocking { Jiaocai1Api(site).classifyTree() }
        assertTrue(fake.jiaocai1.portalJumpCalls.get() > 0, "会话没建立时上游会给一张跳转页")
        assertEquals(1, fake.jiaocai1.portalLoginCalls.get(), "跟完一跳就该把会话建起来")
        assertEquals(1, tree.size, "跟完链之后这一次就应当拿到数据：$tree")
    }

    @Test
    fun `分类树：一级 + 子类按 pid 建树`() = withJiaocaiLogin { site, _ ->
        val roots = runBlocking { Jiaocai1Api(site).classifyTree() }
        val root = roots.single()
        assertEquals(ROOT_CATEGORY_ID, root.id, "`classifyid` 就是检索参数 cls")
        assertEquals(ROOT_CATEGORY_NAME, root.name)
        val child = root.children.single()
        assertEquals(CHILD_CATEGORY_ID, child.id)
        assertEquals(CHILD_CATEGORY_NAME, child.name)
        assertEquals(2, child.level)
    }

    @Test
    fun `检索：五个字段与分页口径，以及「查不到」那一档`() = withJiaocaiLogin { site, fake ->
        val api = Jiaocai1Api(site)
        val result = runBlocking {
            api.search(keyword = "电路", field = Jiaocai1SearchField.BOOK_NAME, cls = CHILD_CATEGORY_ID, page = 1)
        }
        val book = result.books.single()
        assertEquals(SSNO, book.ssno)
        assertEquals(BOOK_TITLE, book.title)
        assertEquals(BOOK_AUTHOR, book.author)
        assertEquals(BOOK_PUBLISH_DATE, book.publishDate)
        assertEquals(BOOK_THEME_WORD, book.themeWord)
        assertEquals(BOOK_CALL_NO, book.callNo)
        assertEquals(BOOK_CLASSIFY_PATH, book.classifyPath)
        assertTrue(book.coverUrl.endsWith("$SSNO.jpg"), "封面 URL 要取出来：${book.coverUrl}")
        // 页码三件套（`data-cpage` / `data-sum-page` / `data-totalrow`）
        assertEquals(31, result.totalRows)
        assertEquals(1, result.currentPage)
        assertEquals(2, result.totalPages)
        assertTrue(result.hasMore, "1 < 2 ⇒ 还有下一页")

        val body = fake.jiaocai1.lastSearchBody.orEmpty()
        assertTrue(body.contains("stype=1"), body)
        assertTrue(body.contains("cpage=1"), body)
        assertTrue(body.contains("searchField=bookName"), body)
        assertTrue(body.contains("cls=$CHILD_CATEGORY_ID"), body)

        // 第二页：另一本（页码字段跟着走，`hasMore` 转 false）
        val page2 = runBlocking { api.search(keyword = "电路", page = 2) }
        assertEquals(Jiaocai1FakeUpstream.SSNO_PAGE2, page2.books.single().ssno)
        assertEquals(2, page2.currentPage)
        assertTrue(!page2.hasMore, "2 == 2 ⇒ 没有下一页了")

        // 「查不到」那一档：空表、总数为 0
        val miss = runBlocking { api.search(keyword = MISS_KEYWORD) }
        assertTrue(miss.books.isEmpty(), "夹具认的那个关键词应当查不到：${miss.books}")
        assertEquals(0, miss.totalRows)
    }

    @Test
    fun `打开一本书：8 组页型展开成线性页表`() = withJiaocaiLogin { site, fake ->
        val handle = assertNotNull(runBlocking { Jiaocai1Api(site).openBook(SSNO) }, "应当拿到阅读句柄")
        assertEquals(SSNO, handle.ssno)
        assertEquals(BOOK_TITLE, handle.title)
        assertEquals(Jiaocai1FakeUpstream.JPG_PATH, handle.jpgPath)
        assertEquals(BOOK_PAGES, handle.bookPages)
        assertEquals(8, handle.pageRanges.size, "8 种页型各一组 [起, 止]")
        // 线性页表：2+1+1+1+3+24+1+1 = 34 页
        assertEquals(34, handle.pages.size)
        assertEquals("cov001", handle.pages.first().fileName)
        assertEquals("封面", handle.pages.first().label)
        assertEquals("第 1 页", handle.pages.first { it.typeIndex == Jiaocai1Paging.TYPE_CONTENT }.label)
        assertEquals(SSNO, fake.jiaocai1.lastReaderSsno)
    }

    @Test
    fun `页字节：先读磁盘、没有才下载，且只认图片魔数`() = withJiaocaiLogin { site, fake ->
        val cacheRoot = Files.createTempDirectory("jiaocai1-pages").toFile()
        val source = AppJiaocai1Source(site, cacheRoot)
        val handle = assertNotNull(runBlocking { source.openBook(SSNO) })
        val page = handle.pages.first()

        val first = assertNotNull(runBlocking { source.pageBytes(handle, page) }, "第一页应当取得到")
        assertTrue(
            first.size > 8 && first[0] == 0xFF.toByte() && first[1] == 0xD8.toByte(),
            "夹具给的是标准 JPEG（FF D8 开头）",
        )
        assertEquals(2, fake.jiaocai1.pageImageCalls.get(), "prime + 本页共两发")
        assertTrue(
            java.io.File(java.io.File(java.io.File(cacheRoot, "jiaocai1"), SSNO), "cov001.img").isFile,
            "第一页应当落在磁盘上",
        )

        // 第二发同样拿得到，但**不再走网络**（磁盘命中）
        val second = assertNotNull(runBlocking { source.pageBytes(handle, page) })
        assertEquals(first.size, second.size)
        assertEquals(2, fake.jiaocai1.pageImageCalls.get(), "第二发应当命中磁盘缓存")

        // 移出书架时那一发：整本目录删掉
        runBlocking { source.evictBook(SSNO) }
        assertTrue(!java.io.File(java.io.File(cacheRoot, "jiaocai1"), SSNO).exists(), "整本目录应当被删掉")
        source.closePages()
    }

    @Test
    fun `封面字节：同一个 URL 不重复取（取不到时屏那边记住失败，不在取数层重试）`() = withJiaocaiLogin { site, fake ->
        val cacheRoot = Files.createTempDirectory("jiaocai1-cover").toFile()
        val source = AppJiaocai1Source(site, cacheRoot)
        // 真机上的顺序：先检索（那发把跟完跳转页、建起会话）→ 屏再去取封面
        runBlocking { Jiaocai1Api(site).search(keyword = "电路") }
        val before = fake.jiaocai1.pageImageCalls.get()
        // 封面那一发也走 `fetchBytes`（判图片魔数）—— 夹具给的是程序生成的小 JPEG ⇒ 取得到
        val bytes = assertNotNull(runBlocking { source.coverBytes("${Jiaocai1FakeUpstream.BASE}/cover/$SSNO.jpg") })
        assertTrue(
            bytes.size > 8 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(),
            "封面应当是 JPEG（FF D8 开头）",
        )
        assertEquals(before, fake.jiaocai1.pageImageCalls.get(), "封面不走页图那条计数")
    }
}
