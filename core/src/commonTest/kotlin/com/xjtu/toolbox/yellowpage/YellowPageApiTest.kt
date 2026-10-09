package com.xjtu.toolbox.yellowpage

import com.xjtu.toolbox.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 黄页解析的跨端回归网。
 *
 * 这一轮的改动是「把 :app 的 okhttp 版搬进 :core 并换成 Ktor」——传输层换了，
 * 但**解析口径必须逐字不变**。所以这里直接钉住 [YellowPageApi.parseListBody] /
 * [YellowPageApi.parseUpdateTime]（它们被从网络路径里抽出来，正是为了让这件事可测），
 * 同一份断言在 :core:jvmTest 与 wasm 目标上都能编。
 */
class YellowPageApiTest {

    // 纯解析用，不需要真的发请求；MockEngine 只是给 HttpClient 一个合法构造。
    private fun api() = YellowPageApi(HttpClient(MockEngine { error("本测试不应发请求") }))

    private fun parse(json: String) = AppJson.parseToJsonElement(json).jsonObject

    @Test
    fun listBody_filtersDisabled_andSortsBySortThenId() {
        val (categories, departments) = api().parseListBody(
            parse(
                """
                {"e":0,"d":{
                  "categories":[
                    {"id":2,"name":"B","status":1,"sort":2},
                    {"id":1,"name":"A","status":0,"sort":1},
                    {"id":3,"name":"C","status":1,"sort":1}
                  ],
                  "departments":[
                    {"id":10,"categoryId":1,"name":"X","phone":"029-82668888","sort":2,"status":1},
                    {"id":11,"categoryId":1,"name":"Y","phone":"029-82668889","sort":1,"status":1},
                    {"id":12,"categoryId":1,"name":"Z","phone":"029-82668890","sort":1,"status":0}
                  ]
                }}
                """.trimIndent()
            )
        )
        // status==0 的 A / Z 被丢掉；剩下的按 (sort, id) 升序
        assertEquals(listOf(3, 2), categories.map { it.id })
        assertEquals(listOf(11, 10), departments.map { it.id })
    }

    @Test
    fun listBody_missingData_throwsSameMessage() {
        val error = assertFailsWith<RuntimeException> { api().parseListBody(parse("""{"e":0}""")) }
        assertEquals("黄页接口缺少数据", error.message)
    }

    @Test
    fun listBody_phoneItemsAndDialNumber() {
        val (_, departments) = api().parseListBody(
            parse("""{"e":0,"d":{"categories":[],"departments":[{"id":1,"categoryId":1,"name":"N","phone":"029-82668888 / 82668889","sort":0,"status":1}]}}""")
        )
        val phone = departments.single().phone
        assertEquals(listOf("029-82668888", "82668889"), departments.single().phoneItems)
        assertEquals("82668888", departments.single().dialNumber("029-82668888"))
        assertEquals("82668889", departments.single().dialNumber("82668889"))
        assertEquals("", departments.single().dialNumber("见年级群"))
        assertEquals("029-82668888 / 82668889", phone)
    }

    @Test
    fun updateTime_isoToChineseDate() {
        val formatted = api().parseUpdateTime(parse("""{"e":0,"d":{"page_update_time":"2026-08-01T00:00:00"}}"""))
        assertEquals("2026年08月01日", formatted)
    }

    @Test
    fun updateTime_garbage_throws() {
        // getData 用 runCatching 把它变成 ""——与搬迁前 java.time 的行为一致
        assertFailsWith<Exception> {
            api().parseUpdateTime(parse("""{"e":0,"d":{"page_update_time":"不是时间"}}"""))
        }
    }
}
