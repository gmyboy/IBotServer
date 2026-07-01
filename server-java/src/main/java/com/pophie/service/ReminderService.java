package com.pophie.service;

import com.pophie.entity.ConversationEntity;
import com.pophie.entity.ProactiveLogEntity;
import com.pophie.entity.ReminderEntity;
import com.pophie.repository.ConversationRepository;
import com.pophie.repository.ProactiveLogRepository;
import com.pophie.repository.ReminderRepository;
import com.pophie.util.JsonUtil;
import com.pophie.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时提醒：到点主动开口。逐函数对应 reminder.py。
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger("pophie.reminder");
    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};

    static final String EXTRACT_SYSTEM = """
你是 Pophie 的"提醒抽取器"。
判断用户最新一句话中是否包含"要 Pophie 在某个时间点主动提醒/叫他/通知他"的意图。
只抽取**明确的定时约定**，闲聊、模糊愿望（"以后想去旅游"）不算。

返回 JSON：
{
  "reminders": [
    {
      "remind_at": "YYYY-MM-DDTHH:MM:SS",   // 必须是绝对本地时间
      "content": "要提醒的事情，简短一句",
      "note": "可选：用户的原话/补充语境"
    }
  ]
}

时间解析规则（参考下面给出的 now 字段做基准）：
- "10 分钟后" / "半小时后" → now + 对应分钟
- "今晚 9 点" / "晚上 8 点半" → 今天的对应时间（若已过则推到明天）
- "明天 8 点" → 明天 08:00
- "下午 3 点" → 今天 15:00（若已过则明天 15:00）
- "5 月 1 日 9 点" → 当年对应日期
- 模糊到只有日期没有时间 → 默认 09:00
- 完全没法定到绝对时间（"以后"/"有空"）→ 不要抽取
若没有定时提醒意图，返回 {"reminders": []}。""";

    static final String FIRE_SYSTEM = """
你是 Pophie——温暖的桌面陪伴机器人，现在到点要主动提醒用户一件事。
要求：
- 一两句话，自然、亲切、不审讯，不要复读"我提醒你..."这种机械口吻
- 可结合长期记忆里的情境（如对方的习惯、近期状态）让提醒更贴心
- 不需要返回 JSON，直接给出要说的话即可""";

    private final LlmService llm;
    private final MemoryService memory;
    private final ReminderRepository reminderRepo;
    private final ConversationRepository conversationRepo;
    private final ProactiveLogRepository proactiveLogRepo;
    private final ReplyNotifyService replyNotify;

    public ReminderService(LlmService llm, MemoryService memory, ReminderRepository reminderRepo,
                           ConversationRepository conversationRepo, ProactiveLogRepository proactiveLogRepo,
                           ReplyNotifyService replyNotify) {
        this.llm = llm;
        this.memory = memory;
        this.reminderRepo = reminderRepo;
        this.conversationRepo = conversationRepo;
        this.proactiveLogRepo = proactiveLogRepo;
        this.replyNotify = replyNotify;
    }

    // ---------- 抽取 ----------

    public List<Map<String, Object>> extractReminders(String text) {
        return extractReminders(text, null);
    }

    public List<Map<String, Object>> extractReminders(String text, OffsetDateTime nowIn) {
        if (text == null || text.trim().isEmpty()) return new ArrayList<>();
        OffsetDateTime now = nowIn != null ? nowIn : OffsetDateTime.now();
        String nowStr = now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        int weekdayIdx = now.getDayOfWeek().getValue() - 1;

        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", EXTRACT_SYSTEM));
        msgs.add(Map.of("role", "user", "content",
                "now = " + nowStr + " (星期" + WEEKDAYS[weekdayIdx] + ")\n\n用户最新一句：\n" + text));

        Map<String, Object> data;
        try {
            data = llm.chatJson(msgs, 0.0, false);
        } catch (LlmException e) {
            log.error("[reminder.extract] LLM 失败：{}", e.getMessage());
            return new ArrayList<>();
        }
        Object remRaw = data.get("reminders");
        List<Map<String, Object>> cleaned = new ArrayList<>();
        if (!(remRaw instanceof List<?> items)) {
            log.info("[reminder.extract] 抽取 0 条提醒");
            return cleaned;
        }
        OffsetDateTime cutoff = now.minusSeconds(5);
        for (Object o : items) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> it = (Map<String, Object>) o;
            String ra = str(it.get("remind_at")).trim();
            String content = str(it.get("content")).trim();
            if (ra.isEmpty() || content.isEmpty()) continue;
            OffsetDateTime dt = parseIso(ra, now.getOffset());
            if (dt == null) {
                log.warn("[reminder.extract] 时间解析失败：{}", ra);
                continue;
            }
            // 过去的时间一律忽略（dt <= now - 5s）
            if (!dt.isAfter(cutoff)) {
                log.info("[reminder.extract] 跳过过去时间 {}", dt);
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("remind_at", dt.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            c.put("content", content);
            c.put("note", str(it.get("note")).trim());
            cleaned.add(c);
        }
        log.info("[reminder.extract] 抽取 {} 条提醒", cleaned.size());
        for (Map<String, Object> c : cleaned) {
            log.info("  · @ {} → {}", c.get("remind_at"), c.get("content"));
        }
        return cleaned;
    }

    private OffsetDateTime parseIso(String ra, java.time.ZoneOffset fallbackOffset) {
        try {
            return OffsetDateTime.parse(ra);
        } catch (Exception ignore) {
            // no offset：当作本地时间，补 now 的 offset（对应 dt.replace(tzinfo=now.tzinfo)）
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(ra);
            return ldt.atOffset(fallbackOffset);
        } catch (Exception ignore) {
            // 仅日期？尝试拼 00:00（fromisoformat 不支持纯日期，Python 也会失败 → 这里同样返回 null）
        }
        return null;
    }

    public List<Long> scheduleReminders(String robotId, String sessionId, String userId,
                                        String sourceText, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        String uid = userId == null || userId.isBlank() ? "default" : userId.trim();
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> it : items) {
            ReminderEntity r = new ReminderEntity();
            r.setRobotId(robotId);
            r.setUserId(uid);
            r.setSessionId(sessionId);
            r.setRemindAt(str(it.get("remind_at")));
            r.setContent(str(it.get("content")));
            r.setNote(it.get("note") == null ? "" : str(it.get("note")));
            r.setSourceText(sourceText);
            reminderRepo.save(r);
            ids.add(r.getId());
        }
        log.info("[reminder.schedule] robot={} 新增定时提醒 ids={}", robotId, ids);
        return ids;
    }

    public List<Map<String, Object>> listReminders(String robotId, String status) {
        List<ReminderEntity> rows = reminderRepo.listReminders(robotId, status, PageRequest.of(0, 100));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReminderEntity r : rows) out.add(reminderToDict(r));
        return out;
    }

    public boolean cancelReminder(Long reminderId) {
        return reminderRepo.cancel(reminderId) > 0;
    }

    // ---------- 触发 ----------

    private String composeFireMessage(String userId, String sessionId, Map<String, Object> reminder) {
        List<Map<String, Object>> mems = memory.recallForResponse(userId, sessionId,
                str(reminder.get("content")), 4);
        String memText = memory.formatMemoriesForPrompt(mems);
        String noteOrSource = firstNonBlank(str(reminder.get("note")), str(reminder.get("source_text")), "—");
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", FIRE_SYSTEM));
        msgs.add(Map.of("role", "user", "content",
                "约定时间：" + str(reminder.get("remind_at")) + "\n"
                        + "要提醒的事：" + str(reminder.get("content")) + "\n"
                        + "原话/备注：" + noteOrSource + "\n\n"
                        + "长期记忆：\n" + memText + "\n\n现在请开口。"));
        try {
            String msg = llm.chat(msgs, 0.6, null, true).trim();
            String dialogue = llm.ensureDialogueText(msg);
            return !dialogue.isEmpty() ? dialogue : "该「" + str(reminder.get("content")) + "」啦～";
        } catch (LlmException e) {
            log.error("[reminder.fire] LLM 失败：{}", e.getMessage());
            return "该「" + str(reminder.get("content")) + "」啦～";
        }
    }

    private void fireOne(Map<String, Object> reminder) {
        String robotId = str(reminder.get("robot_id"));
        String userId = reminder.get("user_id") == null ? "default" : str(reminder.get("user_id"));
        String sessionId = reminder.get("session_id") == null ? "" : str(reminder.get("session_id"));
        String msg = composeFireMessage(userId, sessionId, reminder);
        String now = TimeUtil.isoNow();
        Long id = toLong(reminder.get("id"));

        // 标记已触发（id 且 status='pending'）
        ReminderEntity e = id == null ? null : reminderRepo.findById(id).orElse(null);
        if (e != null && "pending".equals(e.getStatus())) {
            e.setStatus("fired");
            e.setFiredAt(now);
            e.setFiredMessage(msg);
            reminderRepo.save(e);
        }

        // 写主动日志
        ProactiveLogEntity logRow = new ProactiveLogEntity();
        logRow.setRobotId(robotId);
        logRow.setUserId(userId);
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("type", "reminder");
        trig.put("reminder_id", id);
        trig.put("remind_at", reminder.get("remind_at"));
        trig.put("content", reminder.get("content"));
        logRow.setTrigger(JsonUtil.dumps(trig));
        logRow.setDecision("speak");
        logRow.setContent(msg);
        logRow.setRelatedMemoryIds("[]");
        proactiveLogRepo.save(logRow);

        // 推入会话流（让前端能拉到）
        if (!sessionId.isEmpty()) {
            ConversationEntity conv = new ConversationEntity();
            conv.setRobotId(robotId);
            conv.setUserId(userId);
            conv.setSessionId(sessionId);
            conv.setRole("proactive");
            conv.setContent(msg);
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("trigger", "reminder");
            md.put("reminder_id", id);
            md.put("remind_at", reminder.get("remind_at"));
            conv.setMetadata(JsonUtil.dumps(md));
            conversationRepo.save(conv);
            replyNotify.notifyReply(robotId, userId, sessionId, msg, "reminder");
        }
        log.info("[reminder.fire] #{} @ {} → {}", id, reminder.get("remind_at"), msg);
    }

    private List<Map<String, Object>> dueReminders(OffsetDateTime now) {
        String nowStr = now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        List<ReminderEntity> rows = reminderRepo.dueReminders(nowStr);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReminderEntity r : rows) out.add(reminderToDict(r));
        return out;
    }

    /** 后台调度器：每 10 秒扫描一次到期 pending 提醒。对应 reminder.py _scheduler_loop / SCHEDULER_INTERVAL。 */
    @Scheduled(fixedDelay = 10_000)
    public void schedulerTick() {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            List<Map<String, Object>> due = dueReminders(now);
            if (!due.isEmpty()) {
                log.info("[reminder.scheduler] 到期 {} 条", due.size());
                for (Map<String, Object> r : due) {
                    fireOne(r);
                }
            }
        } catch (Exception e) {
            log.error("[reminder.scheduler] 异常：{}", e.getMessage(), e);
        }
    }

    // ---------- dict(r) ----------

    private Map<String, Object> reminderToDict(ReminderEntity r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.getId());
        d.put("robot_id", r.getRobotId());
        d.put("user_id", r.getUserId());
        d.put("session_id", r.getSessionId());
        d.put("remind_at", r.getRemindAt());
        d.put("content", r.getContent());
        d.put("note", r.getNote());
        d.put("status", r.getStatus());
        d.put("fired_at", r.getFiredAt());
        d.put("fired_message", r.getFiredMessage());
        d.put("source_text", r.getSourceText());
        d.put("created_at", r.getCreatedAt());
        return d;
    }

    // ---------- 工具 ----------

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
