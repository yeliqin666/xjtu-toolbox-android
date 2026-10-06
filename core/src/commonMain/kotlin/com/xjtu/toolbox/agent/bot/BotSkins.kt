package com.xjtu.toolbox.agent.bot

/**
 * 屁岱形象的形状与颜色目录，移植自 bloub 的 `skins.ts`。
 *
 * 与 [BOT_STATES] 里的动画轮廓是**两个来源**，且这是刻意的：动画状态必须忠实于
 * 参考视频（逐帧实测值，不许手改），而基础形状/颜色是用户的选择，按原定制器网格
 * 解析构造。所以两处形状都叫「轮廓」，却不要互相校准。
 */

/* ------------------------------------------------------------------ 形状 */

/** 形状种类。id 用英文短名（存储 key），标签在 UI 层给中文。 */
class BotShapeDef(
    val id: String,
    val label: String,
    /** 径向轮廓 r(theta)，[PROFILE_SAMPLES] 个采样，最大半径已归一到约 1。 */
    val radii: DoubleArray,
)

private val ANGLES = DoubleArray(PROFILE_SAMPLES) { it.toDouble() / PROFILE_SAMPLES * TAU }

/** 鹅卵石：被两个低次谐波揉过的圆，不规则但光滑。 */
private val PEBBLE = normalizeRadii(
    DoubleArray(PROFILE_SAMPLES) { i ->
        val a = ANGLES[i]
        1.0 + 0.075 * kotlin.math.cos(2 * a + 0.5) + 0.035 * kotlin.math.cos(3 * a + 2.1)
    },
    1.02,
)

/** 云朵：几家鼓包的并集，下宽、上两瓣。 */
private val CLOUD = normalizeRadii(
    unionOfCirclesProfile(
        listOf(
            Disc(-0.44, 0.2, 0.54),
            Disc(0.46, 0.2, 0.5),
            Disc(0.02, 0.3, 0.6),
            Disc(-0.24, -0.3, 0.48),
            Disc(0.3, -0.24, 0.44),
        )
    ),
    1.02,
)

/** 水滴：下方大圆，上方收成尖。 */
private val DROPLET = normalizeRadii(
    profileFromPolygon(hullOfCircles(0.0, 0.28, 0.66, 0.0, -0.96, 0.05), 0.0, 0.0),
    1.04,
)

/** 横躺胶囊：两个并排圆的外包络。 */
private val CAPSULE = profileFromPolygon(hullOfCircles(-0.42, 0.0, 0.62, 0.42, 0.0, 0.62), 0.0, 0.0)

val BOT_SHAPES: List<BotShapeDef> = listOf(
    BotShapeDef("cercle", "圆形", DoubleArray(PROFILE_SAMPLES) { 1.0 }),
    BotShapeDef("galet", "鹅卵石", PEBBLE),
    // 1.15 而非 1.02：超椭圆的最大半径在对角线上，按它归一会让形状看着比圆小。
    BotShapeDef("squircle", "方圆", normalizeRadii(superellipseProfile(4.2), 1.15)),
    BotShapeDef("capsule", "胶囊", CAPSULE),
    // -90°：尖角朝屏幕上方（y 向下）
    BotShapeDef("triangle", "三角", regularPolygonProfile(3, 1.12, 0.34, -90.0)),
    // 0°：左右各一个尖角，上下边是平的
    BotShapeDef("hexagone", "六边形", regularPolygonProfile(6, 1.04, 0.26, 0.0)),
    BotShapeDef("nuage", "云朵", CLOUD),
    BotShapeDef("goutte", "水滴", DROPLET),
)

private val SHAPE_BY_ID: Map<String, BotShapeDef> = BOT_SHAPES.associateBy { it.id }

/** 默认圆形：与移植前的底栏形象一致，换形状是用户主动的选择。 */
const val DEFAULT_SHAPE_ID = "cercle"

fun botShapeById(id: String?): BotShapeDef? = SHAPE_BY_ID[id]

/* ------------------------------------------------------------------ 颜色 */

class BotColorDef(
    val id: String,
    val label: String,
    /** ARGB。null = 跟随主题（取底栏前景色，深浅色都自动有对比度）。 */
    val argb: Long?,
)

/**
 * 调色板沿用 bloub 定制器。头一项 [BOT_COLOR_AUTO] 是**本项目独有**的：
 * 底栏底色随主题走，写死一个墨色会在深色模式的深底栏上糊掉，所以默认跟随主题，
 * 颜色是用户主动覆盖时才生效。其余 12 色即原版调色板。
 */
const val BOT_COLOR_AUTO = "auto"

val BOT_COLORS: List<BotColorDef> = listOf(
    BotColorDef(BOT_COLOR_AUTO, "跟随主题", null),
    BotColorDef("encre", "墨", 0xFF0A0A0C),
    BotColorDef("brun", "棕", 0xFF8B5E3C),
    BotColorDef("rouge", "红", 0xFFE8483F),
    BotColorDef("orange", "橙", 0xFFF08A24),
    BotColorDef("ambre", "琥珀", 0xFFF0B429),
    BotColorDef("vert", "绿", 0xFF3ECF8E),
    BotColorDef("turquoise", "青绿", 0xFF2FBFA0),
    BotColorDef("bleu", "蓝", 0xFF3B93F0),
    BotColorDef("violet", "紫", 0xFF8B5CF6),
    BotColorDef("rose", "玫红", 0xFFE152B0),
    BotColorDef("gris", "灰", 0xFFA3A3A3),
    BotColorDef("creme", "米白", 0xFFF1EFE9),
)

private val COLOR_BY_ID: Map<String, BotColorDef> = BOT_COLORS.associateBy { it.id }

const val DEFAULT_COLOR_ID = BOT_COLOR_AUTO

fun botColorById(id: String?): BotColorDef? = COLOR_BY_ID[id]
