package com.xjtu.toolbox.data

import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.agent.CardWidget
import com.xjtu.toolbox.agent.ScheduleWidget
import com.xjtu.toolbox.agent.StoredConversation
import com.xjtu.toolbox.agent.storedToWidget
import com.xjtu.toolbox.agent.toStored
import com.xjtu.toolbox.attendance.AttendanceRecordStore
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.card.CampusCardSnapshot
import com.xjtu.toolbox.jwapp.ScoreSource
import com.xjtu.toolbox.jwapp.TermScore
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户手机上存的都是旧版（Gson）写出的 JSON：字段齐全、不写 null、枚举按名字。
 * 这里的样本照那个格式手写，确认换成 kotlinx 后原样读得回来。
 */
class LegacyJsonCompatTest {

    @Test
    fun accounts_badEntryOnlyDropsItself() {
        val raw = """[
            {"accountId":"2210000001","password":"p","accountType":"POSTGRADUATE","nickname":"张三","rsaKeyTime":1,"lastUsedAt":2},
            {"password":"no-id"},
            {"accountId":"2210000002","password":"q","accountType":"SOMETHING_NEW"},
            {"accountId":{"broken":true}}
        ]"""
        val accounts = AccountStore.decodeAccounts(raw)
        assertEquals(listOf("2210000001", "2210000002"), accounts.map { it.accountId })
        assertEquals(AccountType.POSTGRADUATE, accounts[0].accountType)
        assertEquals("张三", accounts[0].nickname)
        // 认不出的枚举值落回默认
        assertEquals(AccountType.UNDERGRADUATE, accounts[1].accountType)
    }

    @Test
    fun courses_oldCacheWithoutMinuteFields() {
        val raw = """[{"courseName":"高数","teacher":"李","location":"主楼","weekBits":"0110","dayOfWeek":2,
            "startSection":1,"endSection":2,"courseCode":"MATH1","courseType":"必修"}]"""
        val c = AppJson.decodeFromString<List<CourseItem>>(raw).single()
        assertEquals(-1, c.startMinuteOfDay)
        assertTrue(c.isInWeek(2))
    }

    @Test
    fun courses_writtenFormatKeepsDefaults() {
        // 装回旧版本时 Gson 读新写的缓存：默认值也要写出来，否则 -1 会被读成 0
        val json = AppJson.encodeToString(CourseItem(courseName = "x")).let { AppJson.parseToJsonElement(it).jsonObject }
        assertEquals("-1", json["startMinuteOfDay"].toString())
    }

    @Test
    fun conversation_withWidgets() {
        val raw = """{"messages":[
            {"role":"user","content":"今天有课吗","nav":[],"widgets":[]},
            {"role":"assistant","content":"有两节","nav":[["课表","schedule"]],
             "widgets":[{"type":"ScheduleWidget","json":"{\"title\":\"今天\",\"courses\":[{\"courseName\":\"高数\",\"weekBits\":\"1\",\"dayOfWeek\":1,\"startSection\":1,\"endSection\":2}]}"},
                        {"type":"GoneWidget","json":"{}"}]}
        ],"llmHistory":"[]","contextExhausted":false}"""
        val convo = AppJson.decodeFromString<StoredConversation>(raw)
        assertEquals(2, convo.messages.size)
        val widgets = convo.messages[1].widgets.orEmpty().mapNotNull(::storedToWidget)
        assertEquals("高数", (widgets.single() as ScheduleWidget).courses.single().courseName)
        assertEquals("ScheduleWidget", widgets.single().toStored().type)
    }

    @Test
    fun cardWidget_withoutInfoIsDropped() {
        assertNull(storedToWidget(com.xjtu.toolbox.agent.StoredWidget("CardWidget", "{}")))
        val ok = storedToWidget(com.xjtu.toolbox.agent.StoredWidget("CardWidget", """{"info":{"name":"张三","balance":12.5}}"""))
        assertEquals(12.5, (ok as CardWidget).info.balance, 0.0)
    }

    @Test
    fun attendanceShard_enumByName() {
        val raw = """{"termCode":"2025-2026-1","records":[{"sbh":"1","courseName":"物理","status":"LATE","week":3}],
            "fetchedAt":1,"fullScanAt":2}"""
        val shard = AppJson.decodeFromString<AttendanceRecordStore.Shard>(raw)
        assertEquals(WaterType.LATE, shard.records.single().status)
    }

    @Test
    fun cardSnapshot_missingCardInfoIsNoCache() {
        assertTrue(runCatching { AppJson.decodeFromString<CampusCardSnapshot>("""{"transactions":[]}""") }.isFailure)
        val snap = AppJson.decodeFromString<CampusCardSnapshot>(
            """{"cardInfo":{"account":"1","name":"a","balance":3.0},"transactions":[{"time":"2025-01-01 08:00","merchant":"","amount":-5.0,"description":"珍念水饺-电子账户消费"}],"rangeStart":"2025-01-01","rangeEnd":"2025-02-01","savedAt":9}"""
        )
        assertEquals("珍念水饺", snap.transactions.single().displayMerchant)
    }

    @Test
    fun grades_sourceEnum() {
        val raw = """[{"termCode":"2024-2025-1","termName":"秋","scoreList":[{"id":"1","courseName":"高数","score":"90","scoreValue":90.0,"passFlag":true,"coursePoint":4.0,"examType":"考试","examProp":"初修","replaceFlag":false,"source":"REPORT"}]}]"""
        val term = AppJson.decodeFromString<List<TermScore>>(raw).single()
        assertEquals(ScoreSource.REPORT, term.scoreList.single().source)
        assertNull(term.scoreList.single().gpa)
    }
}
