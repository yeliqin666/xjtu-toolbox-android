package com.xjtu.toolbox.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.settings.SettingsStore

/**
 * 默认分组 + 默认摆放。
 *
 * **分组结构和改造前一致**：最上面一组「常用功能」，下面按 [ServiceCategory] 的目录顺序
 * 一组一个（这一类里没有可见功能就不出组）。每块默认 2×1 也就是「一行两块、图标 + 名称 +
 * 状态」，正是改造前分类卡的样子；要摆成 1×1 纯图标或 4×4 大块，进编辑态拖。
 *
 * [quickRoutes] 是宿主按点击习惯挑出来的常用入口；拿不到这份数据的端（桌面、Web）传空表，
 * 「常用功能」组就是空组——编辑态下仍然画出来，用户可以自己往里放。
 */
object HomeLayoutDefaults {

    const val QUICK_GROUP = "quick"

    fun of(accountType: AccountType, quickRoutes: List<AppRoute> = emptyList()): HomeLayout {
        val groups = mutableListOf<HomeGroup>()

        val quick = quickRoutes.distinctBy { it.id }.mapIndexed { index, route ->
            HomeTile(
                id = "$QUICK_GROUP/$index",
                routeId = route.id,
                x = (index % 2) * 2f,
                y = (index / 2).toFloat(),
                w = 2f,
                h = 1f,
            )
        }
        groups += HomeGroup(id = QUICK_GROUP, title = "常用功能", tiles = quick)

        AppServices.homeFor(accountType)
            .groupBy { it.category }
            .forEach { (category, services) ->
                val tiles = services.mapIndexed { index, service ->
                    HomeTile(
                        id = "${category.name}/${service.route.id}",
                        routeId = service.route.id,
                        x = (index % 2) * 2f,
                        y = (index / 2).toFloat(),
                        w = 2f,
                        h = 1f,
                    )
                }
                groups += HomeGroup(id = category.name, title = category.title, tiles = tiles)
            }

        return HomeLayout(groups)
    }
}

/**
 * 首页布局与主页设置的落盘（和其余应用设置同一个 `app_settings` 文件）。
 *
 * 只存**用户动过之后的整页快照**：[saved] 为 null 就是「没改过」，每次读的时候按当前
 * 服务目录重新生成默认布局——这样新增一个功能，默认布局里会出现它，而用户自己删掉的
 * 又不会被加回来。
 */
class HomeLayoutStore(
    private val store: KeyValueStore = keyValueStore(SettingsStore.APP_SETTINGS_FILE),
) {

    /** 用户摆过的整页布局；null = 没改过，用 [HomeLayoutDefaults]。 */
    var saved: HomeLayout?
        get() = HomeLayoutCodec.decode(store.getString(KEY_LAYOUT))
        set(value) {
            store.putString(KEY_LAYOUT, value?.let(HomeLayoutCodec::encode))
            HomeLayoutRevision.bump()
        }

    /**
     * 当前应当渲染的布局：存档优先，没有就按目录生成默认的。
     *
     * 读出来顺手收一遍空行（`compactGaps`，只网格模式）：老版本把空行留在存档里了，不收的话
     * 用户升级完看到的还是那片空行。
     */
    fun load(accountType: AccountType, quickRoutes: List<AppRoute> = emptyList()): HomeLayout =
        (saved ?: HomeLayoutDefaults.of(accountType, quickRoutes)).compactGaps(grid)

    fun save(layout: HomeLayout) {
        // 空组不落盘：编辑态里被搬空的组，保存这一下就自动删掉（它在常规态本来就是隐藏的）
        saved = layout.withoutEmptyGroups()
    }

    /** 「恢复默认分组和布局」：清掉整页快照，回到 [HomeLayoutDefaults]。 */
    fun reset() {
        store.remove(KEY_LAYOUT)
        HomeLayoutResetRevision.bump()
        HomeLayoutRevision.bump()
    }

    /**
     * 打开网格：把存档里那份**自由摆位取整落盘**，再开开关。
     *
     * 这一步不可撤销——原来那串 1.3×2.7 之类的非整数摆位在 [HomeLayout.snapToGrid] 之后
     * 就不存在了，关掉网格也不会回来。所以调用方（主页设置）必须先二次确认再进来。
     *
     * 顺手 bump [HomeLayoutGridRevision]：抽屉可能是从**编辑态**的齿轮打开的，那时首页手上
     * 还有一份草稿；不处理它，用户点保存就会把取整前的自由摆位又写回去。这个信号让首页把
     * 草稿也取整一次（只取整，不换内容——和「恢复默认」不同，用户摆的东西还在）。
     */
    fun enableGrid() {
        // 存档为空 = 从没摆过，默认布局本来就是整格摆的，不用取整
        saved?.let { save(it.snapToGrid()) }
        grid = true
        HomeLayoutGridRevision.bump()
        notifyChanged()
    }

    /**
     * 改完一批设置之后叫一次，让首页重新读盘。
     *
     * 上面那些 setter 故意不自己 bump：主页设置面板里的滑条一次拖动会连着写几十次，
     * 每次都 bump 会把整页重读几十遍。面板在连续改完之后（或者离开面板时）调这个就行。
     */
    fun notifyChanged() {
        HomeLayoutRevision.bump()
    }

    /** 拖拽是否吸附到 1×1 栅格；关掉就是自由摆放（带边界联动吸附）。 */
    var grid: Boolean
        get() = store.getBoolean(KEY_GRID, true)
        set(value) = store.putBoolean(KEY_GRID, value)

    /** 主页卡片材质：false = 毛玻璃（默认），true = 液态玻璃。 */
    var liquidCard: Boolean
        get() = store.getBoolean(KEY_LIQUID_CARD, false)
        set(value) = store.putBoolean(KEY_LIQUID_CARD, value)

    /**
     * 液态玻璃要不要带折射（透镜弯光 + 色散）。
     *
     * 折射是整个玻璃里最贵的一层：`lens()` 要在离屏层上再跑一遍位移采样，开色散时还是三通道
     * 各采一次。关掉它剩下的模糊、活力、高光、轮廓、投影一样不少，只是玻璃从「厚透镜」变成
     * 「磨砂片」——观感差一档，但滚动和拖动时省下相当一部分 GPU。
     *
     * 独立成一个键、默认 true：老用户升级上来外观不变，觉得卡的人自己往下拨一格。
     * 只在 [liquidCard] 为真时有意义（毛玻璃那条路本来就没有折射）。
     */
    var liquidRefraction: Boolean
        get() = store.getBoolean(KEY_LIQUID_REFRACTION, true)
        set(value) = store.putBoolean(KEY_LIQUID_REFRACTION, value)

    /**
     * 首页顶栏的功能按钮是否**统一右对齐**（默认开）。
     *
     * 开 = 扫码 / 搜索 / 通知三个圆钮都在顶栏右侧（改造前的排法）；
     * 关 = 扫码单独站到最左边、标题绝对居中，右侧只留搜索与通知（改版后的排法）。
     *
     * 只有自己画首页顶栏按钮的端才读它（`:app`）；桌面与 web 的首页顶栏由外壳画，
     * 主页设置抽屉在那两端也不画这一行（`SettingsRow.HOME_TOP_BAR` 没人认领）。
     */
    var topActionsRight: Boolean
        get() = store.getBoolean(KEY_TOP_ACTIONS_RIGHT, true)
        set(value) = store.putBoolean(KEY_TOP_ACTIONS_RIGHT, value)

    /** 主页背景图（裁好的 WebP Base64）；空串 = 没有背景图，用主题底色。 */
    var backgroundImage: String
        get() = store.getString(KEY_BACKGROUND_IMAGE).orEmpty()
        set(value) = store.putString(KEY_BACKGROUND_IMAGE, value.ifEmpty { null })

    /** 背景图的模糊半径（dp），0 = 不模糊。 */
    var backgroundBlur: Int
        get() = store.getInt(KEY_BACKGROUND_BLUR, 0).coerceIn(BACKGROUND_BLUR_MIN, BACKGROUND_BLUR_MAX)
        set(value) = store.putInt(
            KEY_BACKGROUND_BLUR,
            value.coerceIn(BACKGROUND_BLUR_MIN, BACKGROUND_BLUR_MAX),
        )

    /** 背景图的透明度（百分比，20..100）：100 = 原样，越低越透出主题底色。 */
    var backgroundOpacity: Int
        get() = store.getInt(KEY_BACKGROUND_OPACITY, BACKGROUND_OPACITY_MAX)
            .coerceIn(BACKGROUND_OPACITY_MIN, BACKGROUND_OPACITY_MAX)
        set(value) = store.putInt(
            KEY_BACKGROUND_OPACITY,
            value.coerceIn(BACKGROUND_OPACITY_MIN, BACKGROUND_OPACITY_MAX),
        )

    companion object {
        const val BACKGROUND_BLUR_MIN = 0
        const val BACKGROUND_BLUR_MAX = 10
        const val BACKGROUND_OPACITY_MIN = 20
        const val BACKGROUND_OPACITY_MAX = 100

        private const val KEY_LAYOUT = "home_layout"
        private const val KEY_GRID = "home_grid"
        private const val KEY_LIQUID_CARD = "home_liquid_card"
        private const val KEY_LIQUID_REFRACTION = "home_liquid_refraction"
        private const val KEY_TOP_ACTIONS_RIGHT = "home_top_actions_right"
        private const val KEY_BACKGROUND_IMAGE = "home_background_image"
        private const val KEY_BACKGROUND_BLUR = "home_background_blur"
        private const val KEY_BACKGROUND_OPACITY = "home_background_opacity"
    }
}

/**
 * 首页布局改过之后的进程内信号（和 `HeroAppearanceRevision` 一个套路）：
 * 主页设置面板、编辑态、首页自己各持一份状态，靠它一起重读，不依赖谁还留在组合里。
 */
object HomeLayoutRevision {
    var current by mutableIntStateOf(0)
        private set

    internal fun bump() {
        current++
    }
}

/**
 * 「恢复默认分组和布局」的独立信号。
 *
 * 不能复用 [HomeLayoutRevision]：那个计数器在改背景、开关网格、保存布局时都会动，而首页编辑态
 * 只在**恢复默认**这一件事上才该把手上草稿整个换成新的默认布局（别的信号要是也换，会把用户
 * 正摆着的草稿冲掉）。恢复默认是一次「推倒重来」，所以它同时也 bump [HomeLayoutRevision]。
 */
object HomeLayoutResetRevision {
    var current by mutableIntStateOf(0)
        private set

    internal fun bump() {
        current++
    }
}

/**
 * 「打开网格」的独立信号（见 [HomeLayoutStore.enableGrid]）。
 *
 * 也不能复用 [HomeLayoutRevision]：那个在改背景、保存布局时都会动，跟着取整会把用户正常摆着
 * 的草稿一遍遍重排。首页只在这个计数变化时把草稿取整一次。
 */
object HomeLayoutGridRevision {
    var current by mutableIntStateOf(0)
        private set

    internal fun bump() {
        current++
    }
}
