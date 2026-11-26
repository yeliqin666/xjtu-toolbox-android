package com.xjtu.toolbox.campusmap

import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.AgentWidget
import com.xjtu.toolbox.agent.CampusMapWidget
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 屁岱的 `campus_map`：校内地点、步行时间、附近的食堂/图书馆；能定位的端不给起点就定位到最近的楼
 * （原先单独的 `my_location` 并了进来，旧名仍可执行）。纯本地，数据见 [CampusMaps]，每次附一张校区简图卡。
 *
 * @param locate 端上定位（返回 WGS-84 的纬度、经度；拿不到 / 没授权返回 null）。没有的端 from 必填。
 */
class CampusMapAgentTools(
    private val locate: (suspend () -> Pair<Double, Double>?)? = null,
) : AgentTools {

    private val pendingWidgets = mutableListOf<AgentWidget>()

    override fun drainWidgets(): List<AgentWidget> = pendingWidgets.toList().also { pendingWidgets.clear() }

    override val toolDefinitions: String by lazy {
        fun prop(type: String, d: String) = buildJsonObject { put("type", type); put("description", d) }
        fun tool(name: String, desc: String, props: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", desc)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(props))
                    put("required", JsonArray(required.map { JsonPrimitive(it) }))
                })
            })
        }
        val here = locate != null
        JsonArray(
            listOf(
                tool(
                    "campus_map",
                    "校内地图（兴庆、雁塔、曲江、创新港），附简图卡：查地点在哪；给 to 算步行分钟和方向；给 near 列附近最近的食堂、图书馆等。" +
                        (if (here) "不给 from 时定位用户此刻位置（首次请求授权）。" else "") +
                        "地图没有的地点返回找不到。",
                    mapOf(
                        "from" to prop("string", "起点：楼名、教室（主楼A-403）、食堂、宿舍、校门。" + if (here) "缺省：用户当前位置。" else ""),
                        "to" to prop("string", "终点。"),
                        "near" to buildJsonObject {
                            put("type", "string")
                            put("description", "列出起点附近最近的这类地点。")
                            put("enum", JsonArray(KINDS.keys.map { JsonPrimitive(it) }))
                        },
                        "campus" to prop("string", "兴庆/雁塔/曲江/创新港。缺省：按地点名自动找。"),
                    ),
                    if (here) emptyList() else listOf("from"),
                ),
            ),
        ).toString()
    }

    override val legacyNames: Set<String> = if (locate != null) setOf("my_location") else emptySet()

    override fun rateLimitKeyOf(toolName: String): String? = null

    override suspend fun execute(name: String, argsJson: String): String {
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        fun arg(k: String) = args[k].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return when (name) {
            "campus_map" -> {
                val from = arg("from")
                when {
                    from != null -> campusMap(from, arg("to"), arg("near"), arg("campus"))
                    locate == null -> ToolReply.missing("from")
                    arg("to") == null && arg("near") == null -> myLocation()
                    else -> campusMap(HERE_WORDS.first(), arg("to"), arg("near"), arg("campus"))
                }
            }
            "my_location" -> myLocation()
            else -> ToolReply.notFound("tool", name)
        }
    }

    private suspend fun here(): Pair<Double, Double>? = locate?.invoke()

    private suspend fun myLocation(): String {
        val (lat, lon) = here() ?: return ToolReply.failed("my_location", "no_permission_or_no_fix")
        val (map, hit) = CampusMaps.locate(lat, lon) ?: return "result: off_campus; lat: $lat; lon: $lon"
        val (place, meters) = hit
        pendingWidgets += CampusMapWidget(map.campus, listOf(place.name), here = listOf(lat, lon))
        return "${map.campus}｜离 ${place.name} 约 $meters 米（定位误差可能有几十米）"
    }

    private suspend fun campusMap(fromQ: String, toQ: String?, nearKind: String?, campus: String?): String {
        val fromHere = fromQ in HERE_WORDS
        val herePoint = if (fromHere) here() ?: return ToolReply.failed("my_location", "no_permission_or_no_fix") else null
        val (map, from) = if (herePoint != null) {
            val m = CampusMaps.locate(herePoint.first, herePoint.second)?.first
                ?: return "result: off_campus; lat: ${herePoint.first}; lon: ${herePoint.second}"
            m to CampusPlace("你的位置", PlaceKind.OTHER, herePoint.first, herePoint.second)
        } else {
            CampusMaps.find(fromQ, campus) ?: return ToolReply.notFound("place", fromQ)
        }
        val shown = mutableListOf<String>()
        if (!fromHere) shown += from.name
        var caption = ""
        val text = buildString {
            append("${map.campus}｜${from.name}（${from.kind.label}）\n")
            toQ?.let { q ->
                val to = CampusMaps.find(q, map.campus)?.second
                if (to == null) append(ToolReply.notFound("place", q)).append('\n')
                else {
                    val min = CampusMaps.walkMinutes(from, to)
                    caption = "${from.name} → ${to.name}：步行约 $min 分钟"
                    shown += to.name
                    append("到 ${to.name}：步行约 $min 分钟，直线 ${CampusMaps.distanceMeters(from, to).toInt()} 米，在${from.name}的${CampusMaps.bearing(from, to)}方\n")
                }
            }
            nearKind?.let { k ->
                val kind = KINDS[k] ?: return ToolReply.badValue("near", KINDS.keys.joinToString("/"))
                val list = CampusMaps.nearest(from, map, kind)
                if (list.isEmpty()) append(ToolReply.empty("near: $k")).append('\n')
                list.forEach { (p, min) ->
                    shown += p.name
                    append("- ${p.name}：步行约 $min 分钟，${CampusMaps.bearing(from, p)}方\n")
                }
            }
            append("（步行时间按直线距离估算，仅供参考；${CampusMaps.ATTRIBUTION}）")
        }
        pendingWidgets += CampusMapWidget(
            campus = map.campus,
            places = shown.distinct(),
            route = toQ != null && nearKind == null,
            caption = caption,
            here = herePoint?.let { listOf(it.first, it.second) },
        )
        return text
    }

    private companion object {
        val KINDS = mapOf(
            "canteen" to PlaceKind.CANTEEN, "library" to PlaceKind.LIBRARY, "gate" to PlaceKind.GATE,
            "sport" to PlaceKind.SPORT, "shop" to PlaceKind.SHOP, "dorm" to PlaceKind.DORM, "building" to PlaceKind.BUILDING,
        )
        val HERE_WORDS = setOf("我的位置", "当前位置", "我这儿", "这里", "here")
    }
}
