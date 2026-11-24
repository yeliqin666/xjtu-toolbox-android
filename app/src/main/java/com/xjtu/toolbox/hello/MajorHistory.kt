package com.xjtu.toolbox.hello

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable

/** 档案里见到的一次专业：何时第一次见到、专业、学院。 */
@Serializable
data class MajorRecord(val at: Long, val profession: String, val department: String = "")

/**
 * 学籍档案里专业的变化记录，学期总结用来认出转专业。按账号存，只在档案刷新时顺手比一下，不额外请求。
 */
object MajorHistory {
    private const val KEY = "records"
    private const val MAX = 20

    private fun store(account: String?) = keyValueStore("major_history${AccountContext.suffixFor(account)}")

    fun read(account: String?): List<MajorRecord> =
        runCatching { AppJson.decodeFromString<List<MajorRecord>>(store(account).getString(KEY) ?: "[]") }.getOrDefault(emptyList())

    /** 新拿到一份档案：专业和上一条不同才追加。[previous] 是覆盖前的缓存，第一次记录时拿它补上旧专业。 */
    @Synchronized
    fun observe(account: String?, previous: HelloProfile?, current: HelloProfile) {
        val now = current.profession() ?: return
        val list = read(account).toMutableList()
        if (list.lastOrNull()?.profession == now) return
        if (list.isEmpty()) previous?.profession()?.takeIf { it != now }?.let {
            list += MajorRecord(previous.fetchedAt, it, previous.departmentName.trim())
        }
        list += MajorRecord(current.fetchedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), now, current.departmentName.trim())
        store(account).putString(KEY, AppJson.encodeToString(list.takeLast(MAX)))
    }

    private fun HelloProfile.profession() = professionName.trim().takeIf { it.isNotEmpty() }
}
