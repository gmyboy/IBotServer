package com.pophie.voice.source;

/**
 * 外部 PCM 源：上层调用 push() 喂入任意长度 PCM16，内部重切成固定帧长回调。
 * 无麦克风也可用，便于第三方集成 / 离线测试。
 */
public final class ExternalPcmSource implements AudioSource {

    private final int frameSamples;
    private FrameCallback callback;
    private volatile boolean running = false;

    private short[] residual = new short[0];

    public ExternalPcmSource(int frameSamples) {
        this.frameSamples = frameSamples;
    }

    @Override
    public boolean start(FrameCallback callback) {
        this.callback = callback;
        this.residual = new short[0];
        running = true;
        return true;
    }

    /** 上层喂入任意长度 PCM16。 */
    public synchronized void push(short[] pcm) {
        if (!running || callback == null || pcm == null || pcm.length == 0) return;
        short[] buf = new short[residual.length + pcm.length];
        System.arraycopy(residual, 0, buf, 0, residual.length);
        System.arraycopy(pcm, 0, buf, residual.length, pcm.length);

        int off = 0;
        while (buf.length - off >= frameSamples) {
            short[] frame = new short[frameSamples];
            System.arraycopy(buf, off, frame, 0, frameSamples);
            callback.onFrame(frame);
            off += frameSamples;
        }
        int rem = buf.length - off;
        residual = new short[rem];
        System.arraycopy(buf, off, residual, 0, rem);
    }

    @Override
    public void stop() {
        running = false;
        residual = new short[0];
        callback = null;
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int audioSessionId() { return 0; }
}
