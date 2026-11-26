package com.xjtu.toolbox.plugin

import com.xjtu.toolbox.plugin.api.PluginApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginToolContractTest {
    @Test
    fun `DEX install and installed records require the host API version`() {
        val manifest = PluginPackageManifest(
            id = "a.b", name = "Plugin", versionCode = 1, apiVersion = PluginApi.VERSION,
            dex = PluginDexPart("plugin.apk", "test.Entry", tools = listOf(tool("echo"))),
            files = listOf(PluginFileEntry("plugin.apk", "0".repeat(64), 1)), pubkey = "key",
        )
        validatePackageManifest(manifest)
        val incompatible = PluginApi.VERSION + 1
        val error = assertThrows(IllegalArgumentException::class.java) {
            validatePackageManifest(manifest.copy(apiVersion = incompatible))
        }
        assertTrue(error.message.orEmpty().contains("$incompatible"))
        assertTrue(listOf(record("a.b", apiVersion = incompatible)).registeredTools().none())
        assertEquals(null, PluginManifest(id = "a.b", name = "Web", type = "web", versionCode = 1).apiVersionError())
    }

    @Test
    fun `qualified names distinguish dots underscores and argument boundaries`() {
        val records = listOf(record("a.b"), record("a_b"), record("a_b_c", tool("echo")), record("a_b", tool("c_echo")))
        val tools = records.registeredTools().toList()
        assertEquals(4, tools.map { it.name }.toSet().size)
        assertNotEquals(tools[0].name, tools[1].name)
        val longest = listOf(record("a".repeat(32), tool("b".repeat(20)))).registeredTools().single()
        assertTrue(longest.name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }

    @Test
    fun `invalid arguments never reach plugin execution`() {
        val parameters = """{
          "type":"object", "required":["items"], "additionalProperties":false,
          "properties":{"items":{"type":"array","items":{"type":"integer","enum":[1,2]}}}
        }"""
        val registered = listOf(record("a.b", tool("echo", parameters))).registeredTools().single()
        val forwarded = mutableListOf<String>()
        for (args in listOf("", "[]", "{}", """{"items":["1"]}""", """{"items":[3]}""", """{"items":[1],"other":true}""")) {
            val result = registered.execute(args) {
                forwarded.add(it)
                "ok"
            }
            assertTrue(result.contains("invalid_arguments"))
        }
        assertTrue(forwarded.isEmpty())
        val valid = """{"items":[1.0,2]}"""
        assertEquals("ok", registered.execute(valid) {
            forwarded.add(it)
            "ok"
        })
        assertEquals(listOf(valid), forwarded)
        val duplicate = """{"items":[],"items":[1]}"""
        assertEquals("""{"items":[1]}""", registered.execute(duplicate) { it })
    }

    @Test
    fun `legacy tools without schema still accept empty and object arguments`() {
        val registered = listOf(record("a.b")).registeredTools().single()
        assertEquals("", registered.execute("") { it })
        assertEquals("{}", registered.execute("{}") { it })
        assertTrue(registered.execute("""{"x":undefined}""") { it }.contains("invalid_arguments"))
    }

    @Test
    fun `malformed and unsupported schemas are rejected`() {
        for (parameters in listOf("[]", """{"type":"object","required":[5]}""", """{"type":"object","oneOf":[]}""")) {
            val manifest = PluginPackageManifest(
                id = "a.b", name = "Plugin", versionCode = 1, apiVersion = PluginApi.VERSION,
                dex = PluginDexPart("plugin.apk", "test.Entry", tools = listOf(tool("echo", parameters))),
                files = listOf(PluginFileEntry("plugin.apk", "0".repeat(64), 1)), pubkey = "key",
            )
            val error = assertThrows(IllegalArgumentException::class.java) { validatePackageManifest(manifest) }
            assertTrue(error.message.orEmpty().contains("工具 echo"))
        }
    }

    private fun tool(name: String, parameters: String? = null): PluginToolManifest {
        val schema = parameters?.let { """, "parameters": $it""" }.orEmpty()
        val definition = Json.parseToJsonElement("""{"type":"function","function":{"name":"$name"$schema}}""").jsonObject
        return PluginToolManifest(name, definition)
    }

    private fun record(id: String, tool: PluginToolManifest = tool("echo"), apiVersion: Int = PluginApi.VERSION) = PluginRecord(
        id = id, versionCode = 1, versionName = "1.0", toolsEnabled = true,
        manifest = PluginManifest(id = id, name = "Plugin", versionCode = 1, apiVersion = apiVersion, tools = listOf(tool)),
    )
}
