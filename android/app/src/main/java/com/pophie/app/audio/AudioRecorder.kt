package com.pophie.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder(
    private val context: Context,
    private val sampleRate: Int = 16000,
) {
    private var recorder: AudioRecord? = null
    private var recording = false
    private val buffer = ByteArrayOutputStream()

    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return false

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (recorder?.state != AudioRecord.STATE_INITIALIZED) return false

        buffer.reset()
        recording = true
        recorder?.startRecording()

        Thread {
            val data = ByteArray(minBuf)
            while (recording) {
                val read = recorder?.read(data, 0, data.size) ?: 0
                if (read > 0) buffer.write(data, 0, read)
            }
        }.start()
        return true
    }

    fun stop(): ByteArray {
        recording = false
        recorder?.apply {
            try {
                stop()
            } catch (_: Exception) {
            }
            release()
        }
        recorder = null
        AudioRouteHelper.restoreAfterRecording(context.applicationContext)
        return pcmToWav(buffer.toByteArray(), sampleRate)
    }

    companion object {
        fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val channels = 1
            val bits = 16
            val byteRate = sampleRate * channels * bits / 8
            val totalDataLen = pcm.size + 36
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * bits / 8).toShort())
            header.putShort(bits.toShort())
            header.put("data".toByteArray())
            header.putInt(pcm.size)
            return header.array() + pcm
        }
    }
}
