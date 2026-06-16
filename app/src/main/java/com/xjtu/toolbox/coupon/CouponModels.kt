package com.xjtu.toolbox.coupon

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull

private const val COUPON_BASE_URL = "https://egc.xjtu.edu.cn"

enum class CouponFilter(
    val label: String,
    val status: String,
    val count: String,
    val expired: String,
    val emptyTitle: String
) {
    AVAILABLE("可领取", "0", "", "3", "暂无可领取加餐券"),
    USABLE("可使用", "1", "1", "3", "暂无可使用加餐券"),
    USED_UP("已用完", "", "0", "", "暂无已用完加餐券"),
    EXPIRED("已过期", "", "1", "2", "暂无已过期加餐券")
}

data class CouponRecord(
    val sendId: String,
    val showCardId: String,
    val voucherName: String,
    val typeName: String,
    val amountFen: Long,
    val leftAmountFen: Long,
    val leftCount: Int,
    val startDate: String,
    val endDate: String,
    val imageUrl: String
) {
    val amountYuan: Double get() = amountFen / 100.0
    val leftAmountYuan: Double get() = leftAmountFen / 100.0
}

data class CouponPage(
    val records: List<CouponRecord>,
    val total: Int
)

data class CouponDetail(
    val showCardId: String,
    val voucherName: String,
    val title: String,
    val description: String,
    val amountFen: Long,
    val leftAmountFen: Long,
    val startDate: String,
    val endDate: String,
    val batchId: String,
    val imageUrl: String,
    val closedPacketImageUrl: String,
    val openPacketImageUrl: String
) {
    val amountYuan: Double get() = amountFen / 100.0
}

data class CouponType(
    val id: Int,
    val name: String
)

object CouponJsonParser {
    fun parsePage(root: JsonObject): CouponPage {
        val data = root.safeGet("data")?.asJsonObjectOrNull()
            ?: return CouponPage(emptyList(), 0)
        val total = data.safeGet("total").safeInt(0)
        val records = data.safeGet("records")?.asJsonArrayOrNull()
            ?.mapNotNull { it.asJsonObjectOrNull()?.let(::parseRecord) }
            .orEmpty()
        return CouponPage(records, total)
    }

    fun parseTypes(root: JsonObject): List<CouponType> {
        val data = root.safeGet("data") ?: return emptyList()
        val array = when {
            data.isJsonArray -> data.asJsonArray
            data.isJsonObject -> data.asJsonObject.safeGet("records")?.asJsonArrayOrNull()
                ?: data.asJsonObject.safeGet("list")?.asJsonArrayOrNull()
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { element ->
            val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.safeGet("typeId").safeInt(
                obj.safeGet("id").safeInt(obj.safeGet("value").safeInt(0))
            )
            val name = obj.safeGet("typeName").safeString(
                obj.safeGet("name").safeString(obj.safeGet("label").safeString())
            )
            if (id == 0 && name.isBlank()) null else CouponType(id, name)
        }
    }

    fun parseDetail(root: JsonObject, showCardId: String): CouponDetail {
        val data = root.safeGet("data")?.asJsonObjectOrNull()
            ?: return CouponDetail(
                showCardId = showCardId,
                voucherName = "加餐券",
                title = "",
                description = "",
                amountFen = 0L,
                leftAmountFen = 0L,
                startDate = "",
                endDate = "",
                batchId = "",
                imageUrl = "",
                closedPacketImageUrl = "",
                openPacketImageUrl = ""
            )
        return CouponDetail(
            showCardId = showCardId,
            voucherName = data.safeGet("voucherName").safeString("加餐券"),
            title = data.safeGet("destitle").safeString(),
            description = data.safeGet("describes").safeString(),
            amountFen = data.safeGet("tranamt").safeLong(),
            leftAmountFen = data.safeGet("ltranamt").safeLong(),
            startDate = data.safeGet("startDate").safeString(),
            endDate = data.safeGet("endDate").safeString(),
            batchId = data.safeGet("batchId").safeString(),
            imageUrl = normalizeImageUrl(data.safeGet("pic").safeString()),
            closedPacketImageUrl = normalizeImageUrl(data.safeGet("rclose").safeString()),
            openPacketImageUrl = normalizeImageUrl(data.safeGet("ropen").safeString())
        )
    }

    private fun parseRecord(obj: JsonObject): CouponRecord {
        val pic = obj.safeGet("pic").safeString()
        return CouponRecord(
            sendId = obj.safeGet("sendId").safeString(),
            showCardId = obj.safeGet("showCardId").safeString(),
            voucherName = obj.safeGet("voucherName").safeString("加餐券"),
            typeName = obj.safeGet("typeName").safeString("加餐券"),
            amountFen = obj.safeGet("tranamt").safeLong(),
            leftAmountFen = obj.safeGet("ltranamt").safeLong(),
            leftCount = obj.safeGet("lknumber").safeInt(0),
            startDate = obj.safeGet("startDate").safeString(),
            endDate = obj.safeGet("endDate").safeString(),
            imageUrl = normalizeImageUrl(pic)
        )
    }

    private fun normalizeImageUrl(pic: String): String {
        if (pic.isBlank()) return ""
        return when {
            pic.startsWith("http://") || pic.startsWith("https://") -> pic
            pic.startsWith("/") -> COUPON_BASE_URL + pic
            else -> "$COUPON_BASE_URL/$pic"
        }
    }
}

private fun JsonElement?.safeLong(default: Long = 0L): Long {
    val raw = this.safeStringOrNull() ?: return default
    return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: default
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
    if (isJsonObject) asJsonObject else null

private fun JsonElement.asJsonArrayOrNull() =
    if (isJsonArray) asJsonArray else null
