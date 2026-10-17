package com.xjtu.toolbox.desktop.jiaocai

import com.xjtu.toolbox.jiaocai.JiaocaiTermTextbook
import com.xjtu.toolbox.jiaocai.JiaocaiTermTextbooks

/** `:core` 的 [JiaocaiTermTextbooks] 在桌面上的填法：空表。 */
object DesktopJiaocaiTermTextbooks : JiaocaiTermTextbooks {

    override suspend fun currentTerm(): List<JiaocaiTermTextbook> = emptyList()
}
