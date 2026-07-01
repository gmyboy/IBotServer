package com.pophie.controller;

import com.pophie.entity.ConversationEntity;
import com.pophie.entity.MemoryEntity;
import com.pophie.entity.ProactiveLogEntity;
import com.pophie.exception.ApiException;
import com.pophie.repository.ConversationRepository;
import com.pophie.repository.MemoryRepository;
import com.pophie.repository.ProactiveLogRepository;
import com.pophie.schema.AudioPayload;
import com.pophie.schema.DeviceBindRequest;
import com.pophie.schema.DeviceBindResponse;
import com.pophie.schema.ChatRequest;
import com.pophie.schema.FacialExpression;
import com.pophie.schema.OwnerProfile;
import com.pophie.schema.OwnerProfilePutRequest;
import com.pophie.schema.OwnerProfilePutResponse;
import com.pophie.schema.RobotState;
import com.pophie.schema.Schemas;
import com.pophie.schema.SttRequest;
import com.pophie.schema.SttResult;
import com.pophie.schema.TickRequest;
import com.pophie.schema.TtsRequest;
import com.pophie.schema.TtsResponse;
import com.pophie.schema.VoiceProsody;
import com.pophie.schema.VoiceSegmentSttPatchRequest;
import com.pophie.schema.VoiceSegmentUploadRequest;
import com.pophie.schema.VoiceSegmentUploadResponse;
import com.pophie.service.DeviceBindService;
import com.pophie.service.ChatService;
import com.pophie.service.MemoryService;
import com.pophie.service.ProactiveService;
import com.pophie.service.ReminderService;
import com.pophie.service.ReplyNotifyService;
import com.pophie.service.RobotService;
import com.pophie.service.SpeechService;
import com.pophie.service.VoiceSegmentLogService;
import com.pophie.util.JsonUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务 API（全部公开，对齐原 FastAPI 路由）。直接返回原始 JSON 结构，不套 BaseResponse。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ChatService chatService;
    private final MemoryService memory;
    private final ReminderService reminder;
    private final ProactiveService proactive;
    private final SpeechService speech;
    private final RobotService robotService;
    private final MemoryRepository memoryRepo;
    private final ConversationRepository conversationRepo;
    private final ProactiveLogRepository proactiveLogRepo;
    private final VoiceSegmentLogService voiceSegmentLog;
    private final ReplyNotifyService replyNotify;
    private final DeviceBindService deviceBind;

    public ApiController(ChatService chatService, MemoryService memory, ReminderService reminder,
                         ProactiveService proactive, SpeechService speech, RobotService robotService,
                         MemoryRepository memoryRepo, ConversationRepository conversationRepo,
                         ProactiveLogRepository proactiveLogRepo, VoiceSegmentLogService voiceSegmentLog,
                         ReplyNotifyService replyNotify, DeviceBindService deviceBind) {
        this.chatService = chatService;
        this.memory = memory;
        this.reminder = reminder;
        this.proactive = proactive;
        this.speech = speech;
        this.robotService = robotService;
        this.memoryRepo = memoryRepo;
        this.conversationRepo = conversationRepo;
        this.proactiveLogRepo = proactiveLogRepo;
        this.voiceSegmentLog = voiceSegmentLog;
        this.replyNotify = replyNotify;
        this.deviceBind = deviceBind;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean enabled = speech.isEnabled();
        m.put("ok", true);
        m.put("speech_enabled", enabled);
        m.put("stt_engine", enabled ? speech.sttEngine() : null);
        m.put("tts_engine", enabled ? speech.ttsEngine() : null);
        return m;
    }

    @GetMapping("/schema")
    public Map<String, Object> schema() {
        Map<String, Object> out = new LinkedHashMap<>();

        List<Map<String, Object>> expressions = new ArrayList<>();
        for (FacialExpression e : FacialExpression.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getValue());
            m.put("label", Schemas.FACIAL_EXPRESSION_LABELS.get(e));
            expressions.add(m);
        }
        out.put("facial_expressions", expressions);

        Map<String, Object> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, FacialExpression> e : Schemas.FACIAL_EXPRESSION_ALIASES.entrySet()) {
            aliases.put(e.getKey(), e.getValue().getValue());
        }
        out.put("facial_expression_aliases", aliases);

        Map<String, Object> voiceProsody = new LinkedHashMap<>();
        voiceProsody.put("tone", List.of("温柔", "平静", "急躁", "兴奋", "低落", "撒娇", "疑问", "冷淡"));
        voiceProsody.put("intonation", List.of("平稳", "上扬", "下沉", "起伏大"));
        voiceProsody.put("speed", List.of("慢", "正常", "快", "极快"));
        out.put("voice_prosody", voiceProsody);

        List<Map<String, Object>> gestures = new ArrayList<>();
        for (Map.Entry<String, String> e : Schemas.GESTURE_LABELS.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("label", e.getValue());
            gestures.add(m);
        }
        out.put("gestures", gestures);

        List<Map<String, Object>> states = new ArrayList<>();
        for (RobotState s : RobotState.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.getValue());
            m.put("label", Schemas.ROBOT_STATE_LABELS.get(s));
            states.add(m);
        }
        out.put("robot_states", states);

        Map<String, Object> perceptionFields = new LinkedHashMap<>();
        perceptionFields.put("facial_expression", "用户面部表情（7 类或别名）");
        perceptionFields.put("voice", "语音侧道（语气/语调/语速），仅与文字/STT 同时有效");
        perceptionFields.put("touch", "抚摸类物理交互，如 摸头/拥抱");
        perceptionFields.put("identity", "端侧身份识别到的人名（仅作感知上下文，记忆按 robot_id 隔离）");
        perceptionFields.put("gesture", "端侧手势识别结果（type 取 gestures 列表 key）");
        perceptionFields.put("posture", "体姿态（预留，暂不进入 LLM）");
        out.put("perception_fields", perceptionFields);

        Map<String, Object> fsm = new LinkedHashMap<>();
        fsm.put("happy", "happy");
        fsm.put("neutral", "idle");
        fsm.put("sad", "sleepy");
        fsm.put("angry", "confused");
        fsm.put("disgust", "confused");
        fsm.put("fear", "confused");
        fsm.put("surprise", "confused");
        out.put("fsm_state_mapping", fsm);

        out.put("tts_voices", speech.listTtsVoices());
        out.put("reserved_fields", List.of("posture"));
        return out;
    }

    /** 设备绑定：以端侧唯一 device_id 关联用户，返回 user_id / robot_id。 */
    @PostMapping("/device/bind")
    public DeviceBindResponse bindDevice(@RequestBody DeviceBindRequest req) {
        return deviceBind.bind(req);
    }

    @GetMapping("/device/bind")
    public DeviceBindResponse getDeviceBinding(@RequestParam String deviceId) {
        return deviceBind.getBinding(deviceId);
    }

    @PostMapping("/session/new")
    public Map<String, Object> newSession(@RequestParam(required = false) String robotId,
                                          @RequestParam(required = false) String userId,
                                          @RequestParam(required = false) String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            DeviceBindRequest bindReq = new DeviceBindRequest();
            bindReq.setDeviceId(deviceId);
            bindReq.setUserId(userId);
            bindReq.setRobotId(robotId);
            DeviceBindResponse bound = deviceBind.bind(bindReq);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("device_id", bound.getDeviceId());
            m.put("robot_id", bound.getRobotId());
            m.put("user_id", bound.getUserId());
            m.put("session_id", chatService.ensureSession(null));
            m.put("new_user", bound.isNewUser());
            m.put("new_device", bound.isNewDevice());
            return m;
        }
        String rid = chatService.resolveRobot(robotId);
        robotService.touchRobot(rid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("robot_id", rid);
        m.put("user_id", chatService.resolveUser(userId));
        m.put("session_id", chatService.ensureSession(null));
        return m;
    }

    @PutMapping("/robots/{robotId}/owner")
    public OwnerProfilePutResponse putOwner(@PathVariable String robotId,
                                            @RequestBody OwnerProfilePutRequest req) {
        if (req.getOwner() == null) throw new ApiException(400, "owner is required");
        String nickname = req.getOwner().getNickname() == null ? "" : req.getOwner().getNickname().trim();
        String robotName = req.getOwner().getRobotName() == null ? "" : req.getOwner().getRobotName().trim();
        if (nickname.isEmpty() || robotName.isEmpty()) {
            throw new ApiException(400, "nickname and robot_name are required");
        }
        OwnerProfile profile;
        try {
            profile = OwnerProfile.normalized(nickname, robotName, req.getOwner().getGender(),
                    req.getOwner().getBirthday(), req.getOwner().isFaceRegistered());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        Map<String, Object> profileMap = new LinkedHashMap<>();
        profileMap.put("nickname", profile.getNickname());
        profileMap.put("robot_name", profile.getRobotName());
        profileMap.put("gender", profile.getGender());
        profileMap.put("birthday", profile.getBirthday());
        profileMap.put("face_registered", profile.isFaceRegistered());
        Map<String, Object> saved = robotService.upsertOwnerProfile(robotId, profileMap);
        OwnerProfile owner = new OwnerProfile(
                (String) saved.get("nickname"), (String) saved.get("robot_name"),
                (String) saved.get("gender"), (String) saved.get("birthday"),
                Boolean.TRUE.equals(saved.get("face_registered")));
        return new OwnerProfilePutResponse(true, robotId, owner);
    }

    @GetMapping("/robots/{robotId}/owner")
    public OwnerProfile getOwner(@PathVariable String robotId) {
        Map<String, Object> owner = robotService.getOwnerProfile(robotId);
        if (owner == null) throw new ApiException(404, "owner not found");
        return new OwnerProfile(
                (String) owner.get("nickname"), (String) owner.get("robot_name"),
                (String) owner.get("gender"), (String) owner.get("birthday"),
                Boolean.TRUE.equals(owner.get("face_registered")));
    }

    @DeleteMapping("/robots/{robotId}/owner")
    public Map<String, Object> deleteOwner(@PathVariable String robotId) {
        if (!robotService.deleteOwnerProfile(robotId)) throw new ApiException(404, "owner not found");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("robot_id", robotId);
        return m;
    }

    @PostMapping("/stt")
    public SttResult stt(@RequestBody SttRequest req) {
        if (!speech.isEnabled()) throw new ApiException(503, "语音功能未启用");
        try {
            return speech.transcribe(req.getAudio());
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** 客户端上传语音段流水（含主人/非主人声纹标记、音频、STT 文本）。 */
    @PostMapping("/voice/segments")
    public VoiceSegmentUploadResponse uploadVoiceSegment(@RequestBody VoiceSegmentUploadRequest req) {
        return voiceSegmentLog.ingest(req);
    }

    @GetMapping("/voice/segments")
    public Map<String, Object> listVoiceSegments(@RequestParam(required = false) String robotId,
                                                 @RequestParam(required = false) String userId,
                                                 @RequestParam(required = false) String sessionId,
                                                 @RequestParam(required = false) Boolean isOwner,
                                                 @RequestParam(defaultValue = "50") int limit) {
        return voiceSegmentLog.list(robotId, userId, sessionId, isOwner, limit);
    }

    @GetMapping("/voice/segments/{id}")
    public Map<String, Object> getVoiceSegment(@PathVariable long id,
                                               @RequestParam(defaultValue = "false") boolean includeAudio) {
        return voiceSegmentLog.getById(id, includeAudio);
    }

    /** 补写段流水 STT 文本（final 晚于入库时由客户端调用）。 */
    @PatchMapping("/voice/segments/{id}/stt")
    public VoiceSegmentUploadResponse patchVoiceSegmentStt(@PathVariable long id,
                                                           @RequestBody VoiceSegmentSttPatchRequest req) {
        return voiceSegmentLog.patchStt(id, req);
    }

    @PostMapping("/tts")
    public TtsResponse tts(@RequestBody TtsRequest req) {
        if (!speech.isEnabled()) throw new ApiException(503, "语音功能未启用");
        try {
            AudioPayload audio = speech.synthesizePayload(req.getText(), req.getVoice(), req.getVoiceId());
            return new TtsResponse(req.getText(), req.getVoice(), audio);
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    @PostMapping("/tts/stream")
    public StreamingResponseBody ttsStream(@RequestBody TtsRequest req) {
        if (!speech.isEnabled()) throw new ApiException(503, "语音功能未启用");
        return out -> {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            Map<String, Object> metrics = new LinkedHashMap<>();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", "meta");
            meta.put("format", speech.ttsStreamFormat());
            meta.put("sample_rate", speech.ttsSampleRate());
            meta.put("encoding", "base64");
            w.write(JsonUtil.dumps(meta) + "\n");
            w.flush();
            try {
                speech.iterTtsChunks(req.getText(), req.getVoice(), req.getVoiceId(), metrics, chunk -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("type", "chunk");
                    line.put("data", Base64.getEncoder().encodeToString(chunk));
                    try {
                        w.write(JsonUtil.dumps(line) + "\n");
                        w.flush();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("type", "error");
                err.put("message", e.getMessage());
                w.write(JsonUtil.dumps(err) + "\n");
                w.flush();
                return;
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("type", "done");
            done.put("first_packet_ms", metrics.get("first_packet_ms"));
            w.write(JsonUtil.dumps(done) + "\n");
            w.flush();
        };
    }

    @PostMapping("/chat")
    public Object chat(@RequestBody ChatRequest req) {
        return chatService.chat(req);
    }

    @PostMapping(value = "/chat/stream", produces = "application/x-ndjson")
    public StreamingResponseBody chatStream(@RequestBody ChatRequest req) {
        return out -> {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            chatService.chatStream(req, line -> {
                try {
                    w.write(line);
                    w.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        };
    }

    @PostMapping("/tick")
    public Map<String, Object> tick(@RequestBody TickRequest req) {
        String robotId = chatService.resolveRobot(req.getRobotId());
        String userId = chatService.resolveUser(req.getUserId());
        String sessionId = chatService.ensureSession(req.getSessionId());
        Map<String, Object> result = proactive.perceiveAndRespond(robotId, sessionId, req.getSignal(), userId);
        if ("speak".equals(result.get("decision")) && result.get("content") != null
                && !"".equals(result.get("content"))) {
            result.put("output", chatService.synthesizeProactiveOutput(result.get("content").toString()));
        }
        return result;
    }

    /**
     * 开发/联调：向已连接 {@code /api/reply/notify} 的客户端推送一条测试回复（含 TTS）。
     */
    @PostMapping("/reply/test")
    public Map<String, Object> replyTest(@RequestBody(required = false) Map<String, Object> body) {
        String robotId = chatService.resolveRobot(body != null ? asStr(body.get("robot_id")) : null);
        String userId = chatService.resolveUser(body != null ? asStr(body.get("user_id")) : null);
        String sessionId = chatService.ensureSession(body != null ? asStr(body.get("session_id")) : null);
        String text = body != null && body.get("text") != null && !body.get("text").toString().isBlank()
                ? body.get("text").toString().trim()
                : "这是一条测试回复，请确认你能听到。";
        replyNotify.notifyReplyAndWait(robotId, userId, null, text, "test", 120_000);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("robot_id", robotId);
        out.put("user_id", userId);
        out.put("session_id", sessionId);
        out.put("text", text);
        return out;
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    @GetMapping("/memories")
    public Map<String, Object> memories(@RequestParam(required = false) String robotId,
                                        @RequestParam(required = false) String userId,
                                        @RequestParam(required = false) String deviceId,
                                        @RequestParam(required = false) String layer,
                                        @RequestParam(required = false) String sessionId) {
        String uid = deviceBind.resolveUserId(deviceId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", uid);
        out.put("items", memory.listMemories(uid, layer, sessionId, 200));
        return out;
    }

    @GetMapping("/memories/{memId}")
    public Map<String, Object> memoryDetail(@PathVariable Long memId,
                                            @RequestParam(required = false) String robotId) {
        String rid = chatService.resolveRobot(robotId);
        MemoryEntity e = memoryRepo.findById(memId).orElse(null);
        if (e == null) throw new ApiException(404, "not found");
        Map<String, Object> d = memory.memToDict(e);
        Object dRobot = d.get("robot_id");
        if (dRobot != null && !dRobot.equals(rid)) throw new ApiException(404, "not found");
        return d;
    }

    @GetMapping("/conversations")
    public Map<String, Object> conversations(@RequestParam(required = false) String robotId,
                                             @RequestParam(required = false) String userId,
                                             @RequestParam(required = false) String deviceId,
                                             @RequestParam(required = false) String sessionId,
                                             @RequestParam(defaultValue = "100") int limit) {
        String uid = deviceBind.resolveUserId(deviceId, userId);
        List<ConversationEntity> rows = conversationRepo.listConversationsByUser(
                uid, sessionId, PageRequest.of(0, limit));
        java.util.Collections.reverse(rows);
        List<Object> items = new ArrayList<>();
        for (ConversationEntity r : rows) items.add(conversationToDict(r));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    @GetMapping("/proactive_log")
    public Map<String, Object> proactiveLog(@RequestParam(required = false) String robotId,
                                            @RequestParam(defaultValue = "50") int limit) {
        String rid = chatService.resolveRobot(robotId);
        List<ProactiveLogEntity> rows = proactiveLogRepo.findByRobotIdOrderByIdDesc(rid, PageRequest.of(0, limit));
        List<Object> items = new ArrayList<>();
        for (ProactiveLogEntity r : rows) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", r.getId());
            d.put("robot_id", r.getRobotId());
            d.put("user_id", r.getUserId());
            d.put("trigger", r.getTrigger());
            d.put("decision", r.getDecision());
            d.put("content", r.getContent());
            d.put("related_memory_ids", JsonUtil.parseList(r.getRelatedMemoryIds()));
            d.put("created_at", r.getCreatedAt());
            items.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    @GetMapping("/proactive_messages")
    public Map<String, Object> proactiveMessages(@RequestParam(required = false) String robotId,
                                                 @RequestParam(required = false) String sessionId,
                                                 @RequestParam(defaultValue = "0") long sinceId,
                                                 @RequestParam(defaultValue = "50") int limit) {
        String rid = chatService.resolveRobot(robotId);
        List<ConversationEntity> rows = conversationRepo.proactiveMessages(rid, sinceId, sessionId, PageRequest.of(0, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ConversationEntity r : rows) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", r.getId());
            d.put("session_id", r.getSessionId());
            d.put("content", r.getContent());
            d.put("metadata", (r.getMetadata() != null && !r.getMetadata().isEmpty())
                    ? JsonUtil.parseMap(r.getMetadata()) : new LinkedHashMap<>());
            d.put("created_at", r.getCreatedAt());
            items.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("last_id", items.isEmpty() ? sinceId : items.get(items.size() - 1).get("id"));
        return out;
    }

    @GetMapping("/reminders")
    public Map<String, Object> reminders(@RequestParam(required = false) String robotId,
                                         @RequestParam(required = false) String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", reminder.listReminders(chatService.resolveRobot(robotId), status));
        return out;
    }

    @DeleteMapping("/reminders/{reminderId}")
    public Map<String, Object> remindersCancel(@PathVariable Long reminderId) {
        if (!reminder.cancelReminder(reminderId)) throw new ApiException(404, "not found or already fired");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("id", reminderId);
        return m;
    }

    @GetMapping("/l1_frames")
    public Map<String, Object> l1Frames(@RequestParam(required = false) String robotId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", memory.l1().snapshot(chatService.resolveRobot(robotId)));
        return out;
    }

    // dict(r) for conversations：metadata 保持原始字符串（与 Python /api/conversations 一致）
    private Map<String, Object> conversationToDict(ConversationEntity r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.getId());
        d.put("robot_id", r.getRobotId());
        d.put("user_id", r.getUserId());
        d.put("session_id", r.getSessionId());
        d.put("role", r.getRole());
        d.put("content", r.getContent());
        d.put("modality", r.getModality());
        d.put("metadata", r.getMetadata());
        d.put("created_at", r.getCreatedAt());
        return d;
    }
}
