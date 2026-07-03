package com.pophie.service;

import com.pophie.config.RuntimeConfigService;
import com.pophie.entity.ConversationEntity;
import com.pophie.repository.ConversationRepository;
import com.pophie.schema.VoiceProsody;
import com.pophie.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class ReplyNotifyService {

    private static final Logger log = LoggerFactory.getLogger("pophie.reply");

    private final SpeechService speech;
    private final ConversationRepository conversationRepo;
    private final RuntimeConfigService cfg;
    private final RobotService robotService;
    private final Executor bgExecutor;
    private final Executor ttsExecutor;
    private final List<NotifySession> sessions = new ArrayList<>();
    private final ConcurrentHashMap<String, Object> pushLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutorService> userPushExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> wsToUserKey = new ConcurrentHashMap<>();

    public ReplyNotifyService(SpeechService speech, ConversationRepository conversationRepo,
                              RuntimeConfigService cfg, RobotService robotService,
                              @Qualifier("dbExecutor") Executor bgExecutor,
                              @Qualifier("ttsExecutor") Executor ttsExecutor) {
        this.speech = speech;
        this.conversationRepo = conversationRepo;
        this.cfg = cfg;
        this.robotService = robotService;
        this.bgExecutor = bgExecutor;
        this.ttsExecutor = ttsExecutor;
    }

    public void register(WebSocketSession ws, String robotId, String userId, String sessionId) {
        String key = robotId + ":" + userId;
        synchronized (sessions) {
            sessions.add(new NotifySession(ws, robotId, userId, sessionId));
        }
        wsToUserKey.put(ws.getId(), key);
        log.info("[reply/ws] register robot={} user={} session={}", robotId, userId, sessionId);
    }

    public void unregister(WebSocketSession ws) {
        String wsId = ws.getId();
        String userKey = wsToUserKey.remove(wsId);
        synchronized (sessions) {
            sessions.removeIf(s -> s.ws.getId().equals(wsId));
        }
        pushLocks.remove(wsId);
        if (userKey != null) {
            boolean stillHasSession;
            synchronized (sessions) {
                stillHasSession = sessions.stream().anyMatch(s -> (s.robotId + ":" + s.userId).equals(userKey));
            }
            if (!stillHasSession) {
                ExecutorService ex = userPushExecutors.remove(userKey);
                if (ex != null) {
                    ex.shutdown();
                    log.info("[reply/ws] shutdown push executor for user={}", userKey);
                }
            }
        }
    }

    public void notifyReply(String robotId, String userId, String sessionId,
                            String text, String source) {
        notifyReplyInternal(robotId, userId, sessionId, text, source, false);
    }

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
            String rid = resolveRobot(robotId);
            String uid = resolveUser(userId);
            String sid = resolveSessionId(sessionId, rid, uid);
            robotService.touchRobot(rid);
            saveReplyText(rid, uid, sid, text, source);

            List<NotifySession> targets;
            synchronized (sessions) {
                targets = new ArrayList<>();
                for (NotifySession s : sessions) {
                    if (s.matches(rid, uid, sessionId)) targets.add(s);
                }
            }
            for (NotifySession s : targets) {
                pushToSession(s, text, source, sid);
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

    private String resolveSessionId(String paramSid, String robotId, String userId) {
        if (paramSid != null && !paramSid.isBlank()) {
            return ensureSession(paramSid);
        }
        synchronized (sessions) {
            for (NotifySession s : sessions) {
                if (s.robotId.equals(robotId) && s.userId.equals(userId)
                        && s.sessionId != null && !s.sessionId.isBlank()) {
                    return ensureSession(s.sessionId);
                }
            }
        }
        return ensureSession(null);
    }

    private void saveReplyText(String robotId, String userId, String sessionId,
                               String text, String source) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reply_source", source == null ? "notify" : source);
        meta.put("channel", "reply_notify");
        meta.put("server_tts", true);

        ConversationEntity conv = new ConversationEntity();
        conv.setRobotId(robotId);
        conv.setUserId(userId);
        conv.setSessionId(sessionId);
        conv.setRole("assistant");
        conv.setContent(text.trim());
        conv.setModality("text");
        conv.setMetadata(JsonUtil.dumps(meta));
        conversationRepo.save(conv);

        log.info("[reply/ws] saved conv robot={} session={} src={} text={}",
                robotId, sessionId, source, text.substring(0, Math.min(40, text.length())));
    }

    private String resolveRobot(String robotId) {
        if (robotId != null && !robotId.isEmpty()) return robotId;
        return RuntimeConfigService.str(cfg.server(), "default_robot", "default");
    }

    private static String resolveUser(String userId) {
        String uid = userId == null ? "" : userId.trim();
        return uid.isEmpty() ? "default" : uid;
    }

    private static String ensureSession(String sessionId) {
        return (sessionId != null && !sessionId.isEmpty())
                ? sessionId : "sess-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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
        if (!s.ws.isOpen()) {
            unregister(s.ws);
            return;
        }
        ReplyStreamEmitter emitter = new ReplyStreamEmitter(
                sessionId, true, new VoiceProsody(), null, speech, ttsExecutor, lock,
                line -> sendRaw(s.ws, line.trim()));
        emitter.replyStart(source);
        emitter.emitSpeak(text);
        emitter.awaitPendingTts(120_000);
        emitter.replyDone();
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
            return sessionId.equals(sid);
        }
    }
}
