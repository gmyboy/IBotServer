package com.pophie.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.pophie.app.data.ApiClient
import com.pophie.app.data.model.AudioPayload
import com.pophie.app.data.model.VoiceProsody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * 播放机器人语音：优先 WebSocket 流式 TTS（/api/tts/stream），
 * 兼容 chat 内嵌的完整 audio 载荷。
 */
class RobotSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun speak(
        text: String,
        serverAudio: AudioPayload? = null,
        voice: VoiceProsody? = null,
        voiceId: String = ApiClient.getVoiceId(appContext),
        onStart: () -> Unit = {},
        onComplete: () -> Unit = {},
    ) {
        if (text.isBlank()) return
        stop()

        if (serverAudio != null && serverAudio.data.isNotBlank()) {
            Log.d(TAG, "speak: inline server audio ${serverAudio.format}")
            playInlineAudio(serverAudio, onStart, onComplete)
            return
        }

        streamAndPlay(text, voice, voiceId, onStart, onComplete)
    }

    private suspend fun streamAndPlay(
        text: String,
        voice: VoiceProsody?,
        voiceId: String,
        onStart: () -> Unit,
        onComplete: () -> Unit,
    ) {
        try {
            withContext(Dispatchers.Main) {
                AudioRouteHelper.prepareForPlayback(appContext)
                AudioRouteHelper.requestPlaybackFocus(appContext)
            }
            withContext(Dispatchers.IO) {
                val client = TtsStreamClient(ApiClient.getBaseUrl(appContext))
                var pcmTrack: AudioTrack? = null
                var playStarted = false
                val mp3Buffer = ByteArrayOutputStream()
                var streamFormat = "pcm"
                var pcmSampleRate = 22050
                var pcmBytesWritten = 0

                val result = client.stream(
                    text = text,
                    voice = voice,
                    voiceId = voiceId,
                    onMeta = { meta ->
                        streamFormat = meta.format
                        pcmSampleRate = meta.sampleRate
                        if (meta.format.equals("pcm", ignoreCase = true)) {
                            pcmTrack = createPcmTrack(meta.sampleRate)
                        }
                    },
                    onChunk = { chunk ->
                        coroutineContext.ensureActive()
                        if (pcmTrack != null) {
                            if (!playStarted) {
                                playStarted = true
                                mainHandler.post { onStart() }
                                pcmTrack?.play()
                            }
                            pcmTrack?.write(chunk, 0, chunk.size)
                            pcmBytesWritten += chunk.size
                        } else {
                            mp3Buffer.write(chunk)
                        }
                    },
                )
                TtsStreamClient.logMetric(result.firstPacketMs)

                if (pcmTrack != null) {
                    val track = pcmTrack
                    val bytesWritten = pcmBytesWritten
                    val sampleRate = pcmSampleRate
                    withContext(Dispatchers.Main) {
                        finishPcmTrack(track, bytesWritten, sampleRate, onComplete)
                    }
                    return@withContext
                }

                val bytes = mp3Buffer.toByteArray()
                if (bytes.isEmpty()) {
                    withContext(Dispatchers.Main) { onComplete() }
                    return@withContext
                }
                val ext = if (streamFormat.equals("mp3", ignoreCase = true)) ".mp3" else ".wav"
                val file = File(appContext.cacheDir, "robot_stream_${System.currentTimeMillis()}$ext")
                file.writeBytes(bytes)
                withContext(Dispatchers.Main) {
                    playFile(file, onStart, onComplete)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stream TTS failed: ${e.message}")
            withContext(Dispatchers.Main) {
                AudioRouteHelper.releasePlaybackFocus(appContext)
                onComplete()
            }
        }
    }

    private fun createPcmTrack(sampleRate: Int): AudioTrack {
        val channel = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channel, encoding)
        val attrs = AudioRouteHelper.playbackAttributes
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channel)
                        .setEncoding(encoding)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                attrs,
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channel)
                    .setEncoding(encoding)
                    .build(),
                minBuf * 4,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.also { audioTrack = it }
    }

    private suspend fun finishPcmTrack(
        track: AudioTrack?,
        bytesWritten: Int,
        sampleRate: Int,
        onComplete: () -> Unit,
    ) {
        if (track == null) {
            AudioRouteHelper.releasePlaybackFocus(appContext)
            onComplete()
            return
        }
        val playbackMs = if (bytesWritten > 0 && sampleRate > 0) {
            bytesWritten * 1000L / (sampleRate * 2)
        } else {
            0L
        }
        val headPos = try {
            track.playbackHeadPosition
        } catch (_: Exception) {
            0
        }
        val pendingMs = if (playbackMs > 0) {
            val playedMs = headPos * 1000L / sampleRate
            (playbackMs - playedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        if (pendingMs > 0) {
            kotlinx.coroutines.delay(pendingMs + 80L)
        }
        track.apply {
            stop()
            release()
        }
        if (audioTrack === track) {
            audioTrack = null
        }
        AudioRouteHelper.releasePlaybackFocus(appContext)
        onComplete()
    }

    private suspend fun playInlineAudio(
        audio: AudioPayload,
        onStart: () -> Unit,
        onComplete: () -> Unit,
    ) {
        withContext(Dispatchers.Main) {
            AudioRouteHelper.prepareForPlayback(appContext)
            AudioRouteHelper.requestPlaybackFocus(appContext)
        }
        onStart()
        playBytes(audio, onComplete)
    }

    private suspend fun playFile(
        file: File,
        onStart: () -> Unit,
        onComplete: () -> Unit,
    ) {
        onStart()
        suspendCancellableCoroutine { cont ->
            try {
                val btOut = AudioRouteHelper.preferredBluetoothOutput(appContext)
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(AudioRouteHelper.playbackAttributes)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && btOut != null) {
                        setPreferredDevice(btOut)
                    }
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        file.delete()
                        AudioRouteHelper.releasePlaybackFocus(appContext)
                        onComplete()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                        file.delete()
                        AudioRouteHelper.releasePlaybackFocus(appContext)
                        onComplete()
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    setOnPreparedListener { start() }
                    prepareAsync()
                }
            } catch (e: Exception) {
                file.delete()
                AudioRouteHelper.releasePlaybackFocus(appContext)
                onComplete()
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation {
                file.delete()
                AudioRouteHelper.releasePlaybackFocus(appContext)
                onComplete()
            }
        }
    }

    private suspend fun playBytes(audio: AudioPayload, onComplete: () -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                val bytes = Base64.decode(audio.data, Base64.DEFAULT)
                if (bytes.isEmpty()) {
                    withContext(Dispatchers.Main) { onComplete() }
                    return@withContext
                }
                val ext = if (audio.format.equals("mp3", ignoreCase = true)) ".mp3" else ".wav"
                val file = File(appContext.cacheDir, "robot_${System.currentTimeMillis()}$ext")
                file.writeBytes(bytes)
                withContext(Dispatchers.Main) {
                    playFile(file, onStart = {}, onComplete = onComplete)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playBytes failed: ${e.message}")
            onComplete()
        }
    }

    fun stop() {
        audioTrack?.apply {
            try {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
            } catch (_: Exception) {
            }
            release()
        }
        audioTrack = null
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
            } catch (_: Exception) {
            }
            release()
        }
        mediaPlayer = null
    }

    fun shutdown() {
        stop()
    }

    companion object {
        private const val TAG = "PophieAudio"
    }
}
