package com.xjtu.toolbox.yellowpage

import com.xjtu.toolbox.util.toDialableTel
import kotlinx.serialization.Serializable

/** 黄页（部门/机构通讯录）的数据模型。 */
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
