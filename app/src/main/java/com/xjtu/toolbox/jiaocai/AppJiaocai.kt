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

/**
 * `:app` 这一端对 `:core` 那两条 `jiaocai` 端口的填法，以及两条路由（`JIAOCAI` / `JIAOCAI1`）
 * 共用的宿主适配。
 *
 * 屏、ViewModel、取数与页解码都已在共享内核（`:core` 的 `jiaocai/`，取数与页字节在 `:data`），
 * 这里只剩三件宿主才知道的事：
 *  - [AppJiaocaiSource]（`:data`，与桌面共用）包住教材中心的取数；
 *  - [AppJiaocaiTermTextbooks] 包住 `DataCache` + `ScheduleCache`（**教务**那半张缓存）；
 *  - 教材全文库的书架 / 分类树缓存（`:app` 的 `jiaocai1/AppJiaocai.kt`，包住 Room DAO 与 DataCache）。
 *
 * ⚠️ 两条路由都在这里收口：`JIAOCAI` 落在「查教材」栏（`initialTab = 0`），`JIAOCAI1` 落在
 * 「全文库」栏（`initialTab = 2`）—— 与收编前那两个薄包装（`JiaocaiScreen` / `Jiaocai1Screen`）
 * 逐字同一条口径。
 */
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

/**
 * `:core` 的 [JiaocaiTermTextbooks] 在 Android 侧的填法：原样包住搬迁前
 * `JiaocaiScreen.kt` 里那个 `loadCurrentTermTextbooks(context)` ——**同一份缓存、同一个 TTL
 * （`Long.MAX_VALUE`）、同一个「无教材」过滤**（`TextbookItem.hasSubstantiveTextbook`），
 * 只是把 `TextbookItem` 映射成 `:core` 那一行 `JiaocaiTermTextbook`。
 *
 * 数据是**教务**的（`ScheduleCache` 的 `schedule_textbooks_$term`），与教材中心无关 ⇒
 * 这一层不做任何网络请求，只读缓存（搬迁前也是只读缓存）。
 */
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
