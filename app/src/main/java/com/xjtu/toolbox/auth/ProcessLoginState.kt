package com.xjtu.toolbox.auth

import android.app.Application
import android.content.Context

/**
 * 进程级唯一一份 [AppLoginStateViewModel]。
 *
 * 主界面（MainActivity）和「一镜到底」的独立页面宿主（[com.xjtu.toolbox.sharedview.SeamlessPageActivity]）
 * 共用同一份会话 / 账号表：第二个 Activity 不能再 new 一个 [AppLoginStateViewModel]，
 * 否则会复制一份 SessionManager、把静态 `SessionManager.active` 覆盖掉，并在同一批 cookie 文件上打架。
 */
object ProcessLoginState {

    @Volatile
    private var instance: AppLoginStateViewModel? = null

    fun get(context: Context): AppLoginStateViewModel = instance ?: synchronized(this) {
        instance ?: AppLoginStateViewModel(context.applicationContext as Application).also { instance = it }
    }
}
