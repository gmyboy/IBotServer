package com.pophie.voice.server;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 按「回复轮次 + seq」顺序播放服务端 TTS 音频。
 * 服务端每轮 reply 的 seq 从 1 重新计数，客户端须按轮次隔离队列。
 */
public final class ReplyAudioPlayer {

    private static final String TAG = "ReplyAudioPlayer";
    private static final int MAX_ROUNDS = 12;
    private static final long OPEN_EMPTY_TIMEOUT_MS = 12_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private final ArrayDeque<ReplyRound> rounds = new ArrayDeque<>();

    private volatile boolean playing;
    private volatile Listener listener;

    public interface Listener {
        default void onPlayStart(int seq, String text) {}
        default void onPlayEnd(int seq) {}
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void reset() {
        synchronized (lock) {
            rounds.clear();
        }
        scheduleDrain();
    }

    /** WS 异常或队列卡死时，强制关闭队头并继续后续轮次。 */
    public void recover() {
        synchronized (lock) {
            ReplyRound head = rounds.peekFirst();
            if (head != null) {
                head.closed = true;
                forceCompletePending(head);
            }
            trimOverflow();
            Log.w(TAG, "recover, queue=" + rounds.size());
        }
        scheduleDrain();
    }

    /** 新一轮 reply（phase=start）——服务端 seq 将重新从 1 开始。 */
    public void onReplyStart() {
        synchronized (lock) {
            rounds.addLast(new ReplyRound());
            trimOverflow();
            Log.d(TAG, "reply round start, queue=" + rounds.size());
        }
        scheduleDrain();
    }

    /** 本轮 reply 结束（phase=done）——关闭最早未结束的轮次。 */
    public void onReplyDone() {
        synchronized (lock) {
            for (ReplyRound r : rounds) {
                if (!r.closed) {
                    r.closed = true;
                    Log.d(TAG, "reply round done, queue=" + rounds.size());
                    break;
                }
            }
        }
        scheduleDrain();
    }

    public void onTtsMeta(int seq, String format, int sampleRate) {
        if (seq <= 0) return;
        synchronized (lock) {
            PendingUtterance u = targetRoundForTts(seq).ensure(seq);
            u.format = format == null ? "pcm" : format;
            u.sampleRate = sampleRate > 0 ? sampleRate : 22050;
        }
        scheduleDrain();
    }

    public void onTtsChunk(int seq, byte[] pcmOrMp3) {
        if (seq <= 0 || pcmOrMp3 == null || pcmOrMp3.length == 0) return;
        synchronized (lock) {
            try {
                targetRoundForTts(seq).ensure(seq).chunks.write(pcmOrMp3, 0, pcmOrMp3.length);
            } catch (Exception e) {
                Log.w(TAG, "buffer chunk failed seq=" + seq, e);
            }
        }
        scheduleDrain();
    }

    public void onSpeakDone(int seq) {
        if (seq <= 0) return;
        synchronized (lock) {
            ReplyRound round = findRoundWithSeq(seq);
            if (round == null) {
                round = targetRoundForTts(seq);
            }
            round.ensure(seq).complete = true;
        }
        scheduleDrain();
    }

    /** TTS 写入最早未关闭轮次；若已有该 seq 则写回原轮次。 */
    private ReplyRound targetRoundForTts(int seq) {
        ReplyRound existing = findRoundWithSeq(seq);
        if (existing != null) return existing;
        for (ReplyRound r : rounds) {
            if (!r.closed) return r;
        }
        ReplyRound r = new ReplyRound();
        rounds.addLast(r);
        return r;
    }

    private ReplyRound findRoundWithSeq(int seq) {
        for (ReplyRound r : rounds) {
            if (r.pending.containsKey(seq)) return r;
        }
        return null;
    }

    private void trimOverflow() {
        while (rounds.size() > MAX_ROUNDS) {
            ReplyRound dropped = rounds.pollFirst();
            Log.w(TAG, "drop oldest reply round, remaining=" + rounds.size());
            if (dropped == null) break;
        }
    }

    private void scheduleDrain() {
        exec.execute(this::drain);
    }

    private void drain() {
        while (true) {
            PendingUtterance u;
            int seq;
            String fmt;
            int sr;
            byte[] audio;
            synchronized (lock) {
                if (!advanceHeadIfStuck()) {
                    ReplyRound round = rounds.peekFirst();
                    if (round == null) return;
                    u = round.pending.get(round.nextSeq);
                    if (u == null) {
                        if (!round.pending.isEmpty()) {
                            int jump = round.pending.firstKey();
                            if (jump > round.nextSeq) {
                                Log.w(TAG, "skip gap seq " + round.nextSeq + " -> " + jump);
                                round.nextSeq = jump;
                                continue;
                            }
                        }
                        if (round.closed) {
                            if (round.pending.isEmpty()) {
                                rounds.pollFirst();
                                Log.d(TAG, "reply round pruned, remaining=" + rounds.size());
                                continue;
                            }
                            forceCompletePending(round);
                            continue;
                        }
                        return;
                    }
                    if (!u.complete) {
                        if (round.closed) {
                            forceCompletePending(round);
                            continue;
                        }
                        return;
                    }
                    seq = round.nextSeq;
                    fmt = u.format;
                    sr = u.sampleRate;
                    audio = u.chunks.toByteArray();
                    round.pending.remove(round.nextSeq);
                    round.nextSeq++;
                } else {
                    continue;
                }
            }
            if (audio.length == 0) {
                continue;
            }
            playBytes(seq, audio, fmt, sr);
        }
    }

    /** @return true 若已处理队头卡死并应继续循环 */
    private boolean advanceHeadIfStuck() {
        ReplyRound head = rounds.peekFirst();
        if (head == null) return false;
        if (!head.pending.isEmpty() || head.closed) {
            head.openSinceMs = 0;
            return false;
        }
        if (head.openSinceMs == 0) {
            head.openSinceMs = System.currentTimeMillis();
            return false;
        }
        if (System.currentTimeMillis() - head.openSinceMs < OPEN_EMPTY_TIMEOUT_MS) {
            return false;
        }
        Log.w(TAG, "reply round timeout (open empty), force close head");
        head.closed = true;
        rounds.pollFirst();
        return true;
    }

    private static void forceCompletePending(ReplyRound round) {
        if (round.pending.isEmpty()) return;
        int key = round.pending.firstKey();
        PendingUtterance u = round.pending.get(key);
        if (u != null) {
            u.complete = true;
            round.nextSeq = key;
        }
    }

    private void playBytes(int seq, byte[] audio, String format, int sampleRate) {
        playing = true;
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onPlayStart(seq, "");
        });
        try {
            if (format != null && format.equalsIgnoreCase("pcm")) {
                playPcm(audio, sampleRate);
            } else {
                playMp3(audio, format);
            }
        } catch (Exception e) {
            Log.e(TAG, "play failed seq=" + seq, e);
        } finally {
            playing = false;
            main.post(() -> {
                Listener l = listener;
                if (l != null) l.onPlayEnd(seq);
            });
            scheduleDrain();
        }
    }

    private void playPcm(byte[] audio, int sampleRate) throws Exception {
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, audio.length);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        try {
            track.play();
            int offset = 0;
            while (offset < audio.length) {
                int wrote = track.write(audio, offset, Math.min(audio.length - offset, bufSize));
                if (wrote <= 0) break;
                offset += wrote;
            }
            long frames = audio.length / 2L;
            long deadline = System.currentTimeMillis() + frames * 1000L / sampleRate + 800;
            while (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                    && track.getPlaybackHeadPosition() < frames
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        } finally {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
        }
    }

    private void playMp3(byte[] audio, String format) throws Exception {
        String ext = format != null && format.equalsIgnoreCase("mp3") ? ".mp3" : ".wav";
        File f = File.createTempFile("reply_", ext);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(audio);
        }
        final Object done = new Object();
        main.post(() -> {
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(f.getAbsolutePath());
                mp.setOnCompletionListener(player -> {
                    player.release();
                    f.delete();
                    synchronized (done) { done.notifyAll(); }
                });
                mp.setOnErrorListener((player, what, extra) -> {
                    player.release();
                    f.delete();
                    synchronized (done) { done.notifyAll(); }
                    return true;
                });
                mp.prepare();
                mp.start();
            } catch (Exception e) {
                f.delete();
                synchronized (done) { done.notifyAll(); }
            }
        });
        synchronized (done) {
            done.wait(120_000);
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    private static final class ReplyRound {
        final TreeMap<Integer, PendingUtterance> pending = new TreeMap<>();
        int nextSeq = 1;
        boolean closed;
        long openSinceMs;

        PendingUtterance ensure(int seq) {
            PendingUtterance u = pending.get(seq);
            if (u == null) {
                u = new PendingUtterance();
                pending.put(seq, u);
            }
            return u;
        }
    }

    private static final class PendingUtterance {
        final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        String format = "pcm";
        int sampleRate = 22050;
        boolean complete;
    }
}
