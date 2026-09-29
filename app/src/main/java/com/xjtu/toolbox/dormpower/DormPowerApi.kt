package com.xjtu.toolbox.dormpower

import android.content.Context
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.SsnLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class DormPowerApi(private val site: SiteSession, private val context: Context) {

    private fun cid(): String = site.localToken["cid"] ?: throw AuthExpiredException("宿舍电费")

    private fun builder(url: String) = Request.Builder().url(url)
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .header("Referer", SsnLogin.PAY_PAGE_URL)
        .header("X-Requested-With", "XMLHttpRequest")

    private suspend fun send(request: Request): String {
        val response = site.executeWithReAuth(request)
        return withContext(Dispatchers.IO) {
            response.use {
                val bytes = it.body.bytes()
                if (!it.isSuccessful) throw RuntimeException("宿舍电费请求失败（HTTP ${it.code}）")
                DormPowerParsers.decode(bytes)
            }
        }
    }

    private suspend fun get(path: String, vararg query: Pair<String, String>) = DormPowerParsers.data(
        send(
            builder(
                "${SsnLogin.BASE_URL}$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString(),
            ).get().build(),
        ),
    )

    private suspend fun post(path: String, vararg body: Pair<String, String>) = DormPowerParsers.data(
        send(
            builder("${SsnLogin.BASE_URL}$path")
                .post(buildJsonObject { body.forEach { (k, v) -> put(k, v) } }.toString().toRequestBody(JSON))
                .build(),
        ),
    )

    suspend fun rooms(): List<DormRoom> = DormPowerParsers.rooms(get("/mobile/addr/list", "cid" to cid()))

    /** 房间剩余电量（度）；查不到返回 null。 */
    suspend fun kwh(roomId: String): Double? = DormPowerParsers.kwh(get("/mobile/meterAccount/electricity", "roomId" to roomId))

    suspend fun bind(room: DormRoom) {
        post("/mobile/bind/addr", "cid" to cid(), "roomId" to room.id, "roomName" to room.name)
    }

    suspend fun unbind(roomId: String) {
        post("/mobile/delete/addr", "roomId" to roomId, "cid" to cid())
    }

    /** 全校房间树，1MB 多，落到缓存文件里一个月内不再重复下。 */
    suspend fun tree(): List<RoomNode> = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, TREE_CACHE)
        val cached = file.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < TREE_TTL_MS }
            ?.let { runCatching { DormPowerParsers.tree(DormPowerParsers.data(DormPowerParsers.decode(it.readBytes()))) }.getOrNull() }
        if (cached != null) return@withContext cached
        val text = send(builder("${SsnLogin.BASE_URL}/region/tRegion/treeTab").get().build())
        val nodes = DormPowerParsers.tree(DormPowerParsers.data(text))
        if (nodes.isNotEmpty()) runCatching { file.writeText(text) }
        nodes
    }

    private companion object {
        val JSON = "application/json; charset=UTF-8".toMediaType()
        const val TREE_CACHE = "dorm_room_tree.json"
        const val TREE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
