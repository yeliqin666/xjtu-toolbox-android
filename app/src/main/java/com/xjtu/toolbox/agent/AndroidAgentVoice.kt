package com.xjtu.toolbox.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 语音输入在这一端的实现：AudioRecord 录 16k 单声道 PCM，停下后整段交给 [DeepSeekAsr]。 */
@Composable
internal fun rememberAndroidAgentVoiceInput(): AgentVoiceInput {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { AndroidAgentVoice(context.applicationContext, scope) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.resumePending() else voice.dropPending("没有麦克风权限，无法语音输入")
    }
    voice.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    return voice
}

private class AndroidAgentVoice(private val context: Context, private val scope: CoroutineScope) : AgentVoiceInput {
    override var phase by mutableStateOf(VoicePhase.IDLE)
        private set

    var requestPermission: () -> Unit = {}
    private var pending: Pair<String, (String) -> Unit>? = null
    private var recording: Job? = null
    @Volatile private var stopRequested = false

    override fun toggle(apiKey: String, onText: (String) -> Unit) {
        when (phase) {
            VoicePhase.RECORDING -> stopRequested = true
            VoicePhase.TRANSCRIBING -> Unit
            VoicePhase.IDLE -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    start(apiKey, onText)
                } else {
                    pending = apiKey to onText
                    requestPermission()
                }
            }
        }
    }

    fun resumePending() {
        pending?.let { (key, onText) -> start(key, onText) }
        pending = null
    }

    fun dropPending(message: String) {
        pending = null
        toast(message)
    }

    @SuppressLint("MissingPermission") // 调用前已查过权限
    private fun start(apiKey: String, onText: (String) -> Unit) {
        if (recording?.isActive == true) return
        stopRequested = false
        phase = VoicePhase.RECORDING
        recording = scope.launch {
            val pcm = record()
            if (pcm == null) {
                phase = VoicePhase.IDLE
                toast("麦克风被占用或不可用")
                return@launch
            }
            // 不到半秒多半是误触
            if (pcm.size < DeepSeekAsr.SAMPLE_RATE) {
                phase = VoicePhase.IDLE
                toast("说话时间太短")
                return@launch
            }
            phase = VoicePhase.TRANSCRIBING
            DeepSeekAsr(apiKey).transcribe(DeepSeekAsr.wav(pcm))
                .onSuccess { text -> if (text.isBlank()) toast("没听清，再说一次") else onText(text) }
                .onFailure { toast(it.message ?: "识别失败") }
            phase = VoicePhase.IDLE
        }
    }

    /** 录到再点一下或到时长上限；打不开麦克风返回 null。 */
    @SuppressLint("MissingPermission")
    private suspend fun record(): ByteArray? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(DeepSeekAsr.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext null
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, DeepSeekAsr.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2,
            )
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return@withContext null
        val out = ByteArrayOutputStream()
        val maxBytes = DeepSeekAsr.SAMPLE_RATE * 2 * DeepSeekAsr.MAX_SECONDS
        val buffer = ByteArray(minBuffer)
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
            while (isActive && !stopRequested && out.size() < maxBytes) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        out.toByteArray()
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
