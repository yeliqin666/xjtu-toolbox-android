package com.xjtu.toolbox.termreport

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 背景音乐：CMJ《星星在唱歌（纯音乐）》。 */
private const val SONG_ID = 1803321138L
private const val MAX_VOLUME = 0.6f

/**
 * 网易云外链 302 到 CDN 上带临时签名的 mp3，只能播放时现取、不能存进 HTML。
 * CDN 给的是 http，换成 https 一样能取（App 不放行明文流量）。下架或变 VIP 时跳到 404 页，返回 null。
 */
private suspend fun resolveSongUrl(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL("https://music.163.com/song/media/outer/url?id=$SONG_ID.mp3").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            conn.getHeaderField("Location")
                ?.takeIf { conn.responseCode in 300..399 && "music.126.net" in it && ".mp3" in it }
                ?.replaceFirst("http://", "https://")
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

/** 看总结时循环放背景音乐；[playing] 为 false 或 App 退到后台时暂停，取不到直链就静默不放。 */
@Composable
internal fun BackgroundMusic(playing: Boolean) {
    val player = remember {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            isLooping = true
        }
    }
    var prepared by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }

    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(Unit) {
        val url = resolveSongUrl() ?: return@LaunchedEffect
        runCatching {
            player.setOnPreparedListener { prepared = true }
            player.setDataSource(url)
            player.prepareAsync()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> foreground = false
                Lifecycle.Event.ON_RESUME -> foreground = true
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(prepared, playing, foreground) {
        if (!prepared) return@LaunchedEffect
        runCatching {
            if (playing && foreground) {
                player.setVolume(0f, 0f)
                player.start()
                for (i in 1..20) {
                    delay(70)
                    val v = MAX_VOLUME * i / 20
                    player.setVolume(v, v)
                }
            } else if (player.isPlaying) {
                player.pause()
            }
        }
    }
}
