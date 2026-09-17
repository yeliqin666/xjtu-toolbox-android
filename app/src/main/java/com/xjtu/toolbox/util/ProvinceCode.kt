package com.xjtu.toolbox.util

/**
 * 从学号推生源地省份。
 *
 * 学号第 4-5 位是 GB/T 2260 省级行政区代码（前两位）。纯字符串运算，
 * 不碰 Android，方便在屁岱画像、匹配交友这类不同场景下复用同一份映射。
 */
object ProvinceCode {
    private val MAP = mapOf(
        "11" to "北京", "12" to "天津", "13" to "河北", "14" to "山西", "15" to "内蒙古",
        "21" to "辽宁", "22" to "吉林", "23" to "黑龙江", "31" to "上海", "32" to "江苏",
        "33" to "浙江", "34" to "安徽", "35" to "福建", "36" to "江西", "37" to "山东",
        "41" to "河南", "42" to "湖北", "43" to "湖南", "44" to "广东", "45" to "广西",
        "46" to "海南", "50" to "重庆", "51" to "四川", "52" to "贵州", "53" to "云南",
        "54" to "西藏", "61" to "陕西", "62" to "甘肃", "63" to "青海", "64" to "宁夏",
        "65" to "新疆", "71" to "台湾", "81" to "香港", "82" to "澳门"
    )

    /** 学号不够长或省码不在表里时返回 null，不猜。 */
    fun of(studentId: String): String? {
        if (studentId.length < 5) return null
        return MAP[studentId.substring(3, 5)]
    }
}
