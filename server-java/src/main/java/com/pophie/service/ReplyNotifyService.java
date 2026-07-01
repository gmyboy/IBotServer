package com.pophie.service;

import com.pophie.schema.VoiceProsody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 向已连接的客户端 WebSocket 推送「需要回复用户」通知 + 服务端 TTS 音频。
 * 用于主动发言、提醒等不经过 chat/stream HTTP 的场景。
 */
@Service
public class ReplyNotifyService {

    private static final Logger log = LoggerFactory.getLogger("pophie.reply");

    private final SpeechService speech;
    private final Executor bgExecutor;
    private final List<NotifySession> sessions = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Object> pushLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutorService> userPushExecutors = new ConcurrentHashMap<>();

    public ReplyNotifyService(SpeechService speech, @Qualifier("dbExecutor") Executor bgExecutor) {
        this.speech = speech;
        this.bgExecutor = bgExecutor;
    }

    public void register(WebSocketSession ws, String robotId, String userId, String sessionId) {
        sessions.add(new NotifySession(ws, robotId, userId, sessionId));
        log.info("[reply/ws] register robot={} user={} session={}", robotId, userId, sessionId);
    }

    public void unregister(WebSocketSession ws) {
        sessions.removeIf(s -> s.ws.getId().equals(ws.getId()));
        pushLocks.remove(ws.getId());
    }

    /** 匹配 robot+user（session_id 为空则广播该用户所有连接）。 */
    public void notifyReply(String robotId, String userId, String sessionId,
                            String text, String source) {
        notifyReplyInternal(robotId, userId, sessionId, text, source, false);
    }

    /** 联调：推送完成后再返回（供 /api/reply/test 使用）。 */
    public void notifyReplyAndWait(String robotId, String userId, String sessionId,
                                   String text, String source, long timeoutMs) {
        notifyReplyInternal(robotId, userId, sessionId, text, source, true, timeoutMs);
    }

    private void notifyReplyInternal(String robotId, String userId, String sessionId,
                                     String text, String source, boolean wait) {
        notifyReplyInternal(robotId, userId, sessionId, text, source, wait, 120_000);
    }

    private void notifyReplyInternal(String robotId, String userId, String sessionId,
                                     String text, String source, boolean wait, long timeoutMs) {
        if (text == null || text.isBlank()) return;
        Runnable task = () -> {
            for (NotifySession s : sessions) {
                if (!s.matches(robotId, userId, sessionId)) continue;
                pushToSession(s, text, source, sessionId);
            }
        };
        ExecutorService ex = userPushExecutor(robotId, userId);
        if (wait) {
            try {
                ex.submit(task).get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("[reply/ws] notify wait failed: {}", e.getMessage());
            }
        } else {
            ex.execute(task);
        }
    }

    private ExecutorService userPushExecutor(String robotId, String userId) {
        String key = robotId + ":" + userId;
        return userPushExecutors.computeIfAbsent(key, k ->
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "reply-push-" + k);
                    t.setDaemon(true);
                    return t;
                }));
    }

    private void pushToSession(NotifySession s, String text, String source, String sessionId) {
        if (!s.ws.isOpen()) {
            unregister(s.ws);
            return;
        }
        Object lock = pushLocks.computeIfAbsent(s.ws.getId(), k -> new Object());
        synchronized (lock) {
            if (!s.ws.isOpen()) {
                unregister(s.ws);
                return;
            }
            Object emitLock = new Object();
            ReplyStreamEmitter emitter = new ReplyStreamEmitter(
                    sessionId, true, new VoiceProsody(), null, speech, bgExecutor, emitLock,
                    line -> sendRaw(s.ws, line.trim()));
            emitter.replyStart(source);
            emitter.emitSpeak(text);
            emitter.awaitPendingTts(120_000);
            emitter.replyDone();
        }
    }

    private void sendRaw(WebSocketSession ws, String json) {
        try {
            if (ws.isOpen()) {
                ws.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.warn("[reply/ws] send failed: {}", e.getMessage());
        }
    }

    private record NotifySession(WebSocketSession ws, String robotId, String userId, String sessionId) {
        boolean matches(String rid, String uid, String sid) {
            if (!robotId.equals(rid) || !userId.equals(uid)) return false;
            if (sessionId == null || sessionId.isBlank()) return true;
            if (sid == null || sid.isBlank()) return true;
            return sessionId.equals(sid) || this.sessionId == null || this.sessionId.isBlank()
                    || this.sessionId.equals(sid);
        }
    }
}
