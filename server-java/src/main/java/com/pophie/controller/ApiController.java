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
import com.pophie.schema.RobotConfigRequest;
import com.pophie.schema.RobotConfigResponse;
import com.pophie.schema.UserProfileRequest;
import com.pophie.schema.UserProfileResponse;
import com.pophie.service.DeviceBindService;
import com.pophie.service.ChatService;
import com.pophie.service.MemoryService;
import com.pophie.service.ProactiveService;
import com.pophie.service.ReminderService;
import com.pophie.service.ReplyNotifyService;
import com.pophie.service.RobotService;
import com.pophie.service.SpeechService;
import com.pophie.service.UserService;
import com.pophie.service.VoiceSegmentLogService;
import com.pophie.util.JsonUtil;
import org.springframework.data.domain.PageRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final UserService userService;

    public ApiController(ChatService chatService, MemoryService memory, ReminderService reminder,
                         ProactiveService proactive, SpeechService speech, RobotService robotService,
                         MemoryRepository memoryRepo, ConversationRepository conversationRepo,
                         ProactiveLogRepository proactiveLogRepo, VoiceSegmentLogService voiceSegmentLog,
                         ReplyNotifyService replyNotify, DeviceBindService deviceBind,
                         UserService userService) {
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
        this.userService = userService;
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

        List<Map<String, Object>> postures = new ArrayList<>();
        for (Map.Entry<String, String> e : Schemas.POSTURE_LABELS.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("label", e.getValue());
            postures.add(m);
        }
        out.put("postures", postures);

        List<Map<String, Object>> voices = new ArrayList<>();
        for (Map.Entry<String, String[]> e : SpeechService.TTS_VOICES.entrySet()) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("voice_id", e.getKey());
            vm.put("api_voice", e.getValue()[0]);
            vm.put("label", e.getValue()[1]);
            voices.add(vm);
        }
        out.put("voices", voices);

        List<String> robotStates = new ArrayList<>();
        for (RobotState s : RobotState.values()) {
            robotStates.add(s.getValue());
        }
        out.put("robot_states", robotStates);

        return out;
    }

    @PostMapping("/device/bind")
    public DeviceBindResponse bind(@RequestBody DeviceBindRequest req) {
        return deviceBind.bind(req);
    }

    @GetMapping("/device/binding")
    public DeviceBindResponse getBinding(@RequestParam("device_id") String deviceId) {
        return deviceBind.getBinding(deviceId);
    }

    @PostMapping("/sessions")
    public Map<String, Object> newSession() {
        String sid = chatService.ensureSession(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", sid);
        return m;
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
        String rid = chatService.resolveRobot(robotId);
        List<ConversationEntity> rows = conversationRepo.listConversationsByUser(
                uid, sessionId, PageRequest.of(0, limit));
        java.util.Collections.reverse(rows);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ConversationEntity c : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("session_id", c.getSessionId());
            m.put("role", c.getRole());
            m.put("content", c.getContent());
            m.put("modality", c.getModality());
            m.put("metadata", c.getMetadata() == null ? null : JsonUtil.parseMap(c.getMetadata()));
            m.put("created_at", c.getCreatedAt());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", uid);
        out.put("robot_id", rid);
        out.put("items", items);
        return out;
    }

    @GetMapping("/conversations/{convId}")
    public Map<String, Object> conversationDetail(@PathVariable Long convId,
                                                  @RequestParam(required = false) String robotId) {
        String rid = chatService.resolveRobot(robotId);
        ConversationEntity e = conversationRepo.findById(convId).orElse(null);
        if (e == null) throw new ApiException(404, "not found");
        if (!e.getRobotId().equals(rid)) throw new ApiException(404, "not found");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("robot_id", e.getRobotId());
        m.put("user_id", e.getUserId());
        m.put("session_id", e.getSessionId());
        m.put("role", e.getRole());
        m.put("content", e.getContent());
        m.put("modality", e.getModality());
        m.put("metadata", e.getMetadata() == null ? null : JsonUtil.parseMap(e.getMetadata()));
        m.put("created_at", e.getCreatedAt());
        return m;
    }

    @DeleteMapping("/conversations")
    public Map<String, Object> clearConversations(@RequestParam(required = false) String robotId,
                                                  @RequestParam(required = false) String userId,
                                                  @RequestParam(required = false) String deviceId,
                                                  @RequestParam(required = false) String sessionId) {
        String uid = deviceBind.resolveUserId(deviceId, userId);
        String rid = chatService.resolveRobot(robotId);
        conversationRepo.deleteByRobotId(rid);
        memory.l1().clear(rid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("cleared", true);
        return m;
    }

    @PostMapping("/tts")
    public TtsResponse tts(@RequestBody TtsRequest req) {
        AudioPayload audio = speech.synthesizePayload(req.getText(), req.getVoice(), req.getVoiceId());
        return new TtsResponse(req.getText(), req.getVoice(), audio);
    }

    @PostMapping("/stt")
    public SttResult stt(@RequestBody SttRequest req) {
        AudioPayload audio = req.getAudio();
        if (audio == null) throw new ApiException(400, "audio required");
        return speech.transcribe(audio);
    }

    @PostMapping("/voice/segments")
    public VoiceSegmentUploadResponse uploadVoiceSegment(@RequestBody VoiceSegmentUploadRequest req) {
        return voiceSegmentLog.ingest(req);
    }

    @PatchMapping("/voice/segments/{segmentId}/stt")
    public Map<String, Object> patchSegmentStt(@PathVariable Long segmentId,
                                               @RequestBody VoiceSegmentSttPatchRequest req) {
        voiceSegmentLog.patchStt(segmentId, req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    @GetMapping("/voice/segments")
    public Map<String, Object> listVoiceSegments(@RequestParam(required = false) String robotId,
                                                 @RequestParam(required = false) String userId,
                                                 @RequestParam(required = false) String deviceId,
                                                 @RequestParam(required = false) String sessionId,
                                                 @RequestParam(required = false) Boolean owner,
                                                 @RequestParam(defaultValue = "50") int limit) {
        String uid = deviceBind.resolveUserId(deviceId, userId);
        String rid = chatService.resolveRobot(robotId);
        String sid = sessionId;
        return voiceSegmentLog.list(rid, uid, sid, owner, limit);
    }

    @GetMapping("/reminders")
    public Map<String, Object> listReminders(@RequestParam(required = false) String robotId,
                                             @RequestParam(required = false) String userId,
                                             @RequestParam(required = false) String status) {
        String rid = chatService.resolveRobot(robotId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("robot_id", rid);
        out.put("items", reminder.listReminders(rid, status));
        return out;
    }

    @PostMapping("/reminders/{reminderId}/ack")
    public Map<String, Object> ackReminder(@PathVariable Long reminderId) {
        reminder.cancelReminder(reminderId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    @GetMapping("/owner/profile")
    public Map<String, Object> getOwnerProfile(@RequestParam(required = false) String robotId) {
        String rid = chatService.resolveRobot(robotId);
        return robotService.getOwnerProfile(rid);
    }

    @PutMapping("/owner/profile")
    public OwnerProfilePutResponse putOwnerProfile(@RequestBody OwnerProfilePutRequest req,
                                                   @RequestParam(required = false) String robotId) {
        String rid = chatService.resolveRobot(robotId);
        OwnerProfile inputOwner = req.getOwner();
        Map<String, Object> profileMap = new LinkedHashMap<>();
        if (inputOwner != null) {
            profileMap.put("nickname", inputOwner.getNickname());
            profileMap.put("robot_name", inputOwner.getRobotName());
            profileMap.put("gender", inputOwner.getGender());
            profileMap.put("birthday", inputOwner.getBirthday());
            profileMap.put("face_registered", inputOwner.isFaceRegistered());
        }
        Map<String, Object> saved = robotService.upsertOwnerProfile(rid, profileMap);
        OwnerProfile owner = new OwnerProfile(
                (String) saved.get("nickname"),
                (String) saved.get("robot_name"),
                (String) saved.get("gender"),
                (String) saved.get("birthday"),
                Boolean.TRUE.equals(saved.get("face_registered"))
        );
        return new OwnerProfilePutResponse(true, rid, owner);
    }

    @GetMapping("/robot/state")
    public Map<String, Object> getRobotState(@RequestParam(required = false) String robotId) {
        String rid = chatService.resolveRobot(robotId);
        robotService.touchRobot(rid);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("robot_id", rid);
        state.put("state", RobotState.idle.getValue());
        return state;
    }

    @GetMapping("/proactive/logs")
    public Map<String, Object> proactiveLogs(@RequestParam(required = false) String robotId,
                                             @RequestParam(defaultValue = "100") int limit) {
        String rid = chatService.resolveRobot(robotId);
        List<ProactiveLogEntity> rows = proactiveLogRepo.findByRobotIdOrderByIdDesc(rid, PageRequest.of(0, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProactiveLogEntity e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("decision", e.getDecision());
            m.put("trigger", e.getTrigger() == null ? null : JsonUtil.parseMap(e.getTrigger()));
            m.put("content", e.getContent());
            m.put("created_at", e.getCreatedAt());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("robot_id", rid);
        out.put("items", items);
        return out;
    }

    @GetMapping("/users/{userId}/profile")
    public UserProfileResponse getUserProfile(@PathVariable String userId) {
        return userService.getProfile(userId);
    }

    @PutMapping("/users/{userId}/profile")
    public UserProfileResponse updateUserProfile(@PathVariable String userId,
                                                 @RequestBody UserProfileRequest req) {
        return userService.updateProfile(userId, req);
    }

    @GetMapping("/robots/{robotId}/config")
    public RobotConfigResponse getRobotConfig(@PathVariable String robotId) {
        return robotService.getRobotConfig(robotId);
    }

    @PutMapping("/robots/{robotId}/config")
    public RobotConfigResponse updateRobotConfig(@PathVariable String robotId,
                                                 @RequestBody RobotConfigRequest req) {
        return robotService.updateRobotConfig(robotId, req);
    }
}
