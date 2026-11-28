package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.AppJson
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Streamable HTTP MCP tools. Server instructions/annotations never bypass local confirmation. */
class McpAgentTools(
    private val config: AgentConfig,
    private val confirm: suspend (endpoint: String, tool: String, arguments: String) -> Boolean,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build(),
) : AgentTools {
    override val toolDefinitions = """[
      {"type":"function","function":{"name":"mcp_list_tools","description":"列出用户配置的远程 MCP 服务的工具及参数结构。","parameters":{"type":"object","properties":{}}}},
      {"type":"function","function":{"name":"mcp_call_tool","description":"调用远程 MCP 工具，名称和参数以 mcp_list_tools 的返回为准。每次调用都要用户在界面上确认。","parameters":{"type":"object","properties":{"name":{"type":"string"},"arguments":{"type":"object"}},"required":["name","arguments"]}}}
    ]"""
    private val mutex = Mutex()
    private var session: String? = null
    private var protocol = "2025-06-18"
    private var initialized = false
    private var nextId = 0
    private var available = emptySet<String>()

    override fun rateLimitKeyOf(toolName: String) = "mcp"

    suspend fun testConnection(): String = "连接成功，可用工具 ${listTools().size} 个"

    override suspend fun execute(name: String, argsJson: String): String {
        require(config.mcpEnabled) { "MCP 已关闭" }
        return when (name) {
            "mcp_list_tools" -> JsonArray(listTools()).toString() + "\n" + ToolReply.EXTERNAL_DATA
            "mcp_call_tool" -> {
                val args = AppJson.parseToJsonElement(argsJson).jsonObject
                val tool = args["name"]?.jsonPrimitive?.content.orEmpty()
                val arguments = args["arguments"] as? JsonObject ?: error("arguments 必须是对象")
                if (tool !in available) listTools()
                require(tool in available) { "远程服务不存在此工具，请先重新查询清单" }
                require(arguments.toString().length <= 32_768) { "工具参数过长" }
                if (!confirm(config.mcpEndpoint, tool, arguments.toString())) {
                    return "用户拒绝了远程工具调用；未执行。不要自行重试。"
                }
                mutex.withLock {
                    initialize()
                    rpc("tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) }).toString() + "\n" + ToolReply.EXTERNAL_DATA
                }
            }
            else -> ToolReply.notFound("tool", name)
        }
    }

    private suspend fun listTools(): List<JsonElement> = mutex.withLock {
        initialize()
        val tools = mutableListOf<JsonElement>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            tools += result["tools"]?.jsonArray.orEmpty()
            require(tools.size <= 512) { "远程工具数量超过限制" }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
            if (cursor != null) require(cursors.add(cursor)) { "远程工具分页游标重复" }
        } while (cursor != null)
        available = tools.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.toSet()
        tools
    }

    private suspend fun initialize() {
        if (initialized) return
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", protocol)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "xjtu-toolbox"); put("version", "1.0") })
        })
        val negotiated = result["protocolVersion"]?.jsonPrimitive?.content.orEmpty()
        require(negotiated in setOf("2025-06-18", "2025-03-26")) { "服务使用暂不支持的 MCP 协议版本" }
        protocol = negotiated
        require(result["capabilities"]?.jsonObject?.containsKey("tools") == true) { "此 MCP 服务未提供工具能力" }
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, null)
        initialized = true
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = ++nextId
        val response = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }, id) ?: error("MCP 没有返回结果")
        if (response.containsKey("error")) {
            val code = response["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content.orEmpty()
            error("MCP 请求失败（$code），请检查服务配置")
        }
        return response["result"] as? JsonObject ?: error("MCP 返回的结果格式不正确")
    }

    private suspend fun send(payload: JsonObject, id: Int?): JsonObject? {
        val url = config.mcpEndpoint.trim().toHttpUrl()
        require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1")) { "MCP 地址需要 HTTPS，本机回环地址可使用 HTTP" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "地址不能包含用户名、密码或片段" }
        val request = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .apply {
                if (config.mcpToken.isNotBlank()) header("Authorization", "Bearer ${config.mcpToken.trim()}")
                session?.let { header("Mcp-Session-Id", it) }
                if (payload["method"]?.jsonPrimitive?.content != "initialize") header("MCP-Protocol-Version", protocol)
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("MCP 连接失败，请检查网络与服务地址"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code == 404 && session != null) { initialized = false; session = null }
                            require(it.isSuccessful) { when(it.code) {
                                401, 403 -> "MCP 鉴权失败，请检查令牌或服务授权"
                                404 -> "MCP 地址或会话失效，请重新连接（不会自动重试写入）"
                                else -> "MCP HTTP ${it.code}"
                            } }
                            it.header("Mcp-Session-Id")?.let { value ->
                                require(value.length <= 1024 && value.all { c -> c.code in 33..126 }) { "MCP 会话标识无效" }
                                session = value
                            }
                            if (id == null) return@use null
                            val body = it.body ?: error("MCP 返回空响应")
                            val source = body.source()
                            if (body.contentType()?.subtype == "event-stream") {
                                var data = StringBuilder()
                                var read = 0L
                                while (!source.exhausted()) {
                                    val line = source.readUtf8LineStrict(1_048_576)
                                    read += line.length
                                    require(read <= 2_097_152) { "MCP 响应过长" }
                                    if (line.isEmpty() && data.isNotEmpty()) {
                                        val event = AppJson.parseToJsonElement(data.toString()).jsonObject
                                        data = StringBuilder()
                                        if (event["id"]?.jsonPrimitive?.intOrNull == id && ("result" in event || "error" in event)) return@use event
                                    } else if (line.startsWith("data:")) {
                                        if (data.isNotEmpty()) data.append('\n')
                                        data.append(line.removePrefix("data:").removePrefix(" "))
                                    }
                                }
                                error("MCP 事件流中缺少请求结果")
                            } else {
                                source.request(2_097_153)
                                val bytes = source.readByteArray(minOf(source.buffer.size, 2_097_153))
                                require(bytes.size <= 2_097_152) { "MCP 响应过长" }
                                val json = AppJson.parseToJsonElement(bytes.decodeToString()).jsonObject
                                require(json["id"]?.jsonPrimitive?.intOrNull == id) { "MCP 响应编号不匹配" }
                                json
                            }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
}
