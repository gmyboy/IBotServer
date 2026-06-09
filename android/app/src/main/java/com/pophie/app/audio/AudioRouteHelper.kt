package com.pophie.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 蓝牙耳机场景：录音后系统可能停留在 SCO/通话模式，导致 MediaPlayer/TTS 无声。
 * 播放前恢复 NORMAL 模式、等待 SCO 断开，并用 USAGE_MEDIA 走 A2DP 媒体通道。
 */
object AudioRouteHelper {

  private const val TAG = "PophieAudio"
  private const val SCO_DISCONNECT_TIMEOUT_MS = 900L

  /** MEDIA 比 ASSISTANT 更容易在蓝牙耳机上出声（跟随媒体音量）。 */
  val playbackAttributes: AudioAttributes =
      AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()

  private var focusRequest: AudioFocusRequest? = null
  @Suppress("DEPRECATION")
  private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

  private fun audioManager(context: Context): AudioManager =
      context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  /** 录音结束后调用，退出蓝牙 SCO / 通话模式。 */
  fun restoreAfterRecording(context: Context) {
    val am = audioManager(context)
    try {
      if (am.isBluetoothScoOn) {
        am.stopBluetoothSco()
        am.isBluetoothScoOn = false
      }
    } catch (_: Exception) {
    }
    am.mode = AudioManager.MODE_NORMAL
    am.isSpeakerphoneOn = false
  }

  /** 播放 TTS / 机器人语音前调用（挂起，等待蓝牙 SCO 完全断开）。 */
  suspend fun prepareForPlayback(context: Context) {
    withContext(Dispatchers.Main) {
      val am = audioManager(context)
      am.isSpeakerphoneOn = false

      val scoActive = am.isBluetoothScoOn
      val wrongMode = am.mode != AudioManager.MODE_NORMAL
      if (scoActive || wrongMode) {
        Log.d(TAG, "prepareForPlayback: sco=$scoActive mode=${am.mode}")
        awaitScoTeardown(context, am)
      }

      am.mode = AudioManager.MODE_NORMAL
      am.isBluetoothScoOn = false
      Log.d(
          TAG,
          "ready: mode=${am.mode} sco=${am.isBluetoothScoOn} " +
              "a2dp=${am.isBluetoothA2dpOn} musicVol=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}",
      )
    }
  }

  private suspend fun awaitScoTeardown(context: Context, am: AudioManager) {
    val finished = withTimeoutOrNull(SCO_DISCONNECT_TIMEOUT_MS) {
      suspendCancellableCoroutine { cont ->
        val receiver =
            object : BroadcastReceiver() {
              override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                  Log.d(TAG, "SCO disconnected")
                  safeUnregister(ctx, this)
                  if (cont.isActive) cont.resume(Unit)
                }
              }
            }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
          @Suppress("DEPRECATION")
          context.registerReceiver(receiver, filter)
        }

        cont.invokeOnCancellation { safeUnregister(context, receiver) }

        try {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
        } catch (_: Exception) {
        }
        am.mode = AudioManager.MODE_NORMAL

        if (!am.isBluetoothScoOn) {
          safeUnregister(context, receiver)
          if (cont.isActive) cont.resume(Unit)
        }
      }
    }
    if (finished == null) {
      Log.w(TAG, "SCO teardown timed out, continuing playback anyway")
    }
  }

  private fun safeUnregister(context: Context, receiver: BroadcastReceiver) {
    try {
      context.unregisterReceiver(receiver)
    } catch (_: Exception) {
    }
  }

  fun requestPlaybackFocus(context: Context): Boolean {
    val am = audioManager(context)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val req =
          AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
              .setAudioAttributes(playbackAttributes)
              .setOnAudioFocusChangeListener { }
              .build()
      focusRequest = req
      am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } else {
      @Suppress("DEPRECATION")
      val listener = AudioManager.OnAudioFocusChangeListener { }
      legacyFocusListener = listener
      @Suppress("DEPRECATION")
      am.requestAudioFocus(
          listener,
          AudioManager.STREAM_MUSIC,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
      ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
  }

  fun releasePlaybackFocus(context: Context) {
    val am = audioManager(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      focusRequest?.let { am.abandonAudioFocusRequest(it) }
      focusRequest = null
    } else {
      @Suppress("DEPRECATION")
      legacyFocusListener?.let { am.abandonAudioFocus(it) }
      legacyFocusListener = null
    }
  }

  /** 已连接蓝牙耳机时，优先选用 A2DP / BLE 输出，避免只从手机扬声器出声。 */
  fun preferredBluetoothOutput(context: Context): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val am = audioManager(context)
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { device ->
      device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
          device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
          device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
    }
  }
}
