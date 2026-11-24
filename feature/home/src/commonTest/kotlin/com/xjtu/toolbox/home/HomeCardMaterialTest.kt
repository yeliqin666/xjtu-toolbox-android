package com.xjtu.toolbox.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCardMaterialTest {

    @Test
    fun defaultBackgroundUsesSolidWhite() {
        assertEquals(HomeCardMaterial.Solid, homeCardMaterial(hasWallpaper = false, wallpaperHasDetail = false, refraction = true))
        assertEquals(HomeCardMaterial.Solid, homeCardMaterial(hasWallpaper = false, wallpaperHasDetail = false, refraction = false))
    }

    @Test
    fun wallpaperWithDetailRefractsWhenEnabled() {
        assertEquals(HomeCardMaterial.Refraction, homeCardMaterial(hasWallpaper = true, wallpaperHasDetail = true, refraction = true))
        assertEquals(HomeCardMaterial.FrostedGlass, homeCardMaterial(hasWallpaper = true, wallpaperHasDetail = true, refraction = false))
    }

    @Test
    fun blurredOutWallpaperSkipsSampling() {
        assertEquals(HomeCardMaterial.ClearGlass, homeCardMaterial(hasWallpaper = true, wallpaperHasDetail = false, refraction = true))
        assertEquals(HomeCardMaterial.ClearGlass, homeCardMaterial(hasWallpaper = true, wallpaperHasDetail = false, refraction = false))
    }
}
