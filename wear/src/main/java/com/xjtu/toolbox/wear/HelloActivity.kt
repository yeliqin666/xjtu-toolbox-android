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
 * Wear 占位 Activity——目前只建立模块骨架，承载编译验证。
 *
 * 后续在此基础上加课表 Complication / 通知摘要卡片 / Agent 语音入口等真功能。
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