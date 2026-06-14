package com.xjtu.toolbox.agent

/**
 * Agent 需要触达 UI 运行态时的注入点（由 MainActivity 在 setContent 时填充）。
 * 例如改深色模式：CredentialStore 写 pref 不会触发主题重组，必须回调更新 MainActivity 的主题 state。
 */
object AgentRuntimeHooks {
    /** 即时应用深色模式（"system"/"light"/"dark"）。 */
    @Volatile
    var applyDarkMode: ((String) -> Unit)? = null
}
