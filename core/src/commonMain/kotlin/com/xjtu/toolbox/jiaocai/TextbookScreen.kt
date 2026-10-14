package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.ui.adaptive.readableWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.jiaocai1.Jiaocai1BrowseContent
import com.xjtu.toolbox.jiaocai1.Jiaocai1Cache
import com.xjtu.toolbox.jiaocai1.Jiaocai1PageImages
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfContent
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfStore
import com.xjtu.toolbox.jiaocai1.Jiaocai1Source
import com.xjtu.toolbox.jiaocai1.Jiaocai1UsageNotice
import com.xjtu.toolbox.jiaocai1.Jiaocai1ViewModel
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import com.xjtu.toolbox.nav.AppRoute

/**
 * 教材合并入口（PR L，plan2 §3）：应用里原本有三个各管一摊的「教材」页
 * （教材中心 `JiaocaiScreen`、教材全文库 `Jiaocai1Screen`），现在收进同一个页面，
 * 用 [AppTabPager] 横滑切三栏：
 *
 * - **查教材**：某门课配什么书（全校范围），教材中心检索；搜索框为空时额外挂一段
 *   「本学期我的教材」（读 `schedule_textbooks_$term` 缓存），一键读全文；
 * - **书架**：教材全文库的书架，打开过的书连阅读进度一起记着；
 * - **全文库**：读书。关键词为空、也没选分类时先看中图法分类树，
 *   选中分类或输入关键词后切到检索结果——不再是两个平行标签，是同一栏内的两种状态。
 *
 * 全文库只认 IP、不走 CAS，校外大概率打不开；这在全文库那一栏自己的空态/错误文案里说明，
 * 不在这一层重复处理。
 *
 * @param initialTab 默认打开哪一栏：0=查教材，1=书架，2=全文库。旧路由 `JIAOCAI`/`JIAOCAI1`
 *   分别落在 0 和 2 上，靠 `:app` 的 `jiaocai/AppJiaocai.kt`（它把宿主能力填成下面那几条端口）
 *   与 `:core` 的 `jiaocai1/Jiaocai1Screen.kt` 这两个薄包装接住。
 */
@Composable
fun TextbookScreen(
    source: JiaocaiSource,
    termTextbooks: JiaocaiTermTextbooks,
    /**
     * 全文库那一半（页图取数 + 书架 + 分类树缓存）。**null = 本端没有这一档** ⇒ 只画「查教材」一栏，
     * 各处的「读全文 / 阅读全文」入口一个都不画。
     *
     * serve 形态就是这样（`docs/api-contract.md` §5.6）：那八个端点里没有 `jiaocai1` 那条链
     *（另一个域名、另一套会话）—— 拿不到的东西不摆入口，而不是摆一个点了没反应的
     *（与 `LmsScreen.videoPlayer` / `AttendanceScreen.onOpenIclassface` 同一条 idiom）。
     * 两个都给齐了才算有：只给一个画不了那一栏。
     *
     * Android / 桌面两处调用点都给，所以那两端的屏一行未变。
     */
    jiaocai1Source: Jiaocai1Source? = null,
    jiaocai1Shelf: Jiaocai1ShelfStore? = null,
    jiaocai1Cache: Jiaocai1Cache? = null,
    onBack: () -> Unit,
    onOpenBook: (ssno: String, title: String) -> Unit,
    initialTab: Int = 0,
    initialKeyword: String = "",
    authExpiredRoute: AppRoute = AppRoute.Jiaocai,
) {
    val authExpiry = LocalAuthExpiry.current
    val jiaocaiVm: JiaocaiViewModel = viewModel { JiaocaiViewModel() }
    jiaocaiVm.bind(source)
    // jiaocai1（全文库）那一半也在共享内核：取数/页字节、书架、分类树缓存都是端口，由宿主决定填
    // 什么（Android 是 Room DAO + DataCache，桌面是 JSON 落盘 + 没有缓存），页图解码在 `:core`。
    // 宿主没填（serve 形态）时整半不建：见 `jiaocai1Source` 的说明。
    val lib = jiaocai1Source?.let { s -> jiaocai1Shelf?.let { shelf -> s to shelf } }
    val jiaocai1Vm: Jiaocai1ViewModel? = lib?.let { (libSource, libShelf) ->
        viewModel(key = "jiaocai-library") { Jiaocai1ViewModel(libSource, libShelf, jiaocai1Cache) }
    }
    jiaocai1Vm?.bind()
    val jiaocai1Images = lib?.let { (libSource, _) -> remember(libSource) { Jiaocai1PageImages(libSource) } }

    // 全文库使用声明：三栏共用一个页面，进哪一栏都可能碰到全文内容，统一在最外层弹一次，
    // 不必等用户切到「全文库」栏才提醒——沿用原 Jiaocai1Screen 的做法（进页面就弹）。
    // 没有全文库那一半时（本端没有那条链）不弹：那一栏根本没画出来。
    if (lib != null) Jiaocai1UsageNotice(onDecline = onBack)
    val tabs = if (lib != null) listOf("查教材", "书架", "全文库") else listOf("查教材")
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, tabs.lastIndex)) }

    LaunchedEffect(initialKeyword, jiaocai1Vm) {
        val libVm = jiaocai1Vm ?: return@LaunchedEffect
        if (initialKeyword.isNotBlank() && libVm.result == null) {
            libVm.keyword = initialKeyword
            selectedTab = 2
            libVm.search(1)
        }
    }

    // 会话过期交给导航层（`:core` 的 [LocalAuthExpiry]）：两个 ViewModel 各走各的，
    // 和搬迁前一样（那时是 `LocalAppLoginState.handleAuthExpired`）。
    if (jiaocaiVm.authExpired) {
        LaunchedEffect(Unit) {
            jiaocaiVm.authExpired = false
            authExpiry.onAuthExpired(authExpiredRoute, onBack)
        }
    }
    if (jiaocai1Vm?.authExpired == true) {
        LaunchedEffect(Unit) {
            jiaocai1Vm.authExpired = false
            authExpiry.onAuthExpired(authExpiredRoute, onBack)
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopAppBar(
                title = "教材",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                // 分段标签挂在顶栏里，和顶栏一起做一整块玻璃
                bottomContent = {
                    CompositionLocalProvider(LocalOnGlassBar provides (glass != null)) {
                        AppSegmentedTabs(
                            tabs = tabs,
                            selectedTabIndex = selectedTab,
                            onTabSelected = { selectedTab = it },
                            modifier = Modifier.readableWidth(),
                        )
                    }
                },
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        // 宽屏不再整页限宽 720：三栏里的书目卡片分列铺开，只有标签行还限宽居中
        Column(
            Modifier
                .padding(padding.withoutTop(glass))
                .fillMaxSize()
                .glassSource(glass)
        ) {
            // 三栏顶上都是不滚动的检索框 / 分类条，整栏让出顶栏高度，不压到玻璃后面
            AppTabPager(
                pageCount = tabs.size,
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
              Box(Modifier.fillMaxSize().padding(top = glassTop)) {
                when (page) {
                    0 -> JiaocaiSearchContent(
                        source = source,
                        termTextbooks = termTextbooks,
                        jiaocai1 = jiaocai1Source,
                        vm = jiaocaiVm,
                        onOpenFullText = onOpenBook,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 1 / 2 两页只在有全文库那一半时才会被划到（`pageCount = tabs.size`）：
                    // 没有那一半时只有一页。这里的判空是给编译器看的，不是给用户看的。
                    1 -> if (jiaocai1Vm != null && jiaocai1Images != null) {
                        Jiaocai1ShelfContent(
                            vm = jiaocai1Vm,
                            images = jiaocai1Images,
                            onOpenBook = onOpenBook,
                        )
                    }
                    else -> if (jiaocai1Vm != null && jiaocai1Images != null) {
                        Jiaocai1BrowseContent(
                            vm = jiaocai1Vm,
                            images = jiaocai1Images,
                            onOpenBook = onOpenBook,
                        )
                    }
                }
              }
            }
        }
    }
}
