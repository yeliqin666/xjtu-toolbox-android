package com.xjtu.toolbox.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Wear 占位 Activity——v4.6 仅建立模块骨架。
 *
 * 后续 PR：
 * - PR-10a 课表 Complication（数据由 :app 通过 Wearable Data Layer 推送）
 * - PR-10b 通知摘要卡片
 * - PR-10c Agent 语音入口（依赖系统授权）
 */
class HelloActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HelloScreen() }
    }
}

@Composable
fun HelloScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "XJTUToolBox Wear")
    }
}

@Preview
@Composable
private fun PreviewHelloScreen() {
    HelloScreen()
}