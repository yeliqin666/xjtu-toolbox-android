package com.xjtu.toolbox.yellowpage

/** 假的黄页上游（`workflow.xjtu.edu.cn/selectpage` 的机构通讯录）—— 两条响应体的原文。 */
object YellowPageFixture {

    /** `YellowPageApi` 打的第一个地址（`$BASE_URL` 之后那一段）。 */
    const val LIST_PATH = "/selectpage/site/schoolePage/getList"

    /** 第二个地址：页面的「数据更新于」。 */
    const val UPDATE_TIME_PATH = "/selectpage/site/schoolePage/getUpdateTime"

    /** `getList` 的响应体。 */
    val listJson: String = """
        {"e":0,"m":"","d":{
          "categories":[
            {"id":2,"name":"学生工作部（处）","status":1,"sort":2},
            {"id":3,"name":"已停用的机构","status":0,"sort":0},
            {"id":1,"name":"教务处","status":1,"sort":1}
          ],
          "departments":[
            {"id":12,"categoryId":1,"name":"综合办公室","phone":"029-82668890","sort":2,"status":1},
            {"id":20,"categoryId":2,"name":"学生事务大厅","phone":"029-82668891/029-82668892","sort":1,"status":1},
            {"id":11,"categoryId":1,"name":"教学运行中心","phone":"029-82668888","sort":1,"status":1},
            {"id":30,"categoryId":1,"name":"已撤销的科室","phone":"029-00000000","sort":3,"status":0}
          ]
        }}
    """.trimIndent()

    /** `getUpdateTime` 的响应体（`LocalDateTime` 能解析的 ISO 形状）。 */
    val updateTimeJson: String = """
        {"e":0,"m":"","d":{"page_update_time":"2026-08-01T09:30:00"}}
    """.trimIndent()

    /** 按 URL 给响应体。 */
    fun bodyFor(url: String): String = when {
        url.endsWith(LIST_PATH) -> listJson
        url.endsWith(UPDATE_TIME_PATH) -> updateTimeJson
        else -> error("假黄页没有这条路径：$url")
    }
}
