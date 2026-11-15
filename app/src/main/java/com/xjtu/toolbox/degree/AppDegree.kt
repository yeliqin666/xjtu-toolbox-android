package com.xjtu.toolbox.degree

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.data.DataCache

/** :app 侧的学业进度装配：课表缓存用 [DataCache]，在修课程的取法见 [degreeCurrentCourses]。 */
fun appDegreeSource(context: Context, sessionManager: SessionManager): DegreeSource {
    val app = context.applicationContext
    return AppDegreeSource(sessionManager) {
        degreeCurrentCourses(sessionManager, DataCache(app, AccountContext.activeAccountId))
    }
}
