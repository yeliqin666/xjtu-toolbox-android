package com.xjtu.toolbox.yellowpage

/**
 * 假的**黄页**上游（`workflow.xjtu.edu.cn/selectpage` 的机构通讯录）—— 两条响应体的原文。
 *
 * ## 为什么它没有 `HttpServer`
 *
 * 图书馆/校历那两个夹具要起服务器，是因为它们面对的是**会话内核**（cookie、302、CAS）。
 * 黄页这条不需要：`:core` 的 [YellowPageApi] 直接收一个 `HttpClient`（端口在调用方一侧），
 * 所以消费者用 Ktor 的 **`MockEngine`** 就能把这两条响应喂进去 —— 少一层服务器、少一个端口。
 *
 * 于是本文件只提供「响应体长什么样」与「哪条路径对应哪个体」（[bodyFor]）。
 * 引擎的搭建留给消费者（`:desktop` 的 test 源集里有一份 `mockYellowPageClient()`）。
 *
 * ## 与解析的对应关系
 *
 * 逐字段对着 `:core` 的 `YellowPageApi.parseListBody` / `parseUpdateTime` 写：
 * 外层 `{e, m, d}`（`e == 0` 才算成功），`d.categories[]` / `d.departments[]` 里
 * **`status != 1` 的会被滤掉**，其余按 `(sort, id)` 升序。
 * 所以样本里**刻意各放一条停用的**，好让「过滤 + 排序」这两件事在断言里看得见。
 */
object YellowPageFixture {

    /** `YellowPageApi` 打的第一个地址（`$BASE_URL` 之后那一段）。 */
    const val LIST_PATH = "/selectpage/site/schoolePage/getList"

    /** 第二个地址：页面的「数据更新于」。 */
    const val UPDATE_TIME_PATH = "/selectpage/site/schoolePage/getUpdateTime"

    /**
     * `getList` 的响应体。
     *
     * 三条类别（第三条 `status=0` ⇒ 应被滤掉）、四条部门（第四条 `status=0` ⇒ 应被滤掉），
     * 且顺序刻意打乱（`sort` 1 的排在 `sort` 2 后面），好让排序也被钉住。
     * 电话里放一个 `/` 分隔的，覆盖 `phoneItems` 那条拆分规则。
     */
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

    /**
     * 按 URL 给响应体。
     *
     * 认不出的地址**抛异常**（不是回空 JSON）：URL 漂了要让测试响亮地红，
     * 而不是让「假上游照答、断言照过」静默成立 —— 校历那个夹具踩过一次同类坑（见交接文档 §5.3）。
     */
    fun bodyFor(url: String): String = when {
        url.endsWith(LIST_PATH) -> listJson
        url.endsWith(UPDATE_TIME_PATH) -> updateTimeJson
        else -> error("假黄页没有这条路径：$url")
    }
}
