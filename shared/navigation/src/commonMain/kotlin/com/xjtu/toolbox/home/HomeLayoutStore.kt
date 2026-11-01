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

    /** 当前应当渲染的布局：存档优先，没有就按目录生成默认的。 */
    fun load(accountType: AccountType, quickRoutes: List<AppRoute> = emptyList()): HomeLayout =
        saved ?: HomeLayoutDefaults.of(accountType, quickRoutes)

    fun save(layout: HomeLayout) {
        saved = layout
    }

    /** 「恢复默认分组和布局」：清掉整页快照，回到 [HomeLayoutDefaults]。 */
    fun reset() {
        store.remove(KEY_LAYOUT)
        HomeLayoutRevision.bump()
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

    /**
     * 「打开设置页并直接定位到主页设置」的一次性信号。
     *
     * 首页编辑态的齿轮不是新路由（`AppRoute.Settings` 是个 data object，加参数会动到
     * 返回栈存盘和旧深链），所以走这个旗标：首页置位后照常打开设置页，
     * 设置页读一次、读到就展开主页设置面板并清掉。
     */
    var pendingHomeSettings: Boolean
        get() = store.getBoolean(KEY_PENDING_HOME_SETTINGS, false)
        set(value) = store.putBoolean(KEY_PENDING_HOME_SETTINGS, value)

    /** 设置页进来时读一次：读到 true 就清掉，避免下次进设置又自动展开。 */
    fun consumePendingHomeSettings(): Boolean {
        val pending = pendingHomeSettings
        if (pending) pendingHomeSettings = false
        return pending
    }

    companion object {
        const val BACKGROUND_BLUR_MIN = 0
        const val BACKGROUND_BLUR_MAX = 10
        const val BACKGROUND_OPACITY_MIN = 20
        const val BACKGROUND_OPACITY_MAX = 100

        private const val KEY_LAYOUT = "home_layout"
        private const val KEY_GRID = "home_grid"
        private const val KEY_LIQUID_CARD = "home_liquid_card"
        private const val KEY_BACKGROUND_IMAGE = "home_background_image"
        private const val KEY_BACKGROUND_BLUR = "home_background_blur"
        private const val KEY_BACKGROUND_OPACITY = "home_background_opacity"
        private const val KEY_PENDING_HOME_SETTINGS = "home_settings_pending"
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
