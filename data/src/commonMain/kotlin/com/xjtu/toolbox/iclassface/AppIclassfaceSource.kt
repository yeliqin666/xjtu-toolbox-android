package com.xjtu.toolbox.iclassface

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** `:core` 的 [IclassfaceSource] 在 `:data` 里的实现（Android 与桌面侧共用） */
class AppIclassfaceSource(private val site: SiteSession) : IclassfaceSource {

    private val api = IclassfaceApi(site)

    override suspend fun fetchRecords(date: LocalDate): List<CheckinRecord> =
        withContext(Dispatchers.IO) { api.fetchRecords(date) }
}
