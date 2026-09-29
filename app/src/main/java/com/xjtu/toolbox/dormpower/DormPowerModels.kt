package com.xjtu.toolbox.dormpower

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 绑定了的宿舍；[name] 是「园区/楼/单元/层/房间…」用 / 连起来的全名。 */
data class DormRoom(val id: String, val name: String)

/** 学校房间树的一个节点，叶子就是能查电量的房间或电表。 */
class RoomNode(val id: String, val name: String, val children: List<RoomNode>) {
    val isLeaf get() = children.isEmpty()
}

/** 电量低于这个数就提醒（度）。 */
const val LOW_KWH = 10.0

object DormPowerParsers {
    /** 系统对同一个接口有时发 UTF-8、有时发 GBK，先严格按 UTF-8 解，不行再按 GBK。 */
    fun decode(bytes: ByteArray): String {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("GBK"))
        }
    }

    /** 拆 `{"code":0,"data":…,"msg":…}`：code 非 0 抛错，401001 算登录过期。 */
    fun data(text: String): JsonElement {
        val root = runCatching { AppJson.parseToJsonElement(text.trim()).jsonObject }
            .getOrElse { throw RuntimeException("宿舍电费返回了异常数据，请稍后重试") }
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code == 401001) throw AuthExpiredException("宿舍电费")
        if (code != 0) throw RuntimeException(root["msg"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "宿舍电费接口出错（$code）")
        return root["data"] ?: JsonNull
    }

    fun rooms(data: JsonElement): List<DormRoom> =
        (data as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["roomId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DormRoom(id, o["roomName"]?.jsonPrimitive?.content.orEmpty())
        }

    /** 剩余电量；系统没有这个房间的表时 data 为 null。 */
    fun kwh(data: JsonElement): Double? = (data as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()

    fun tree(data: JsonElement): List<RoomNode> = (data as? JsonArray).orEmpty().mapNotNull(::node)

    private fun node(e: JsonElement): RoomNode? {
        val o = e as? JsonObject ?: return null
        val id = o["value"]?.jsonPrimitive?.content ?: return null
        val kids = (o["children"] as? JsonArray).orEmpty().mapNotNull(::node)
        return RoomNode(id, o["name"]?.jsonPrimitive?.content.orEmpty(), kids)
    }
}
