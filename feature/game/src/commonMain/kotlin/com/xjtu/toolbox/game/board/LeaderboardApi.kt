package com.xjtu.toolbox.game.board

import com.xjtu.toolbox.core.net.toolboxEngine
import com.xjtu.toolbox.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 榜上一行：名次、名称、分数。就这三样，服务端也不存别的。 */
data class BoardRow(val rank: Int, val name: String, val score: Int)

/**
 * 一次拉取的结果。
 *
 * [total] 是参与人数（不是榜长），[me] 是自己在榜上的那一行，不在前 [Leaderboard.LIMIT] 名内时为 null——
 * 玩家想知道自己排多少，但榜只放得下前一百。
 */
data class BoardSnapshot(val rows: List<BoardRow>, val total: Int, val me: BoardRow?) {

    /** 分数线：够到这个分数才算上榜。榜没满时人人都能上。 */
    val cutoff: Int get() = if (rows.size < Leaderboard.LIMIT) 0 else rows.last().score

    fun qualifies(score: Int): Boolean = rows.size < Leaderboard.LIMIT || score > cutoff
}

/**
 * 全服榜接口。服务端是 `server/leaderboard`，部署在 zyxf.top 后面（见那边的 README）。
 *
 * 分数**不做任何校验**：整局游戏都在客户端跑，谁都能伪造一个分数传上来。所以这里只做
 * 「别把明显荒唐的值写进库」的粗筛，不做防作弊——按信任制设计，发现脏数据手工清。
 * 也正因为这个入口谁都能写，它单独部署、独立进程，不和持有密钥的资料站后端共用。
 *
 * KMP 移植：dev 上是 OkHttp + org.json、地址取 `BuildConfig`；这里换成 Ktor + kotlinx.serialization 放进
 * commonMain，地址由各端入口 [install] 进来（Android 仍取 `BuildConfig.LEADERBOARD_BASE_URL`，
 * 桌面用 [DEFAULT_BASE_URL]）。没 [install] 过（Web）= 整个排行榜功能不出现。
 */
object LeaderboardApi {

    /** 正式环境。公开地址、不是密钥。 */
    const val DEFAULT_BASE_URL = "https://zyxf.top/games"

    private var host: String = ""
    private var client: HttpClient? = null

    /** 地址为空 = 后端没上，整个排行榜功能不出现（本地构建可以留空）。 */
    val configured: Boolean get() = host.isNotBlank() && client != null

    /**
     * 各端入口调一次。[baseUrl] 为空串 = 关掉排行榜；[engine] 只给测试换假引擎用。
     */
    fun install(baseUrl: String, engine: HttpClientEngine? = null) {
        host = baseUrl.trim().trimEnd('/')
        client?.close()
        client = if (host.isBlank()) null else HttpClient(engine ?: toolboxEngine()) {
            // 非 2xx 不抛，由 [call] 按 code 读服务端那句话
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
            }
        }
    }

    /** 拉榜单。[uid] 传上来只为了在响应里回一条 [BoardSnapshot.me]，不参与排名计算。 */
    suspend fun board(game: String, uid: String, limit: Int = Leaderboard.LIMIT): BoardSnapshot {
        val http = client ?: throw LeaderboardError("榜单暂时不可用")
        val url = "$host/board?game=${game.encodeURLParameter()}&uid=${uid.encodeURLParameter()}&limit=$limit"
        return parseBoard(call(http.get(url)))
    }

    /**
     * 上报成绩。[name] 为 null 即匿名，服务端会落成 [LeaderboardStore.ANONYMOUS]。
     *
     * 服务端按「游戏 + uid」去重，同一个人同一款游戏只有一行，分数只增不减，
     * 所以重复调用是安全的，不用先查再写。
     */
    suspend fun submit(game: String, uid: String, name: String?, score: Int) {
        val http = client ?: throw LeaderboardError("榜单暂时不可用")
        val body = buildJsonObject {
            put("game", game)
            put("uid", uid)
            put("name", name?.takeIf { it.isNotBlank() } ?: LeaderboardStore.ANONYMOUS)
            put("score", score)
        }.toString()
        call(
            http.post("$host/score") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        )
    }

    /** 非 2xx 一律抛 [LeaderboardError]，UI 层统一按「榜单暂时不可用」处理。 */
    private suspend fun call(resp: HttpResponse): String {
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            val json = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
            throw LeaderboardError(
                message = json.str("error")?.takeIf { it.isNotBlank() }
                    ?: "榜单暂时不可用（${resp.status.value}）",
                code = json.str("code")?.takeIf { it.isNotBlank() },
            )
        }
        return text
    }

    internal fun parseBoard(text: String): BoardSnapshot {
        val json = AppJson.parseToJsonElement(text).jsonObject
        val rows = runCatching { json["rows"]?.jsonArray }.getOrNull().orEmpty()
        return BoardSnapshot(
            rows = rows.mapIndexed { i, e ->
                val r = e.jsonObject
                BoardRow(
                    rank = r.int("rank") ?: (i + 1),
                    name = r.str("name").orEmpty().ifBlank { LeaderboardStore.ANONYMOUS },
                    score = r.int("score") ?: 0,
                )
            },
            total = json.int("total") ?: 0,
            me = (json["me"] as? JsonObject)?.let {
                BoardRow(
                    rank = it.int("rank") ?: 0,
                    name = it.str("name").orEmpty().ifBlank { LeaderboardStore.ANONYMOUS },
                    score = it.int("score") ?: 0,
                )
            },
        )
    }

    private fun JsonObject?.str(key: String): String? =
        runCatching { this?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject?.int(key: String): Int? =
        runCatching { this?.get(key)?.jsonPrimitive?.intOrNull }.getOrNull()

    /** 服务端判定这个名字不能上榜（命中禁止词或被内容审核拦下）。 */
    const val CODE_NAME_REJECTED = "name_rejected"

    /** 这个名字已经被别人占了：同一款游戏里昵称是独占的。 */
    const val CODE_NAME_TAKEN = "name_taken"

    /**
     * 服务端这次没能把名字送出去审（内容审核服务抖动）。不是名字本身的问题，重试就好，
     * 服务端**不会**因此放一个没审过的名字进榜。
     */
    const val CODE_MODERATION_UNAVAILABLE = "moderation_unavailable"
}

class LeaderboardError(message: String, val code: String? = null) : Exception(message) {

    /** 名字本身有问题（被内容审核拦下、或已被别人占用）：该让玩家换一个。 */
    val nameProblem: Boolean
        get() = code == LeaderboardApi.CODE_NAME_REJECTED || code == LeaderboardApi.CODE_NAME_TAKEN

    /**
     * 该把服务端那句话原样展示给玩家、并让弹窗留在原地。
     *
     * 比 [nameProblem] 宽：审核没跑成时也算——那句话是「稍后再试一次」，
     * 当成网络故障报「检查网络再试」会把人引到错的方向。
     */
    val showableProblem: Boolean
        get() = nameProblem || code == LeaderboardApi.CODE_MODERATION_UNAVAILABLE
}