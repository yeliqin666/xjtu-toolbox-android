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
 * **分组结构和尺寸规则与 dev 那套「小组件」一致**（升级后没编辑过的人看到的排法不变）：
 * 最上面一组「常用功能」，下面按 [ServiceCategory] 的目录顺序一组一个（这一类里没有可见功能
 * 就不出组）。尺寸：校园卡、付款码、图书馆给 2×2；课表、校历、LMS、考勤、空教室、通知、社区、游戏、
 * 小岱、黄页这些「信息型」给 2×1（放得下一行状态）；常用功能与其余给 1×1。
 *
 * 每组的块按 first-fit 从上到下、从左到右排（与 dev `HomeWidgetLayout.pack` 同一套），
 * 所以同一份目录排出来的样子和原来一致。要改成别的摆法，进编辑态拖。
 *
 * [quickRoutes] 是宿主按点击习惯挑出来的常用入口；拿不到这份数据的端（桌面、Web）传空表，
 * 「常用功能」组就是空组——编辑态下仍然画出来，用户可以自己往里放。
 */
object HomeLayoutDefaults {

    const val QUICK_GROUP = "quick"

    /**
     * 默认放大一档（2×2）：最常看、内容也最多（通知列最近几条标题）。
     * 付款码要 2×2 才画得下二维码，目录里它紧跟校园卡，两块正好并排占满一行。
     */
    private val FEATURED = setOf(
        AppRoute.CampusCard.id, AppRoute.PaymentCode.id, AppRoute.Library.id, AppRoute.DegreeProgress.id, AppRoute.Notification.id,
    )

    /** 信息型（2×1）：放一行状态比纯图标有用。 */
    private val INFORMATIONAL = setOf(
        AppRoute.Schedule.id, AppRoute.SchoolCalendar.id, AppRoute.Lms().id,
        AppRoute.Attendance.id, AppRoute.EmptyRoom.id,
        AppRoute.Community.id, AppRoute.Games.id, AppRoute.Agent.id, AppRoute.YellowPage.id,
        // 校车：横条里正好放「下一班 14:20 · 余 12 座」这种一行状态
        AppRoute.CampusBus.id,
    )

    fun of(accountType: AccountType, quickRoutes: List<AppRoute> = emptyList()): HomeLayout {
        val groups = mutableListOf<HomeGroup>()

        val quick = pack(
            groupId = QUICK_GROUP,
            items = quickRoutes.distinctBy { it.id }.map { DefaultSlot(it.id, 1, 1) },
        )
        groups += HomeGroup(id = QUICK_GROUP, title = "常用功能", tiles = quick)

        AppServices.homeFor(accountType)
            .groupBy { it.category }
            .forEach { (category, services) ->
                val tiles = pack(
                    groupId = category.name,
                    items = services.map { service ->
                        val id = service.route.id
                        when {
                            id in FEATURED -> DefaultSlot(id, 2, 2)
                            id in INFORMATIONAL -> DefaultSlot(id, 2, 1)
                            else -> DefaultSlot(id, 1, 1)
                        }
                    },
                )
                groups += HomeGroup(id = category.name, title = category.title, tiles = tiles)
            }

        return HomeLayout(groups)
    }

    /** 一块默认摆位的尺寸（列 × 行）。 */
    private data class DefaultSlot(val routeId: String, val cols: Int, val rows: Int)

    /**
     * 一组内的 first-fit 排位：从上到下、从左到右找第一个放得下的位置，小格回填前面的空洞。
     *
     * 与 dev `HomeWidgetLayout.pack` 的扫描顺序逐行一致，所以同一份目录排出来的样子和原来相同。
     */
    private fun pack(groupId: String, items: List<DefaultSlot>): List<HomeTile> {
        val columns = 4
        val taken = mutableListOf<BooleanArray>()
        fun row(r: Int): BooleanArray {
            while (taken.size <= r) taken.add(BooleanArray(columns))
            return taken[r]
        }
        fun fits(c: Int, r: Int, cols: Int, rows: Int): Boolean {
            if (c + cols > columns) return false
            for (dr in 0 until rows) {
                for (dc in 0 until cols) if (row(r + dr)[c + dc]) return false
            }
            return true
        }
        var serial = 0
        return items.map { slot ->
            var r = 0
            var spot: Pair<Int, Int>? = null
            while (spot == null) {
                for (c in 0..columns - slot.cols) {
                    if (fits(c, r, slot.cols, slot.rows)) {
                        spot = c to r
                        break
                    }
                }
                if (spot == null) r++
            }
            val (c0, r0) = spot!!
            for (dr in 0 until slot.rows) {
                for (dc in 0 until slot.cols) row(r0 + dr)[c0 + dc] = true
            }
            HomeTile(
                id = "$groupId/${slot.routeId}#${serial++}",
                routeId = slot.routeId,
                x = c0.toFloat(),
                y = r0.toFloat(),
                w = slot.cols.toFloat(),
                h = slot.rows.toFloat(),
            )
        }
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
     * 当前应当渲染的布局：卡片组存档优先 → 迁移 dev 那套小组件排版 → 最后才是按目录生成的默认布局。
     *
     * 读出来顺手收一遍空行（`compactGaps`，只网格模式）：老版本把空行留在存档里了，不收的话
     * 用户升级完看到的还是那片空行。
     */
    fun load(accountType: AccountType, quickRoutes: List<AppRoute> = emptyList()): HomeLayout {
        // 网格曾经可以关掉（自由摆放）：关过的存档在这里取整落盘一次，之后永远按网格
        if (!store.getBoolean(KEY_LEGACY_GRID, true)) {
            saved?.let { store.putString(KEY_LAYOUT, HomeLayoutCodec.encode(it.snapToGrid().withoutEmptyGroups())) }
            store.remove(KEY_LEGACY_GRID)
        }
        return (saved ?: migrateFromWidgetLayout(accountType) ?: HomeLayoutDefaults.of(accountType, quickRoutes))
            .compactGaps(grid)
    }

    /**
     * 把 dev 上那套「小组件」的排版迁成卡片组——**兼容必备**：升级不能把用户摆好的分组、
     * 标题、颜色、尺寸与顺序弄丢（那套实现已经并进卡片组，没有第二个渲染器能读它）。
     *
     * 只在「还没有卡片组存档、但有旧布局」时做一次：迁完把结果写进 `home_cards_layout`，
     * 下次 `saved` 就有值了。**旧键 `home_widgets` 不删**——留着不占多少地方，万一要回滚或
     * 再迁一次还有得读。这里不动 revision：本方法是读的一端，在组合里 bump 会触发多余重组。
     */
    private fun migrateFromWidgetLayout(accountType: AccountType): HomeLayout? {
        val lines = store.getString(KEY_LEGACY_WIDGETS)?.split('\n')?.filter { it.isNotBlank() }
        if (lines.isNullOrEmpty()) return null
        val available = AppServices.homeFor(accountType).map { it.route.id }.toSet()
        val migrated = HomeWidgetMigration.decode(lines, available) ?: return null
        val normalized = migrated.withoutEmptyGroups()
        store.putString(KEY_LAYOUT, HomeLayoutCodec.encode(normalized))
        return normalized
    }

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
     * 「从文件导入自定义布局」：把解析好的整页快照落盘。
     *
     * 语义和 [reset] 一样是**推倒重来**：首页编辑态手里那份草稿也要换成导入的这份，
     * 所以两个信号一起 bump（只 bump [HomeLayoutRevision] 的话，用户正摆着的草稿会在
     * 下一次「对勾」时把刚导入的布局又盖回去）。
     */
    fun importLayout(layout: HomeLayout) {
        save(layout)
        HomeLayoutResetRevision.bump()
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

/** 拖拽与缩放永远吸附到整格（自由摆放已取消，设置里不再有这个开关）。 */
    val grid: Boolean get() = true

    /**
     * 首页**卡片**的圆角（dp）。
     *
     * 默认 22 就是改造后一直用的那档；卡片实际圆角还会被「短边的一半」夹住（见
     * `HomeTileItem`），所以滑到顶时任何尺寸的卡片都圆成饼。滑到 0 就完全方正。
     *
     * **只管卡片**：编辑态那条悬浮条恒为全圆（胶囊），不吃这个值（见 `HomeAddTileOrb`）。
     * 改完记得叫一次 [notifyChanged]——这里只写盘，首页靠 `HomeLayoutRevision` 重读。
     */
    var cardCorner: Int
        get() = store.getInt(KEY_CARD_CORNER, CARD_CORNER_DEFAULT)
            .coerceIn(CARD_CORNER_MIN, CARD_CORNER_MAX)
        set(value) = store.putInt(KEY_CARD_CORNER, value.coerceIn(CARD_CORNER_MIN, CARD_CORNER_MAX))

    /**
     * 主页卡片材质：false = 毛玻璃，true = 液态玻璃（**默认开**，2026-11 按维护者要求改）。
     *
     * 键 `home_liquid_card` 与 `SettingsStore.homeLiquidGlass` 是**同一个**：那边是真正在用的
     * 那个访问器（首页与设置面板都读它），这里只是个同键的口子，两边的默认值必须一致，
     * 否则「谁先读到」就决定了首启是玻璃还是毛玻璃。
     */
    var liquidCard: Boolean
        get() = store.getBoolean(KEY_LIQUID_CARD, true)
        set(value) = store.putBoolean(KEY_LIQUID_CARD, value)


    /**
     * 主页背景样式：**主题底色**（[BACKGROUND_SOLID]）/ **主题色渐变**（[BACKGROUND_GRADIENT]）/
     * **自定义图片**（[BACKGROUND_IMAGE]，图本体存在 [backgroundImage]）。
     *
     * 老存档只存过 `home_background_image`：这个键没有值时按「有图就是图片、没图就是主题底色」推断，
     * 不用迁移。存档里写着图片但图被清掉了（手滑 / 老版本）也当主题底色，不留一片空白。
     */
    var backgroundMode: String
        get() {
            val saved = store.getString(KEY_BACKGROUND_MODE) ?: return if (backgroundImage.isNotEmpty()) {
                BACKGROUND_IMAGE
            } else {
                BACKGROUND_SOLID
            }
            if (saved == BACKGROUND_IMAGE && backgroundImage.isEmpty()) return BACKGROUND_SOLID
            return saved
        }
        set(value) = store.putString(KEY_BACKGROUND_MODE, value)

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
        /** 背景样式：纯主题底色。 */
        const val BACKGROUND_SOLID = "solid"

        /** 背景样式：主题色由浅到深铺满整页（不铺纯主题色，见 `HomeScreen` 里那段渐变说明）。 */
        const val BACKGROUND_GRADIENT = "gradient"

        /** 背景样式：自定义图片，图本体在 [backgroundImage]。 */
        const val BACKGROUND_IMAGE = "image"

        const val BACKGROUND_BLUR_MIN = 0
        const val BACKGROUND_BLUR_MAX = 10
        const val BACKGROUND_OPACITY_MIN = 20
        const val BACKGROUND_OPACITY_MAX = 100

        /**
         * 卡片圆角的可调范围与默认值（dp）。22 = 改造后一直用的那档。
         *
         * 上限取 40：卡片实际圆角还会被「短边的一半」截断，所以拖到顶时**任何尺寸的卡片都圆成饼**
         * （常见 1×1 约 74dp，半径 37dp 即正圆）。默认 22 在这条截断之内，升级后外观不变。
         */
        const val CARD_CORNER_MIN = 0
        const val CARD_CORNER_MAX = 40
        const val CARD_CORNER_DEFAULT = 22

        // 注意键名：`SettingsStore.homeLayout` 也用「首页布局」这个意思，但存的是**模式**
        // （classic / widgets / cards）落在 `home_layout` 上；这里存的是卡片组那一整页的分组
        // 与格位快照，必须换个键，否则两边会互相覆盖（合并 dev 时踩到过）。
        private const val KEY_LAYOUT = "home_cards_layout"

/**
 * dev 上那套「小组件」布局的键（`SettingsStore.homeWidgets` 同一个）。只**读**：
 * 升级时把用户摆好的排版迁成卡片组（见 `load` 里的 `migrateFromWidgetLayout`）。
 */
private const val KEY_LEGACY_WIDGETS = "home_widgets"
        /** 旧的网格开关：只在读取时用来把自由摆放的存档取整一次。 */
        private const val KEY_LEGACY_GRID = "home_grid"
private const val KEY_CARD_CORNER = "home_card_corner"
        private const val KEY_LIQUID_CARD = "home_liquid_card"
        private const val KEY_BACKGROUND_MODE = "home_background_mode"
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
