package com.xjtu.toolbox.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MarketIndexParserTest {

    private val sample = """
        {
          "schema": 1,
          "updatedAt": "2026-11-26T21:00:00+08:00",
          "tags": ["示例"],
          "plugins": [
            {
              "id": "top.dzhz.plugin.template", "name": "插件模板", "summary": "s", "type": "dex",
              "version": "1.0.1", "versionCode": 13, "minAppVersionCode": 101, "author": "Hz6826",
              "download": "https://example.com/a.zip", "mirrors": ["http://insecure/a.zip", "https://m/a.zip"],
              "sha256": "sha256:EF9513F9906C539486C55C4DDEC069B33ED0EC3070E9D6EEED2D0BC7C1664311",
              "size": 792344, "featured": true, "tags": ["示例"], "extra": {"ignored": true}
            },
            { "id": "bad.type", "name": "x", "type": "apk", "download": "https://e/x.zip" },
            { "id": "no.https", "name": "x", "type": "web", "download": "http://e/x.zip" },
            { "id": "top.dzhz.plugin.template", "name": "重复", "type": "web", "download": "https://e/y.zip" }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesValidEntriesAndSkipsBadOnes() {
        val index = MarketIndexParser.parse(sample)
        assertEquals(1, index.schema)
        assertEquals(listOf("示例"), index.tags)
        val entry = index.entries.single()
        assertEquals("插件模板", entry.name)
        assertFalse(entry.isWeb)
        assertEquals(13, entry.versionCode)
        assertEquals(792344L, entry.size)
        assertTrue(entry.featured)
        assertEquals("ef9513f9906c539486c55c4ddec069b33ed0ec3070e9d6eeed2d0bc7c1664311", entry.sha256)
        assertEquals(listOf("https://m/a.zip"), entry.mirrors)
    }

    @Test(expected = IOException::class)
    fun htmlFallbackIsRejected() {
        // dzhz.top 是单页应用，文件没部署时会回 index.html
        MarketIndexParser.parse("<!doctype html><html></html>")
    }

    @Test(expected = IOException::class)
    fun newerSchemaIsRejected() {
        MarketIndexParser.parse("""{"schema": 99, "plugins": []}""")
    }

    @Test
    fun tagsFallBackToEntryTags() {
        val index = MarketIndexParser.parse(
            """{"schema":1,"plugins":[{"id":"a.b","name":"n","type":"web","download":"https://e/a.zip","tags":["生活","工具"]}]}""",
        )
        assertEquals(listOf("生活", "工具"), index.tags)
    }
}
