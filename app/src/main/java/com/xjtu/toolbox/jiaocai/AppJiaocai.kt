package com.xjtu.toolbox.jiaocai

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.jiaocai1.AppJiaocai1Cache
import com.xjtu.toolbox.jiaocai1.AppJiaocai1ShelfStore
import com.xjtu.toolbox.jiaocai1.AppJiaocai1Source
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.ScheduleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `:app` 这一端对 `:core` 那两条 `jiaocai` 端口的填法，以及两条路由（`JIAOCAI` / `JIAOCAI1`）共用的宿主适配。 */
@Composable
fun AppJiaocaiScreen(
    site: SiteSession,
    onBack: () -> Unit,
    onOpenBook: (ssno: String, title: String) -> Unit,
    initialTab: Int,
    authExpiredRoute: AppRoute,
) {
    val context = LocalContext.current.applicationContext
    TextbookScreen(
        source = remember(site) { AppJiaocaiSource(site) },
        termTextbooks = remember(context) { AppJiaocaiTermTextbooks(context) },
        jiaocai1Source = remember(site) { AppJiaocai1Source(site, context.cacheDir) },
        jiaocai1Shelf = remember(context) { AppJiaocai1ShelfStore(context) },
        jiaocai1Cache = remember(context) { AppJiaocai1Cache(context) },
        onBack = onBack,
        onOpenBook = onOpenBook,
        initialTab = initialTab,
        authExpiredRoute = authExpiredRoute,
    )
}

/** `:core` 的 [JiaocaiTermTextbooks] 在 Android 侧的填法。 */
class AppJiaocaiTermTextbooks(context: Context) : JiaocaiTermTextbooks {

    private val appContext = context.applicationContext

    override suspend fun currentTerm(): List<JiaocaiTermTextbook> = withContext(Dispatchers.IO) {
        val dc = DataCache(appContext)
        val term = ScheduleCache.readCurrentTerm(dc) ?: return@withContext emptyList()
        ScheduleCache.readTextbooks(dc, term, Long.MAX_VALUE).orEmpty()
            .filter { it.hasSubstantiveTextbook }
            .map {
                JiaocaiTermTextbook(
                    courseName = it.courseName,
                    textbookName = it.textbookName,
                    author = it.author,
                    publisher = it.publisher,
                    isbn = it.isbn,
                )
            }
    }
}
