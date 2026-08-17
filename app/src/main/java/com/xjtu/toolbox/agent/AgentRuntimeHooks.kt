package com.xjtu.toolbox.agent

/**
 * Agent 需要触达 UI 运行态时的注入点（由 MainActivity / 主导航在组合时填充）。
 * CredentialStore 写 pref 不会触发重组，改外观必须回调对应 state。
 */
object AgentRuntimeHooks {
    /** 即时应用深色模式（"system"/"light"/"dark"）。 */
    @Volatile
    var applyDarkMode: ((String) -> Unit)? = null

    /** 即时开关系统动态取色。 */
    @Volatile
    var applyDynamicColor: ((Boolean) -> Unit)? = null

    @Volatile
    var applyHomeTheme: ((String) -> Unit)? = null

    @Volatile
    var applyNavBarStyle: ((String) -> Unit)? = null

    @Volatile
    var applyShowQuickActions: ((Boolean) -> Unit)? = null
}
