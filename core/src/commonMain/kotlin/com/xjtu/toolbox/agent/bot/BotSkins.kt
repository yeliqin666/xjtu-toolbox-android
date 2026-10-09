package com.xjtu.toolbox.agent.bot

/**
 * 经典屁岱的身体轮廓与颜色目录，移植自 bloub 的 `skins.ts`。
 *
 * 静息身体固定是云朵；思考、彗星这些动画状态的轮廓是参考视频逐帧实测的，在 [BOT_STATES] 里，
 * 两处不要互相校准。
 */

/** 云朵：几个鼓包的并集，下宽、上两瓣。径向轮廓 r(theta)，[PROFILE_SAMPLES] 个采样，峰值归一到约 1。 */
val CLOUD_RADII: DoubleArray = normalizeRadii(
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
