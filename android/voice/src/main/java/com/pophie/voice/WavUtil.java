package com.pophie.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PCM/WAV 工具：移植自原 AudioRecorder.pcmToWav，并补充 PCM16↔float 转换。 */
public final class WavUtil {

    private WavUtil() {}

    /** 16-bit 单声道 PCM 字节 → WAV（44 字节头 + data）。 */
    public static byte[] pcmToWav(byte[] pcm, int sampleRate) {
        int channels = 1;
        int bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        int totalDataLen = pcm.length + 36;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(totalDataLen);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) (channels * bits / 8));
        header.putShort((short) bits);
        header.put("data".getBytes());
        header.putInt(pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(header.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    /** short[] PCM16 → WAV。 */
    public static byte[] shortToWav(short[] pcm, int sampleRate) {
        return pcmToWav(shortToBytes(pcm), sampleRate);
    }

    /** short[] → 小端字节。 */
    public static byte[] shortToBytes(short[] pcm) {
        ByteBuffer bb = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) bb.putShort(s);
        return bb.array();
    }

    /** 小端字节 → short[]。 */
    public static short[] bytesToShort(byte[] data, int len) {
        ByteBuffer bb = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[len / 2];
        for (int i = 0; i < out.length; i++) out[i] = bb.getShort();
        return out;
    }

    /** PCM16 → float [-1,1]（sherpa-onnx 需要 float 输入）。 */
    public static float[] pcm16ToFloat(short[] pcm) {
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = pcm[i] / 32768f;
        return out;
    }

    /** float [-1,1] → PCM16（带裁剪）。 */
    public static short[] floatToPcm16(float[] f) {
        short[] out = new short[f.length];
        for (int i = 0; i < f.length; i++) {
            float v = f[i] * 32768f;
            if (v > 32767f) v = 32767f;
            if (v < -32768f) v = -32768f;
            out[i] = (short) v;
        }
        return out;
    }

    /** 拼接多个 short[]。 */
    public static short[] concat(java.util.List<short[]> chunks) {
        int total = 0;
        for (short[] c : chunks) total += c.length;
        short[] out = new short[total];
        int off = 0;
        for (short[] c : chunks) {
            System.arraycopy(c, 0, out, off, c.length);
            off += c.length;
        }
        return out;
    }

    /** 帧的 RMS 能量（用于能量门/SNR 估计），输入 PCM16。 */
    public static double rms(short[] pcm) {
        if (pcm.length == 0) return 0;
        double sum = 0;
        for (short s : pcm) sum += (double) s * s;
        return Math.sqrt(sum / pcm.length);
    }

    /** ByteArrayOutputStream 累计的 PCM16 字节 → WAV。 */
    public static byte[] streamToWav(ByteArrayOutputStream pcm, int sampleRate) {
        return pcmToWav(pcm.toByteArray(), sampleRate);
    }
}
