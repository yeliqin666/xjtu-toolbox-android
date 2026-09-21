package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.ui.adaptive.readableWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.jiaocai1.Jiaocai1BrowseContent
import com.xjtu.toolbox.jiaocai1.Jiaocai1PageLoader
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfContent
import com.xjtu.toolbox.jiaocai1.Jiaocai1UsageNotice
import com.xjtu.toolbox.jiaocai1.Jiaocai1ViewModel
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.AppTabPager
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 *   分别落在 0 和 2 上，靠 [com.xjtu.toolbox.jiaocai.JiaocaiScreen]、
 *   [com.xjtu.toolbox.jiaocai1.Jiaocai1Screen] 这两个薄包装接住。
 * @param initialKeyword 进全文库栏时预填的检索词（原 `Jiaocai1Screen.initialKeyword`，
 *   目前仓库里没有调用方传非默认值，这里原样保留只是不丢参数）。
 * @param authExpiredRoute 会话过期后 `handleAuthExpired` 要带回的路由，
 *   两个旧路由各自传自己的，行为和收编前一致。
 */
@Composable
fun TextbookScreen(
    site: SiteSession,
    onBack: () -> Unit,
    onOpenBook: (ssno: String, title: String) -> Unit,
    initialTab: Int = 0,
    initialKeyword: String = "",
    authExpiredRoute: String = Routes.JIAOCAI,
) {
    val appLoginState = LocalAppLoginState.current
    val context = LocalContext.current
    val jiaocaiVm: JiaocaiViewModel = viewModel()
    jiaocaiVm.bind(site)
    val jiaocai1Vm: Jiaocai1ViewModel = viewModel()
    jiaocai1Vm.bind(context, site)
    val loader = remember(site) { Jiaocai1PageLoader(context, site) }

    // 全文库使用声明：三栏共用一个页面，进哪一栏都可能碰到全文内容，统一在最外层弹一次，
    // 不必等用户切到「全文库」栏才提醒——沿用原 Jiaocai1Screen 的做法（进页面就弹）。
    Jiaocai1UsageNotice(onDecline = onBack)

    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotBlank() && jiaocai1Vm.result == null) {
            jiaocai1Vm.keyword = initialKeyword
            selectedTab = 2
            jiaocai1Vm.search(1)
        }
    }

    if (jiaocaiVm.authExpired) {
        LaunchedEffect(Unit) {
            jiaocaiVm.authExpired = false
            appLoginState.handleAuthExpired(LoginType.JIAOCAI, authExpiredRoute, onBack)
        }
    }
    if (jiaocai1Vm.authExpired) {
        LaunchedEffect(Unit) {
            jiaocai1Vm.authExpired = false
            appLoginState.handleAuthExpired(LoginType.JIAOCAI, authExpiredRoute, onBack)
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = "教材",
                largeTitle = "教材",
                color = MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
        ) {
            AppSegmentedTabs(
                tabs = listOf("查教材", "书架", "全文库"),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
            )
            AppTabPager(
                pageCount = 3,
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> JiaocaiSearchContent(
                        site = site,
                        vm = jiaocaiVm,
                        onOpenFullText = onOpenBook,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier.fillMaxSize(),
                    )
                    1 -> Jiaocai1ShelfContent(
                        vm = jiaocai1Vm,
                        loader = loader,
                        onOpenBook = onOpenBook,
                    )
                    else -> Jiaocai1BrowseContent(
                        vm = jiaocai1Vm,
                        loader = loader,
                        onOpenBook = onOpenBook,
                    )
                }
            }
        }
    }
}
