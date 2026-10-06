package com.xjtu.toolbox.yellowpage

import com.xjtu.toolbox.util.toDialableTel
import kotlinx.serialization.Serializable

/**
 * 黄页（部门/机构通讯录）的数据模型。字段名即服务端 JSON 的键，直接解码接口原始数组。
 *
 * 从 :app 的 `yellowpage/YellowPageApi.kt` 原样搬进 commonMain（同一文件里的 HTTP 部分
 * 换成了 Ktor，见 [YellowPageApi]）。
 */
@Serializable
data class YellowPageCategory(val id: Int = 0, val name: String = "", val status: Int = 0, val sort: Int = 0)

@Serializable
data class YellowPageDepartment(
    val id: Int = 0,
    val categoryId: Int = 0,
    val name: String = "",
    val phone: String = "",
    val sort: Int = 0,
    val status: Int = 0,
) {
    val phoneItems: List<String>
        get() = phone.split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 取这一项里能拨的号码，解析规则与学籍档案共用，见 [toDialableTel]。 */
    fun dialNumber(item: String): String = item.toDialableTel()
}

@Serializable
data class YellowPageData(
    val categories: List<YellowPageCategory> = emptyList(),
    val departments: List<YellowPageDepartment> = emptyList(),
    val updateTime: String = "",
)
