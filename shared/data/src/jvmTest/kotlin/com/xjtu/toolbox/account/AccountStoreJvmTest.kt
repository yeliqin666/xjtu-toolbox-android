package com.xjtu.toolbox.account

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 多账号那张表的落盘口径（`secureKeyValueStore`）。 */
class AccountStoreJvmTest {

    /** 按老存储名 `xjtu_accounts` 直接开一份，确认老数据仍读得到。 */
    private lateinit var raw: KeyValueStore
    private lateinit var root: File
    private var previousRoot: File? = null

    @BeforeTest
    fun setUp() {
        previousRoot = dataRootOverride
        root = Files.createTempDirectory("xjtu-account-store-test").toFile()
        dataRootOverride = root
        raw = secureKeyValueStore("xjtu_accounts")
    }

    @AfterTest
    fun tearDown() {
        try {
            wipeSecureStore("xjtu_accounts")
        } finally {
            dataRootOverride = previousRoot
            root.deleteRecursively()
        }
    }

    @Test
    fun `解码：坏条目只丢自己，缺学号的丢掉，认不出的枚举落回默认`() {
        val rawJson = """[
            {"accountId":"2210000001","password":"p","accountType":"POSTGRADUATE","nickname":"张三","rsaKeyTime":1,"lastUsedAt":2},
            {"password":"no-id"},
            {"accountId":"2210000002","password":"q","accountType":"SOMETHING_NEW"},
            {"accountId":{"broken":true}}
        ]"""
        val accounts = AccountStore.decodeAccounts(rawJson)
        assertEquals(listOf("2210000001", "2210000002"), accounts.map { it.accountId })
        assertEquals(AccountType.POSTGRADUATE, accounts[0].accountType)
        assertEquals("张三", accounts[0].nickname)
        // 认不出的枚举值落回默认
        assertEquals(AccountType.UNDERGRADUATE, accounts[1].accountType)
    }

    @Test
    fun `整表坏掉时不抛，回空表`() {
        assertEquals(emptyList(), AccountStore.decodeAccounts("not json at all"))
        assertEquals(emptyList(), AccountStore.decodeAccounts("{}"))
    }

    @Test
    fun `upsert 落盘并可选设为激活，update 只改指定字段`() {
        val store = AccountStore()
        assertTrue(store.isEmpty())
        store.upsert(Account(accountId = "2210000001", password = "p"), setActive = true)
        store.upsert(Account(accountId = "2210000002", password = "q"))

        assertEquals(listOf("2210000001", "2210000002"), store.list().map { it.accountId })
        assertEquals("2210000001", store.activeAccountId())
        assertEquals("p", store.activeAccount()?.password)

        // 同一 accountId 再 upsert 是替换而不是追加
        store.upsert(Account(accountId = "2210000001", password = "p2", nickname = "李四"))
        assertEquals(2, store.list().size)
        assertEquals("李四", store.get("2210000001")?.nickname)

        val updated = store.update("2210000002") { it.copy(lastUsedAt = 7L) }
        assertEquals(7L, updated?.lastUsedAt)
        assertEquals("q", store.get("2210000002")?.password)
        assertNull(store.update("2210999999") { it })
    }

    @Test
    fun `删掉当前激活账号时激活指针一并清掉`() {
        val store = AccountStore()
        store.upsert(Account(accountId = "2210000001", password = "p"), setActive = true)
        store.upsert(Account(accountId = "2210000002", password = "q"))

        store.remove("2210000001")
        assertEquals(listOf("2210000002"), store.list().map { it.accountId })
        assertNull(store.activeAccountId())
        assertNull(store.activeAccount())

        // 删的不是激活账号时，指针不动
        store.setActive("2210000002")
        store.remove("2210999999")
        assertEquals("2210000002", store.activeAccountId())
    }

    @Test
    fun `写入的就是迁移闸门与键名那一份，换一份实例读得到同一张表`() {
        val store = AccountStore()
        store.upsert(Account(accountId = "2210000001", password = "p"), setActive = true)
        assertFalse(store.migrationDone)

        assertEquals("2210000001", raw.getString("active_account"))
        assertTrue(raw.getString("accounts")!!.contains("2210000001"))
        assertEquals(false, raw.getBoolean("migration_done", false))

        store.migrationDone = true
        assertTrue(AccountStore().migrationDone)
        // 新实例（同一份文件）看到同一张表
        assertEquals(listOf("2210000001"), AccountStore().list().map { it.accountId })

        store.clearActive()
        assertNull(AccountStore().activeAccountId())
    }

    @Test
    fun concurrentUpdatesFromDifferentInstancesPreserveBothChanges() {
        val first = AccountStore()
        val second = AccountStore()
        first.upsert(Account(accountId = "A"))
        first.upsert(Account(accountId = "B"))
        val insideUpdate = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit {
                first.update("A") {
                    insideUpdate.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    it.copy(nickname = "first")
                }
            }
            assertTrue(insideUpdate.await(5, TimeUnit.SECONDS))
            val b = pool.submit {
                secondStarted.countDown()
                second.update("B") { it.copy(nickname = "second") }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            try {
                b.get(300, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // B 等待 A 的整个读改写事务结束。
            } finally {
                resume.countDown()
            }
            a.get(5, TimeUnit.SECONDS)
            b.get(5, TimeUnit.SECONDS)
            assertEquals("first", first.get("A")?.nickname)
            assertEquals("second", first.get("B")?.nickname)
        } finally {
            resume.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
