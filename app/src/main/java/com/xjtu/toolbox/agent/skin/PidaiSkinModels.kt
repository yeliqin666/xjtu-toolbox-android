package com.xjtu.toolbox.agent.skin

import com.xjtu.toolbox.agent.bot.SkinOutline
import com.xjtu.toolbox.agent.bot.SkinTransform
import java.time.DayOfWeek

data class PidaiSkinManifest(
    val formatVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val renderer: String,
)

/**
 * 颜色槽。皮肤可以写死颜色，也可以让某一层跟随底栏主题的墨色/背景色，
 * 这样单色皮肤在深浅色模式下都还认得出来。
 */
sealed interface PidaiPaint {
    /** 不绘制这一通道。 */
    object None : PidaiPaint

    /** 跟随主题前景色。 */
    object Ink : PidaiPaint

    /** 跟随主题背景色；用来在身体上挖洞（眼睛、镂空）。 */
    object Paper : PidaiPaint

    /** 0xAARRGGBB。 */
    data class Solid(val argb: Long) : PidaiPaint
}

/**
 * 一个可被引用的绘制单元：自由矢量轮廓，或一张随包携带的位图。
 * 位图让「把自己的照片做成屁岱」这种用法可以直接落地。
 */
class PidaiShape(
    val id: String,
    val outline: SkinOutline?,
    val imageSrc: String?,
    val imageW: Double = 0.0,
    val imageH: Double = 0.0,
    val fill: PidaiPaint = PidaiPaint.Ink,
    val stroke: PidaiPaint = PidaiPaint.None,
    val strokeWidth: Double = 0.0,
    val evenOdd: Boolean = false,
    val cap: String = "round",
    val join: String = "round",
) {
    val isImage: Boolean get() = imageSrc != null
}

/**
 * 关键帧里的一层。数组下标即 z 序（靠前的先画）；[key] 用于在关键帧之间认人，
 * 避免动作切换时把第 3 片壳片配对到第 3 根绒毛上。
 */
class PidaiLayer(
    val key: String,
    val shape: PidaiShape,
    val cx: Double = 0.0,
    val cy: Double = 0.0,
    val sx: Double = 1.0,
    val sy: Double = 1.0,
    val rot: Double = 0.0,
    val pivotX: Double = 0.0,
    val pivotY: Double = 0.0,
    val alpha: Double = 1.0,
    val fill: PidaiPaint? = null,
    val stroke: PidaiPaint? = null,
    val strokeWidth: Double? = null,
    /** v1 皮肤：先旋转再在屏幕轴压扁，与旧渲染同序。 */
    val radial: Boolean = false,
)

class PidaiMotionFrame(
    val t: Double,
    val ease: String = "linear",
    val layers: List<PidaiLayer> = emptyList(),
)

class PidaiMotionAction(
    val id: String,
    val duration: Double,
    val loop: Boolean,
    val returnTo: String?,
    val frames: List<PidaiMotionFrame>,
)

data class PidaiTransition(
    val from: String,
    val to: String,
    val duration: Double,
    val ease: String,
)

/** 播放器采样出的一笔绘制。几何已插值完毕，变换还没有落到路径上。 */
class PidaiDraw(
    val outline: SkinOutline?,
    val imageSrc: String?,
    val imageW: Double,
    val imageH: Double,
    val transform: SkinTransform,
    val fill: PidaiPaint,
    val stroke: PidaiPaint,
    val strokeWidth: Double,
    val evenOdd: Boolean,
    val cap: String,
    val join: String,
    val alpha: Double,
)

data class PidaiMotion(
    val colorArgb: Long?,
    val actions: Map<String, PidaiMotionAction>,
    val bindings: Map<String, String>,
    val transitions: List<PidaiTransition>,
) {
    fun actionFor(beat: String): PidaiMotionAction {
        val candidates = buildList {
            bindings[beat]?.let(::add)
            when (beat) {
                "rest" -> add("idle")
                "idle" -> addAll(listOf("wink", "idle"))
                "thinking" -> addAll(listOf("thinking", "orbit", "idle"))
                "alert" -> addAll(listOf("alert", "notify", "idle"))
                "tap" -> addAll(listOf("tap", "comet", "idle"))
            }
        }
        return candidates.firstNotNullOfOrNull(actions::get) ?: actions.values.first()
    }

    fun transitionDuration(from: String?, to: String): Double =
        transitions.firstOrNull { it.from == from && it.to == to }?.duration ?: 0.32
}

data class PidaiChatterLine(
    val id: String,
    val text: String,
    val hours: IntRange? = null,
    val months: IntRange? = null,
    val weekdays: Set<DayOfWeek>? = null,
    val weight: Double = 1.0,
    val action: String? = null,
)

data class PidaiPersona(
    val prompt: String = "",
    val catchphrases: List<String> = emptyList(),
    val chatterMix: Double = 0.4,
    val chatter: List<PidaiChatterLine> = emptyList(),
    /**
     * 皮肤想让助手改叫的名字，覆盖用户在设置里填的名字（皮肤在时"覆盖"，不是"补充"）。
     * null/空 = 不改名，沿用用户设置。最终显示前还会经 `sanitizeAgentTitle` 折叠空白、
     * 截到 12 字——这里存的是原始值，和 `AgentConfig.assistantName` 的处理方式一致。
     */
    val displayName: String? = null,
)

data class PidaiSkin(
    val manifest: PidaiSkinManifest,
    val motion: PidaiMotion,
    val persona: PidaiPersona?,
    /** 已校验的原始文件，用于原样落盘和再次加载；含被引用的位图。 */
    val files: Map<String, ByteArray>,
    /** 载荷字节的短哈希。作者改了 motion.json 却没动版本号时，缓存也必须失效。 */
    val contentHash: String = "",
) {
    val cacheKey: String get() = "${manifest.id}@${manifest.version}#$contentHash"

    /** 包内位图，按 motion.json 里的 src 路径索引。 */
    val images: Map<String, ByteArray>
        get() = files.filterKeys { it.startsWith("images/") }
}

class PidaiSkinFormatException(message: String) : IllegalArgumentException(message)
