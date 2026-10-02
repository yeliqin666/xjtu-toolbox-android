package com.xjtu.toolbox.agent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.agent.bot.BOT_COLORS
import com.xjtu.toolbox.agent.bot.DEFAULT_COLOR_ID
import com.xjtu.toolbox.agent.bot.botColorById
import com.xjtu.toolbox.agent.bot.cast.CastCharacter
import com.xjtu.toolbox.agent.bot.cast.castById
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/** 经典屁岱（云朵）的角色 id；其余角色见 [com.xjtu.toolbox.agent.bot.cast.CAST]。 */
const val CLASSIC_ID = "classic"

/**
 * 屁岱形象（角色 + 经典屁岱的颜色）的全局入口。
 *
 * 用 [mutableStateOf] 是为了让底栏能直接跟着设置页的选择重组：底栏常驻，设置页改一下
 * 就要立刻在底栏看到，而不是等下次冷启动。
 *
 * 与账号数据分开存（不挂 [com.xjtu.toolbox.account.AccountContext]）：这是**设备级**的
 * 外观偏好，不是某个学号的业务数据。跟着账号走会让切账号时形象莫名重置。
 */
object PidaiAppearanceHost {
    var characterId by mutableStateOf(CLASSIC_ID)
    var colorId by mutableStateOf(DEFAULT_COLOR_ID)

    /**
     * 朴素图标：不播动画、没有装饰，只显示一个静态图标。[PidaiNavButton] 自己读这个
     * 状态（不是走参数），这样底栏、侧栏两处调用都不用改就能自动生效。
     */
    var plain by mutableStateOf(false)
        private set

    /** 当前角色；null = 经典屁岱。 */
    val cast: CastCharacter? get() = castById(characterId)

    /** 从磁盘读一次并写入 host。幂等。 */
    fun load(context: Context) {
        val saved = PidaiAppearanceStore(context).load()
        characterId = saved.character
        colorId = saved.color
        plain = saved.plain
    }

    /** 选择之后立即落盘 + 更新 host。 */
    fun set(context: Context, character: String? = null, color: String? = null) {
        if (character != null) characterId = character
        if (color != null) colorId = color
        PidaiAppearanceStore(context).save(characterId, colorId, plain)
    }

    /** 「朴素图标」开关：角色仍然保留在存档里，只是暂时不画。 */
    fun setPlain(context: Context, value: Boolean) {
        plain = value
        PidaiAppearanceStore(context).save(characterId, colorId, value)
    }
}

/** 形象偏好的落盘。设备级，与账号无关。 */
class PidaiAppearanceStore(context: Context) {
    private val appContext = context.applicationContext

    private val prefs
        get() = appContext.getSharedPreferences("pidai_appearance", Context.MODE_PRIVATE)

    /**
     * 读出形象，非法/失踪的值一律回落到默认，免得旧版残留把底栏搞成空白。
     * [plain] 旧版本存档里没有这个字段，`getBoolean` 的默认值就是 `false`，
     * 老用户升级上来不会突然变成朴素图标。
     */
    data class Saved(val character: String, val color: String, val plain: Boolean = false)

    fun load(): Saved {
        purgeLegacy()
        val character = prefs.getString("character", CLASSIC_ID)
            ?.takeIf { it == CLASSIC_ID || castById(it) != null }
            ?: CLASSIC_ID
        val color = prefs.getString("color", DEFAULT_COLOR_ID)
            ?.takeIf { id -> BOT_COLORS.any { it.id == id } }
            ?: DEFAULT_COLOR_ID
        return Saved(character, color, prefs.getBoolean("plain", false))
    }

    fun save(character: String, color: String, plain: Boolean) {
        prefs.edit().putString("character", character).putString("color", color).putBoolean("plain", plain).apply()
    }

    /**
     * 清掉已下线功能的残留：外部导入的皮肤包与选中记录、经典屁岱的形状选择
     * （形状已固定为云朵）。
     */
    private fun purgeLegacy() {
        if (prefs.contains("skin") || prefs.contains("shape")) prefs.edit().remove("skin").remove("shape").apply()
        File(appContext.filesDir, "pidai_skins").takeIf { it.exists() }?.deleteRecursively()
    }
}

data class PidaiNavStyle(
    /** null = 经典屁岱。 */
    val cast: CastCharacter?,
    /** 经典屁岱的身体墨色；新角色自带配色，用不到。 */
    val ink: Color,
)

/**
 * 底栏渲染需要的形象参数：角色 + 经典屁岱的墨色。
 *
 * 墨色在「跟随主题」时取 `onSurface`（底栏前景），深色底栏上仍然可见；用户选了具体
 * 颜色才用那个色值。这个取舍是必须的——写死一个墨色在深色模式会直接看不见。
 */
@Composable
fun pidaiNavAppearance(): PidaiNavStyle {
    val color = botColorById(PidaiAppearanceHost.colorId)?.argb
        ?.let { Color(it) }
        ?: MiuixTheme.colorScheme.onSurface
    return PidaiNavStyle(PidaiAppearanceHost.cast, color)
}
