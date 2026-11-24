package com.xjtu.toolbox.home

/** 首页卡片材质。 */
internal enum class HomeCardMaterial {
    /** 默认背景：白色半透明实底 + 细描边，不采样。 */
    Solid,

    /** 壁纸已糊平：透明玻璃，只有柔光与描边，不采样。 */
    ClearGlass,

    /** 壁纸有细节：贴共用的预糊背景，不折射。 */
    FrostedGlass,

    /** 完整液态材质：活力、折射、轮廓高光、环境光投影（每张卡一个离屏层，模糊共用预糊背景）。 */
    Refraction,
}

/**
 * [hasWallpaper]：背景是自定义图片；[wallpaperHasDetail]：图没被「模糊程度」糊平；
 * [refraction]：用户的「液态折射」开关。
 */
internal fun homeCardMaterial(hasWallpaper: Boolean, wallpaperHasDetail: Boolean, refraction: Boolean): HomeCardMaterial =
    when {
        !hasWallpaper -> HomeCardMaterial.Solid
        !wallpaperHasDetail -> HomeCardMaterial.ClearGlass
        refraction -> HomeCardMaterial.Refraction
        else -> HomeCardMaterial.FrostedGlass
    }
