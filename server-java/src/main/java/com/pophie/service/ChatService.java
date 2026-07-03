package com.pophie.service;

import com.pophie.config.RuntimeConfigService;
import com.pophie.entity.ConversationEntity;
import com.pophie.exception.ApiException;
import com.pophie.repository.ConversationRepository;
import com.pophie.schema.ChatInput;
import com.pophie.schema.ChatRequest;
import com.pophie.schema.ChatResponse;
import com.pophie.schema.FacialExpression;
import com.pophie.schema.PerceptionInput;
import com.pophie.schema.RobotOutput;
import com.pophie.schema.Schemas;
import com.pophie.schema.SttResult;
import com.pophie.schema.VoiceProsody;
import com.pophie.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 对话管线与全部辅助函数，逐函数对应 main.py。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger("pophie");

    private final RuntimeConfigService cfg;
    private final LlmService llm;
    private final MemoryService memory;
    private final ReminderService reminder;
    private final SpeechService speech;
    private final RobotService robotService;
    private final ConversationRepository conversationRepo;
    private final Executor bgExecutor;
    private final Executor ttsExecutor;
    private final DeviceBindService deviceBind;

    public ChatService(RuntimeConfigService cfg, LlmService llm, MemoryService memory,
                       ReminderService reminder, SpeechService speech, RobotService robotService,
                       ConversationRepository conversationRepo, DeviceBindService deviceBind,
                       @Qualifier("dbExecutor") Executor bgExecutor,
                       @Qualifier("ttsExecutor") Executor ttsExecutor) {
        this.cfg = cfg;
        this.llm = llm;
        this.memory = memory;
        this.reminder = reminder;
        this.speech = speech;
        this.robotService = robotService;
        this.conversationRepo = conversationRepo;
        this.deviceBind = deviceBind;
        this.bgExecutor = bgExecutor;
        this.ttsExecutor = ttsExecutor;
    }

    // ---------- 解析辅助 ----------

    public String resolveRobot(String robotId) {
        if (robotId != null && !robotId.isEmpty()) return robotId;
        return RuntimeConfigService.str(cfg.server(), "default_robot", "default");
    }

    public String resolveUser(String userId) {
        String uid = userId == null ? "" : userId.trim();
        return uid.isEmpty() ? "default" : uid;
    }

    public String ensureSession(String sessionId) {
        return (sessionId != null && !sessionId.isEmpty())
                ? sessionId : "sess-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private boolean shouldServerTts(ChatInput chatInput) {
        if (Boolean.FALSE.equals(chatInput.getServerTts())) return false;
        if (Boolean.TRUE.equals(chatInput.getServerTts())) return speech.isEnabled();
        return speech.isEnabled() && RuntimeConfigService.bool(cfg.chat(), "stream_server_tts", true);
    }

    private boolean shouldInlineTts(ChatInput chatInput) {
        if (Boolean.TRUE.equals(chatInput.getSkipTts())) return false;
        if (Boolean.FALSE.equals(chatInput.getSkipTts())) return true;
        return RuntimeConfigService.bool(cfg.chat(), "inline_tts", true);
    }

    private int chatRecentTurns() {
        return RuntimeConfigService.integer(cfg.chat(), "recent_turns", 8);
    }

    private int chatRecallTopK() {
        return RuntimeConfigService.integer(cfg.chat(), "recall_top_k", 5);
    }

    // ---------- 历史会话 ----------

    @SuppressWarnings("unchecked")
    private String assistantJsonContent(String text, Map<String, Object> metadata) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return t;
        if (t.startsWith("{")) return t;
        Object out = metadata == null ? null : metadata.get("output");
        if (out instanceof Map && ((Map<String, Object>) out).get("text") != null
                && !"".equals(((Map<String, Object>) out).get("text"))) {
            Map<String, Object> o = (Map<String, Object>) out;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", o.getOrDefault("text", t));
            m.put("facial_expression", o.getOrDefault("facial_expression", "neutral"));
            Object voice = o.get("voice");
            m.put("voice", voice != null ? voice : defaultVoiceMap());
            m.put("gesture", null);
            m.put("posture", null);
            return JsonUtil.dumps(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", t);
        m.put("facial_expression", "neutral");
        m.put("voice", defaultVoiceMap());
        m.put("gesture", null);
        m.put("posture", null);
        return JsonUtil.dumps(m);
    }

    private Map<String, Object> defaultVoiceMap() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("tone", "温柔");
        v.put("intonation", "平稳");
        v.put("speed", "正常");
        return v;
    }

    private List<Map<String, Object>> recentConversations(String userId, String sessionId, int n) {
        List<ConversationEntity> rows = conversationRepo.findByUserIdAndSessionIdOrderByIdDesc(
                userId, sessionId, PageRequest.of(0, n));
        Collections.reverse(rows);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConversationEntity r : rows) {
            Map<String, Object> meta = null;
            if (r.getMetadata() != null && !r.getMetadata().isEmpty()) {
                try {
                    meta = JsonUtil.parseMap(r.getMetadata());
                } catch (Exception e) {
                    meta = null;
                }
            }
            String content = r.getContent();
            if (!"user".equals(r.getRole())) {
                content = assistantJsonContent(content, meta);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", r.getRole());
            m.put("content", content);
            m.put("modality", r.getModality());
            m.put("created_at", r.getCreatedAt());
            result.add(m);
        }
        return result;
    }

    public void saveConv(String robotId, String sessionId, String role, String content,
                         String modality, Map<String, Object> metadata, String userId) {
        ConversationEntity conv = new ConversationEntity();
        conv.setRobotId(robotId);
        conv.setUserId(userId);
        conv.setSessionId(sessionId);
        conv.setRole(role);
        conv.setContent(content);
        conv.setModality(modality);
        conv.setMetadata(JsonUtil.dumps(metadata == null ? new LinkedHashMap<>() : metadata));
        conversationRepo.save(conv);
    }

    // ---------- system prompt ----------

    private String ownerPromptBlock(String robotId) {
        Map<String, Object> owner = robotService.getOwnerProfile(robotId);
        if (owner == null) return "";
        List<String> lines = new ArrayList<>();
        lines.add("\n[主人档案]");
        lines.add("- 主人称呼：" + owner.get("nickname"));
        lines.add("- 你的名字（主人起的）：" + owner.get("robot_name"));
        Object gender = owner.get("gender");
        if (gender != null && !"".equals(gender)) {
            Map<String, String> labels = Map.of("male", "男", "female", "女", "other", "其他");
            lines.add("- 主人性别：" + labels.getOrDefault(gender.toString(), gender.toString()));
        }
        Object birthday = owner.get("birthday");
        if (birthday != null && !"".equals(birthday)) {
            lines.add("- 主人生日：" + birthday);
        }
        Object face = owner.get("face_registered");
        if (Boolean.TRUE.equals(face)) {
            lines.add("- 端侧已录入主人人脸（仅标志，无图像上传）");
        }
        lines.add("- 请自然地用主人称呼与其对话；自我介绍时使用主人给你起的名字。");
        return String.join("\n", lines);
    }

    private String systemPromptFor(String robotId) {
        return PromptConstants.CHAT_SYSTEM_PROMPT + ownerPromptBlock(robotId) + PromptConstants.REPLY_JSON_INSTRUCTION;
    }

    // ---------- 输入处理 ----------

    private VoiceProsody mergeVoice(PerceptionInput perception, VoiceProsody sttVoice) {
        if (perception == null || perception.getVoice() == null) return sttVoice;
        VoiceProsody v = perception.getVoice();
        if (sttVoice == null) return v;
        return new VoiceProsody(
                v.getTone() != null ? v.getTone() : sttVoice.getTone(),
                v.getIntonation() != null ? v.getIntonation() : sttVoice.getIntonation(),
                v.getSpeed() != null ? v.getSpeed() : sttVoice.getSpeed());
    }

    /** 解析 STT、合并感知。对应 _prepare_input。 */
    private Prepared prepareInput(ChatInput chatInput) {
        String text = chatInput.getText() == null ? "" : chatInput.getText().trim();
        SttResult sttResult = null;
        boolean sttUnrecognized = false;
        PerceptionInput perception = chatInput.getPerception() != null
                ? chatInput.getPerception() : new PerceptionInput();

        if (chatInput.getAudio() != null && chatInput.getAudio().getData() != null
                && !chatInput.getAudio().getData().isEmpty() && text.isEmpty()) {
            if (speech.isEnabled()) {
                try {
                    sttResult = speech.transcribe(chatInput.getAudio());
                    text = sttResult.getText().trim();
                    if (text.isEmpty()) sttUnrecognized = true;
                } catch (Exception e) {
                    log.error("[chat] STT 失败: {}", e.getMessage());
                    sttUnrecognized = true;
                }
            } else {
                throw new ApiException(400, "语音功能未启用，请提供 text 或启用 speech.enabled");
            }
        }

        VoiceProsody mergedVoice = mergeVoice(perception, sttResult != null ? sttResult.getVoice() : null);
        if (mergedVoice != null && (mergedVoice.getTone() != null || mergedVoice.getIntonation() != null
                || mergedVoice.getSpeed() != null)) {
            perception = new PerceptionInput(perception.getFacialExpression(), mergedVoice,
                    perception.getTouch(), perception.getIdentity(), perception.getGesture(),
                    perception.getPosture());
        }

        Map<String, Object> pData = Schemas.perceptionToDict(perception);

        if (text.isEmpty()) {
            Map<String, Object> voicePresent = new LinkedHashMap<>();
            for (String k : new ArrayList<>(pData.keySet())) {
                if (Schemas.VOICE_KEYS.contains(k)) voicePresent.put(k, pData.get(k));
            }
            if (!voicePresent.isEmpty()) {
                log.info("[chat] 无文字时忽略声音侧道: {}", voicePresent);
                for (String k : Schemas.VOICE_KEYS) pData.remove(k);
            }
        }

        if (sttUnrecognized) {
            return new Prepared("", new LinkedHashMap<>(), sttResult, true);
        }
        if (text.isEmpty() && pData.isEmpty()) {
            throw new ApiException(400, "需要文字、语音或至少一项非声音感知输入");
        }
        return new Prepared(text, pData, sttResult, false);
    }

    private String buildUserText(String text, Map<String, Object> pData) {
        String perceptionStr = Schemas.formatPerceptionDict(pData);
        if (!text.isEmpty() && !perceptionStr.isEmpty()) return "[感知 " + perceptionStr + "] " + text;
        if (!text.isEmpty()) return text;
        return "[非语言信号 " + perceptionStr + "]";
    }

    private BuiltHistory buildChatHistory(String robotId, String sessionId, String userId, String userText) {
        int recentN = chatRecentTurns();
        int recallK = chatRecallTopK();
        List<Map<String, Object>> recent = recentConversations(userId, sessionId, recentN);
        List<Map<String, Object>> mems = memory.recallForResponse(userId, sessionId, userText, recallK);
        String memBlock = memory.formatMemoriesForPrompt(mems);
        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", systemPromptFor(robotId) + "\n\n[长期记忆上下文]\n" + memBlock);
        history.add(sys);
        for (Map<String, Object> m : recent) {
            String role = "user".equals(m.get("role")) ? "user" : "assistant";
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("role", role);
            h.put("content", m.get("content"));
            history.add(h);
        }
        return new BuiltHistory(history, recent, mems);
    }

    private RobotOutput generateReply(List<Map<String, Object>> history, Map<String, Object> pData) {
        log.info("[chat] >>> 调用 LLM 生成结构化回复");
        FacialExpression userExpr = Schemas.parseFacialExpression(asStr(pData.get("facial_expression")));
        try {
            Map<String, Object> raw = llm.chatJson(history);
            RobotOutput output = Schemas.robotOutputFromLlm(raw);
            return Schemas.alignOutputToUserPerception(output, userExpr);
        } catch (LlmException e) {
            log.error("[chat] LLM 失败，保持静默：{}", e.getMessage());
            return silentOutput();
        }
    }

    private RobotOutput silentOutput() {
        RobotOutput o = new RobotOutput();
        o.setText("");
        o.setFacialExpression(FacialExpression.neutral);
        return o.finalizeOutput();
    }

    private RobotOutput synthesizeOutput(RobotOutput output, String voiceId) {
        if (!speech.isEnabled() || output.getText().trim().isEmpty()) {
            output.setAudio(null);
            return output.finalizeOutput();
        }
        output.setVoice(Schemas.voiceForExpression(output.getFacialExpression(), output.getVoice()));
        try {
            output.setAudio(speech.synthesizePayload(output.getText(), output.getVoice(), voiceId));
            log.info("[chat] TTS 成功 len={}", output.getText().length());
        } catch (Exception e) {
            log.error("[chat] TTS 失败 text={} voice_id={}: {}",
                    output.getText().substring(0, Math.min(80, output.getText().length())), voiceId, e.getMessage());
        }
        return output.finalizeOutput();
    }

    private void deferChatSideTasks(String robotId, String sessionId, String userText,
                                    String text, List<Map<String, Object>> recent, String userId) {
        bgExecutor.execute(() -> {
            try {
                List<Map<String, Object>> ctx = recent.isEmpty()
                        ? new ArrayList<>() : new ArrayList<>(recent.subList(0, recent.size() - 1));
                memory.ingestUserInput(robotId, sessionId, userText, "text", ctx, userId);
                List<Map<String, Object>> remItems = text.isEmpty()
                        ? new ArrayList<>() : reminder.extractReminders(text);
                if (!remItems.isEmpty()) {
                    reminder.scheduleReminders(robotId, sessionId, userId, text, remItems);
                }
                log.info("[chat.bg] 后台完成 reminders={}", remItems.size());
            } catch (Exception e) {
                log.error("[chat.bg] 后台任务失败", e);
            }
        });
    }

    // ---------- /api/chat ----------

    public ChatResponse chat(ChatRequest req) {
        DeviceBindService.ResolvedIdentity id = deviceBind.resolve(
                req.getDeviceId(), req.getRobotId(), req.getUserId());
        String robotId = id.robotId();
        String userId = id.userId();
        String sessionId = ensureSession(req.getSessionId());
        robotService.touchRobot(robotId);

        ChatInput chatInput = req.getInput();
        String voiceId = chatInput.getVoiceId();

        Prepared prepared = prepareInput(chatInput);

        if (prepared.unrecognized) {
            log.info("[chat] robot={} session={} 语音无法识别，保持静默", robotId, sessionId);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("stt_unrecognized", true);
            meta.put("stt", prepared.stt);
            saveConv(robotId, sessionId, "user", "[语音] 未能识别", "text", meta, userId);
            ChatResponse resp = new ChatResponse();
            resp.setRobotId(robotId);
            resp.setUserId(userId);
            resp.setSessionId(sessionId);
            resp.setOutput(silentOutput());
            resp.setStt(prepared.stt);
            return resp;
        }

        String userText = buildUserText(prepared.text, prepared.pData);
        log.info("[chat] robot={} user={} session={} text={} perception={}",
                robotId, userId, sessionId, prepared.text, prepared.pData);

        Map<String, Object> userMeta = new LinkedHashMap<>();
        userMeta.put("perception", prepared.pData.isEmpty() ? null : prepared.pData);
        userMeta.put("text_only", prepared.text);
        userMeta.put("no_text", prepared.text.isEmpty());
        userMeta.put("stt", prepared.stt);
        saveConv(robotId, sessionId, "user", userText, "text", userMeta, userId);

        BuiltHistory bh = buildChatHistory(robotId, sessionId, userId, userText);

        boolean deferSide = RuntimeConfigService.bool(cfg.chat(), "defer_side_tasks", true);
        Map<String, Object> ingestResult = new LinkedHashMap<>();
        ingestResult.put("produced", new ArrayList<>());
        ingestResult.put("l1_frames", new ArrayList<>());
        List<Map<String, Object>> remItems = new ArrayList<>();

        RobotOutput output;
        if (deferSide) {
            output = generateReply(bh.history, prepared.pData);
            deferChatSideTasks(robotId, sessionId, userText, prepared.text, bh.recent, userId);
        } else {
            output = generateReply(bh.history, prepared.pData);
            List<Map<String, Object>> ctx = bh.recent.isEmpty()
                    ? new ArrayList<>() : new ArrayList<>(bh.recent.subList(0, bh.recent.size() - 1));
            ingestResult = memory.ingestUserInput(robotId, sessionId, userText, "text", ctx, userId);
            remItems = prepared.text.isEmpty() ? new ArrayList<>() : reminder.extractReminders(prepared.text);
        }

        if (shouldInlineTts(chatInput)) {
            output = synthesizeOutput(output, voiceId);
        } else {
            log.warn("[chat] inline_tts=false，响应不含音频（客户端需另调 /api/tts）");
            output.setAudio(null);
            output = output.finalizeOutput();
        }

        log.info("[chat] output text={} expr={}", output.getText(), output.getFacialExpression());

        if (!output.getText().trim().isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("recall_ids", recallIds(bh.mems));
            meta.put("output", output);
            saveConv(robotId, sessionId, "assistant", output.getText(), "text", meta, userId);
        }

        List<Long> remIds = remItems.isEmpty()
                ? new ArrayList<>() : reminder.scheduleReminders(robotId, sessionId, userId, prepared.text, remItems);
        List<Object> scheduled = new ArrayList<>();
        for (int i = 0; i < remIds.size() && i < remItems.size(); i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", remIds.get(i));
            s.put("remind_at", remItems.get(i).get("remind_at"));
            s.put("content", remItems.get(i).get("content"));
            scheduled.add(s);
        }

        ChatResponse resp = new ChatResponse();
        resp.setRobotId(robotId);
        resp.setUserId(userId);
        resp.setSessionId(sessionId);
        resp.setOutput(output);
        resp.setStt(prepared.stt);
        resp.setMemoryFlow((List<Object>) ingestResult.getOrDefault("produced", new ArrayList<>()));
        resp.setL1Frames((List<Object>) ingestResult.getOrDefault("l1_frames", new ArrayList<>()));
        resp.setRecalled(recalledSummary(bh.mems));
        resp.setScheduledReminders(scheduled);
        return resp;
    }

    // ---------- /api/chat/stream ----------

    public void chatStream(ChatRequest req, Consumer<String> emit) {
        DeviceBindService.ResolvedIdentity id = deviceBind.resolve(
                req.getDeviceId(), req.getRobotId(), req.getUserId());
        String robotId = id.robotId();
        String userId = id.userId();
        String sessionId = ensureSession(req.getSessionId());
        robotService.touchRobot(robotId);

        ChatInput chatInput = req.getInput();
        Prepared prepared = prepareInput(chatInput);

        if (prepared.unrecognized) {
            ChatResponse resp = new ChatResponse();
            resp.setRobotId(robotId);
            resp.setUserId(userId);
            resp.setSessionId(sessionId);
            resp.setOutput(silentOutput());
            resp.setStt(prepared.stt);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("type", "done");
            done.put("response", resp);
            emit.accept(JsonUtil.dumps(done) + "\n");
            return;
        }

        String userText = buildUserText(prepared.text, prepared.pData);
        Map<String, Object> userMeta = new LinkedHashMap<>();
        userMeta.put("perception", prepared.pData.isEmpty() ? null : prepared.pData);
        userMeta.put("text_only", prepared.text);
        userMeta.put("no_text", prepared.text.isEmpty());
        userMeta.put("stt", prepared.stt);
        saveConv(robotId, sessionId, "user", userText, "text", userMeta, userId);

        BuiltHistory bh = buildChatHistory(robotId, sessionId, userId, userText);
        boolean deferSide = RuntimeConfigService.bool(cfg.chat(), "defer_side_tasks", true);
        boolean serverTts = shouldServerTts(chatInput);
        VoiceProsody voice = chatInput.getPerception() != null ? chatInput.getPerception().getVoice() : null;
        String voiceId = chatInput.getVoiceId();
        Object emitLock = new Object();
        ReplyStreamEmitter replyEmitter = new ReplyStreamEmitter(
                sessionId, serverTts, voice, voiceId, speech, ttsExecutor, emitLock, line -> {
                    synchronized (emitLock) {
                        emit.accept(line);
                    }
                });
        replyEmitter.replyStart("chat");

        StreamingReplyTextExtractor extractor = new StreamingReplyTextExtractor();
        try {
            String fullContent = llm.streamChat(bh.history, null, true, delta -> {
                for (String chunk : extractor.feed(delta)) {
                    replyEmitter.emitSpeak(chunk);
                }
            });
            String tail = extractor.flush();
            if (tail != null && !tail.isEmpty()) {
                replyEmitter.emitSpeak(tail);
            }

            FacialExpression userExpr = Schemas.parseFacialExpression(asStr(prepared.pData.get("facial_expression")));
            Map<String, Object> raw = llm.parseChatJson(fullContent, true);
            RobotOutput output = Schemas.alignOutputToUserPerception(Schemas.robotOutputFromLlm(raw), userExpr);
            output.setAudio(null);
            output = output.finalizeOutput();

            if (deferSide) {
                deferChatSideTasks(robotId, sessionId, userText, prepared.text, bh.recent, userId);
            }

            if (!output.getText().trim().isEmpty()) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("recall_ids", recallIds(bh.mems));
                meta.put("output", output);
                saveConv(robotId, sessionId, "assistant", output.getText(), "text", meta, userId);
            }

            ChatResponse resp = new ChatResponse();
            resp.setRobotId(robotId);
            resp.setUserId(userId);
            resp.setSessionId(sessionId);
            resp.setOutput(output);
            resp.setStt(prepared.stt);
            resp.setRecalled(recalledSummary(bh.mems));
            replyEmitter.awaitPendingTts(120_000);
            replyEmitter.replyDone();
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("type", "done");
            done.put("response", resp);
            emit.accept(JsonUtil.dumps(done) + "\n");
        } catch (LlmException e) {
            log.error("[chat/stream] LLM 失败：{}", e.getMessage());
            ChatResponse resp = new ChatResponse();
            resp.setRobotId(robotId);
            resp.setUserId(userId);
            resp.setSessionId(sessionId);
            resp.setOutput(silentOutput());
            resp.setStt(prepared.stt);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("type", "done");
            done.put("response", resp);
            emit.accept(JsonUtil.dumps(done) + "\n");
        } catch (Exception e) {
            log.error("[chat/stream] 失败：{}", e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "error");
            err.put("message", e.getMessage());
            emit.accept(JsonUtil.dumps(err) + "\n");
        }
    }

    // ---------- /api/tick 用：合成主动输出 ----------

    public RobotOutput synthesizeProactiveOutput(String content) {
        RobotOutput output = new RobotOutput();
        output.setText(content);
        output.setFacialExpression(FacialExpression.neutral);
        output.setVoice(new VoiceProsody("温柔", "平稳", "正常"));
        return synthesizeOutput(output, null);
    }

    // ---------- 工具 ----------

    private List<Object> recallIds(List<Map<String, Object>> mems) {
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> m : mems) ids.add(m.get("id"));
        return ids;
    }

    private List<Object> recalledSummary(List<Map<String, Object>> mems) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> m : mems) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.get("id"));
            r.put("layer", m.get("layer"));
            r.put("summary", m.get("summary"));
            r.put("importance", m.get("importance"));
            out.add(r);
        }
        return out;
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    private static final class Prepared {
        final String text;
        final Map<String, Object> pData;
        final SttResult stt;
        final boolean unrecognized;
        Prepared(String text, Map<String, Object> pData, SttResult stt, boolean unrecognized) {
            this.text = text;
            this.pData = pData;
            this.stt = stt;
            this.unrecognized = unrecognized;
        }
    }

    private static final class BuiltHistory {
        final List<Map<String, Object>> history;
        final List<Map<String, Object>> recent;
        final List<Map<String, Object>> mems;
        BuiltHistory(List<Map<String, Object>> history, List<Map<String, Object>> recent,
                     List<Map<String, Object>> mems) {
            this.history = history;
            this.recent = recent;
            this.mems = mems;
        }
    }
}
