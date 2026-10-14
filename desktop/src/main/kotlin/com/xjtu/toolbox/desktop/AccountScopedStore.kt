package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext

/**
 * S7b：桌面端「按账号命名空间的 store 名」的**唯一一处规则**（规格 §3.5④ / 审计 #09 的端侧那半）：
 *
 *    端内名 = base + AccountContext.suffixFor(accountId)
 *
 * 后缀规则逐字来自 `AccountContext`（三端唯一的后缀真源），桌面上按账号分的落盘现在有四处：
 * 课表族（`DesktopScheduleHost.cache`，早就带后缀）、下载队列、教材书架、成绩报表
 * （这三处原本没有后缀，S7b 补齐 —— 它们各自 KDoc 里都写着「没有老数据」或「键里带学号」）。
 * 写成一处是为了不再出现「这一处带、那一处不带」。
 *
 * ## 名字在**建实例时**定下来（如实记一笔）
 *
 * 这几个 Store 的共享层缝没有 `accountId` 参数，桌面也没有 Android 那种「Room 查询级 accountId
 * 过滤」⇒ 实例建出来之后命名空间就定了。于是：
 *  - 外壳里那几处 `remember { ... }` 在切账号时会重建（路由分支离开组合），拿到新的后缀；
 *  - `DesktopDownloads.manager` 是**进程级单例** ⇒ 队列属于「本进程第一次碰它时那个账号」，
 *    切账号要重开进程才换命名空间（桌面没有「按账号切队列」的产品口径，审计 #09 那条仍未定）。
 *
 * ## 「旧名一次性读入」不在这里
 *
 * 旧名（加后缀之前那份）的**只读**兜底在各 Store 自己的构造器里（见它们的 `legacyStore` 参数）：
 * 那是「旧形状只读」（§6.1 第 2 条）—— 只读，不往回写、也不删旧文件。
 */
internal fun accountScopedStoreName(base: String, accountId: String? = AccountContext.activeAccountId): String =
    base + AccountContext.suffixFor(accountId)
