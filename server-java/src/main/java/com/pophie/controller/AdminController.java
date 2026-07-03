package com.pophie.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.pophie.config.RuntimeConfigService;
import com.pophie.exception.ApiException;
import com.pophie.repository.ConversationRepository;
import com.pophie.repository.MemoryRepository;
import com.pophie.repository.ProactiveLogRepository;
import com.pophie.repository.UserRepository;
import com.pophie.repository.VoiceSegmentLogRepository;
import com.pophie.repository.ReminderRepository;
import com.pophie.schema.AdminModels;
import com.pophie.service.ChatService;
import com.pophie.service.MemoryService;
import com.pophie.service.SpeechService;
import com.pophie.service.ReplyNotifyService;
import com.pophie.service.RobotService;
import com.pophie.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理后台 API。鉴权由 SaTokenConfig 拦截 /api/admin/**（/api/admin/login 除外）。
 * 对应原 main.py 的 X-Admin-Token 方案，改为 sa-token 登录。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger("pophie");
    private static final List<String> RESTART_REQUIRED_FOR = List.of("server.host", "server.port");

    private final RuntimeConfigService cfg;
    private final RobotService robotService;
    private final MemoryService memory;
    private final SpeechService speech;
    private final ChatService chatService;
    private final ReplyNotifyService replyNotify;
    private final MemoryRepository memoryRepo;
    private final ConversationRepository conversationRepo;
    private final ReminderRepository reminderRepo;
    private final ProactiveLogRepository proactiveLogRepo;
    private final VoiceSegmentLogRepository voiceSegmentLogRepo;
    private final UserRepository userRepo;
    private final UserService userService;

    public AdminController(RuntimeConfigService cfg, RobotService robotService, MemoryService memory,
                           SpeechService speech, ChatService chatService, ReplyNotifyService replyNotify,
                           MemoryRepository memoryRepo,
                           ConversationRepository conversationRepo, ReminderRepository reminderRepo,
                           ProactiveLogRepository proactiveLogRepo,
                           VoiceSegmentLogRepository voiceSegmentLogRepo,
                           UserRepository userRepo, UserService userService) {
        this.cfg = cfg;
        this.robotService = robotService;
        this.memory = memory;
        this.speech = speech;
        this.chatService = chatService;
        this.replyNotify = replyNotify;
        this.memoryRepo = memoryRepo;
        this.conversationRepo = conversationRepo;
        this.reminderRepo = reminderRepo;
        this.proactiveLogRepo = proactiveLogRepo;
        this.voiceSegmentLogRepo = voiceSegmentLogRepo;
        this.userRepo = userRepo;
        this.userService = userService;
    }

    private String adminToken() {
        return RuntimeConfigService.str(cfg.server(), "admin_token", "").trim();
    }

    /** 管理员登录：校验 server.admin_token 后签发 sa-token。未配置 token 时（与原行为一致）放行。 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) AdminModels.AdminLoginRequest req) {
        String token = adminToken();
        if (!token.isEmpty()) {
            String provided = req == null ? null : req.getToken();
            if (!token.equals(provided)) {
                throw new ApiException(401, "invalid admin token");
            }
        }
        StpUtil.login("admin");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("token", StpUtil.getTokenValue());
        return m;
    }

    @GetMapping("/robots")
    public Map<String, Object> robotsList(@RequestParam(required = false) String q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", robotService.listRobotsWithStats(q));
        return out;
    }

    @GetMapping("/robots/{robotId}")
    public Map<String, Object> robotDetail(@PathVariable String robotId) {
        Map<String, Object> detail = robotService.getRobotDetail(robotId);
        if (detail == null) throw new ApiException(404, "robot not found");
        return detail;
    }

    @PatchMapping("/robots/{robotId}")
    public Map<String, Object> robotPatch(@PathVariable String robotId,
                                          @RequestBody AdminModels.RobotPatchRequest req) {
        String dn = req.getDisplayName() == null ? "" : req.getDisplayName().trim();
        return robotService.updateRobotDisplayName(robotId, dn);
    }

    @DeleteMapping("/robots/{robotId}")
    public Map<String, Object> robotDelete(@PathVariable String robotId) {
        Map<String, Integer> deleted = robotService.deleteRobotAll(robotId);
        memory.l1().clear(robotId);
        log.info("[admin.delete_robot] robot={} deleted={}", robotId, deleted);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("robot_id", robotId);
        m.put("deleted", deleted);
        return m;
    }

    @GetMapping("/users")
    public Map<String, Object> usersList(@RequestParam(required = false) String q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", userService.listUsersWithStats(q));
        return out;
    }

    @GetMapping("/users/{userId}")
    public Map<String, Object> userDetail(@PathVariable String userId) {
        return userService.getUserDetail(userId);
    }

    @DeleteMapping("/memories/{memId}")
    public Map<String, Object> memoryDelete(@PathVariable Long memId,
                                            @RequestParam(required = false) String robotId) {
        String rid = chatService.resolveRobot(robotId);
        if (!memory.deleteMemory(memId, rid)) throw new ApiException(404, "not found");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("id", memId);
        return m;
    }

    /** 管理员向指定机器人/用户推送消息（触发TTS播报）。
     *  - 指定 robot_id + user_id：推送到该机器人该用户
     *  - 只指定 user_id：推送到该用户绑定的所有机器人
     *  - 只指定 robot_id：推送到该机器人的 default 用户
     */
    @PostMapping("/push")
    public Map<String, Object> pushMessage(@RequestBody Map<String, Object> body) {
        String robotId = asStr(body.get("robot_id"));
        String userId = asStr(body.get("user_id"));
        String text = asStr(body.get("text"));
        if (text == null || text.isBlank()) {
            throw new ApiException(400, "text 不能为空");
        }
        String sessionId = asStr(body.get("session_id"));

        java.util.List<String> targets = new java.util.ArrayList<>();
        if (robotId != null && !robotId.isBlank() && !"default".equals(robotId)) {
            String uid = (userId != null && !userId.isBlank()) ? userId : "default";
            replyNotify.notifyReply(robotId, uid, sessionId, text, "admin_push");
            targets.add(robotId + ":" + uid);
        } else if (userId != null && !userId.isBlank() && !"default".equals(userId)) {
            List<Map<String, Object>> devices = userService.listBoundRobots(userId);
            if (devices.isEmpty()) {
                replyNotify.notifyReply("default", userId, sessionId, text, "admin_push");
                targets.add("default:" + userId);
            } else {
                Set<String> pushed = new java.util.HashSet<>();
                for (Map<String, Object> d : devices) {
                    String rid = String.valueOf(d.get("robot_id"));
                    if (pushed.add(rid)) {
                        replyNotify.notifyReply(rid, userId, sessionId, text, "admin_push");
                        targets.add(rid + ":" + userId);
                    }
                }
            }
        } else {
            throw new ApiException(400, "必须指定 robot_id 或 user_id");
        }

        log.info("[admin.push] targets={} text={}", targets, text);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("targets", targets);
        m.put("text", text);
        return m;
    }

    @GetMapping("/config")
    public Map<String, Object> configGet() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", cfg.getConfigForAdmin());
        m.put("schema", cfg.adminSchema());
        m.put("tts_voices", speech.listTtsVoices());
        m.put("config_path", cfg.configPathString());
        m.put("restart_required_for", RESTART_REQUIRED_FOR);
        return m;
    }

    @PutMapping("/config")
    public Map<String, Object> configPut(@RequestBody AdminModels.ConfigUpdateRequest req) {
        if (req.getConfig() == null) throw new ApiException(400, "config 必须是对象");
        Map<String, Object> updated = cfg.saveConfig(req.getConfig());
        log.info("[admin.config] updated sections={}", req.getConfig().keySet());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("config", updated);
        m.put("tts_voices", speech.listTtsVoices());
        m.put("restart_required_for", RESTART_REQUIRED_FOR);
        return m;
    }

    @GetMapping("/service")
    public Map<String, Object> serviceGet() {
        Map<String, Object> server = cfg.server();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", ProcessHandle.current().pid());
        m.put("host", RuntimeConfigService.str(server, "host", "0.0.0.0"));
        m.put("port", RuntimeConfigService.integer(server, "port", 8000));
        m.put("speech_config_enabled", RuntimeConfigService.bool(cfg.speech(), "enabled", false));
        m.put("speech_runtime", speech.isEnabled());
        return m;
    }

    @PutMapping("/service")
    public Map<String, Object> servicePut(@RequestBody AdminModels.ServiceUpdateRequest req) {
        if (req.getSpeechEnabled() == null) throw new ApiException(400, "需要 speech_enabled");
        Map<String, Object> patch = new LinkedHashMap<>();
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("enabled", req.getSpeechEnabled());
        patch.put("speech", sp);
        Map<String, Object> updated = cfg.saveConfig(patch);
        boolean enabled = RuntimeConfigService.bool(RuntimeConfigService.sub(updated, "speech"), "enabled", false);
        log.info("[admin.service] speech.enabled={} runtime={}", enabled, speech.isEnabled());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("speech_config_enabled", enabled);
        m.put("speech_runtime", speech.isEnabled());
        return m;
    }

    @PostMapping("/service/restart")
    public Map<String, Object> serviceRestart() {
        log.info("[admin.service] restart requested pid={}", ProcessHandle.current().pid());
        scheduleRestart();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", "服务正在重启");
        return m;
    }

    @PostMapping("/wipe")
    public Map<String, Object> wipe(@RequestBody AdminModels.WipeRequest req) {
        String robotId = chatService.resolveRobot(req.getRobotId());
        String scope = (req.getScope() == null ? "memories" : req.getScope()).toLowerCase();
        if (!Set.of("memories", "conversations", "reminders", "all").contains(scope)) {
            throw new ApiException(400, "scope 必须是 memories/conversations/reminders/all");
        }
        Map<String, Object> deleted = new LinkedHashMap<>();
        if (scope.equals("memories") || scope.equals("all")) {
            deleted.put("pb_mem_memories", memoryRepo.deleteByRobotId(robotId));
        }
        if (scope.equals("conversations") || scope.equals("all")) {
            deleted.put("pb_chat_conversations", conversationRepo.deleteByRobotId(robotId));
        }
        if (scope.equals("reminders") || scope.equals("all")) {
            deleted.put("pb_rem_reminders", reminderRepo.deleteByRobotId(robotId));
        }
        if (scope.equals("all")) {
            deleted.put("pb_pro_proactive_log", proactiveLogRepo.deleteByRobotId(robotId));
            deleted.put("pb_voice_segment_logs", voiceSegmentLogRepo.deleteByRobotId(robotId));
        }
        if (scope.equals("memories") || scope.equals("all")) {
            memory.l1().clear(robotId);
        }
        log.info("[admin.wipe] robot={} scope={} deleted={}", robotId, scope, deleted);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("scope", scope);
        m.put("deleted", deleted);
        return m;
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    /** 重启进程：延迟后退出，由容器/进程守护（docker-compose restart 策略 / systemd）拉起。 */
    private void scheduleRestart() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        });
        t.setDaemon(true);
        t.start();
    }
}
