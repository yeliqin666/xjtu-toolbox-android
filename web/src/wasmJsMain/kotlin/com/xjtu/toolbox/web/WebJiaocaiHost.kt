package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.CampusJwxtScheduleApi
import com.xjtu.toolbox.jiaocai.JiaocaiTermTextbook
import com.xjtu.toolbox.jiaocai.JiaocaiTermTextbooks
import com.xjtu.toolbox.jiaocai.toJiaocaiTermTextbooks

/** 「本学期我的教材」那一段在 serve 形态下的宿主填空（`:core` 的 [JiaocaiTermTextbooks]）。 */
internal class WebJiaocaiTermTextbooks(
    private val jwxt: CampusJwxtScheduleApi,
) : JiaocaiTermTextbooks {

    override suspend fun currentTerm(): List<JiaocaiTermTextbook> =
        jwxt.textbooks(term = null).toJiaocaiTermTextbooks()
}
