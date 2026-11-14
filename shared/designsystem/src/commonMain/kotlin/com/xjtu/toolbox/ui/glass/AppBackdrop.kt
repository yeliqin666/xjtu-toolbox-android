package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前是不是画在一块玻璃顶栏上（标题下面挂的分段标签、周胶囊这类）。
 *
 * 玻璃顶栏在自己的内容外面提供 true。放在上面的控件读到它，就把自己的底色换成半透明：
 * 一块不透明的托盘贴在玻璃上，四周透、中间不透，看着像补丁。
 */
val LocalOnGlassBar = compositionLocalOf { false }
