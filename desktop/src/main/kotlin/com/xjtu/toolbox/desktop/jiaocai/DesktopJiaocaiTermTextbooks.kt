package com.xjtu.toolbox.desktop.jiaocai

import com.xjtu.toolbox.jiaocai.JiaocaiTermTextbook
import com.xjtu.toolbox.jiaocai.JiaocaiTermTextbooks

/**
 * `:core` 的 [JiaocaiTermTextbooks] 在桌面上的填法：**如实降级成空表**。
 *
 * 那一段（「本学期我的教材」）的数据来自**教务**：`:app` 的实现读的是 `ScheduleCache` 里
 * `schedule_textbooks_$term` 那份缓存（`DataCache` + `FinReport` 教材报表）。
 *
 * ⚠️ 现状（「我的课表」点亮之后）：桌面这一端**已经有课表缓存了** —— 键值文件
 * `schedule_cache<账号后缀>.properties`，与日程页读写的是同一份（见 `DesktopScheduleHost`）。
 * 但**本对象还没有接那条缓存**（要读的话就是三行：`readCurrentTerm` + `readTextbooks`），
 * 所以这里仍回空表 ⇒ 「本学期我的教材」那一段在桌面上不显示。
 * **TODO**：把那条缓存接上（`ScheduleCache.readCurrentTerm(cache)` → `readTextbooks(cache, it)`），
 * 那时这段说明与 `DesktopAuthJiaocaiJvmTest` 里那一条断言要一起改。
 *
 * 回空表的效果与「读完了但没有」那一档**逐字相同**（`MyTextbooksSection` 里
 * `textbooks ?: return` 之后是 `if (list.isEmpty()) return`）：那一段整段不显示，
 * 「查教材」栅照样能用（检索框 + 建议词 + 结果列表）。这不是「漏做」，是可核对的降级
 * —— 桌面还没有教务课表，就没有「我这学期的教材」这个事实。
 */
object DesktopJiaocaiTermTextbooks : JiaocaiTermTextbooks {

    override suspend fun currentTerm(): List<JiaocaiTermTextbook> = emptyList()
}
