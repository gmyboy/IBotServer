package com.pophie.service;

import com.pophie.entity.ConversationEntity;
import com.pophie.entity.ProactiveLogEntity;
import com.pophie.repository.ConversationRepository;
import com.pophie.repository.ProactiveLogRepository;
import com.pophie.util.JsonUtil;
import com.pophie.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动感知与主动响应（Living Loop）。逐函数对应 proactive.py。
 */
@Service
public class ProactiveService {

    private static final Logger log = LoggerFactory.getLogger("pophie.proactive");

    private final LlmService llm;
    private final MemoryService memory;
    private final ConversationRepository conversationRepo;
    private final ProactiveLogRepository proactiveLogRepo;
    private final ReplyNotifyService replyNotify;

    public ProactiveService(LlmService llm, MemoryService memory,
                            ConversationRepository conversationRepo,
                            ProactiveLogRepository proactiveLogRepo,
                            ReplyNotifyService replyNotify) {
        this.llm = llm;
        this.memory = memory;
        this.conversationRepo = conversationRepo;
        this.proactiveLogRepo = proactiveLogRepo;
        this.replyNotify = replyNotify;
    }

    public Map<String, Object> perceiveAndRespond(String robotId, String sessionId,
                                                  Map<String, Object> signal, String userId) {
        List<Map<String, Object>> mems = memory.recallForResponse(userId, sessionId, String.valueOf(signal), 6);
        log.info("[proactive] 召回 {} 条长期记忆用于决策", mems.size());
        String memText = memory.formatMemoriesForPrompt(mems);

        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", PromptConstants.PROACTIVE_SYSTEM));
        msgs.add(Map.of("role", "user", "content",
                "被动感知信号：" + JsonUtil.dumps(signal) + "\n\n可用长期记忆：\n" + memText + "\n\n请决策。"));

        Map<String, Object> result;
        try {
            result = llm.chatJson(msgs, 0.4, false);
        } catch (LlmException e) {
            result = new LinkedHashMap<>();
            result.put("decision", "silent");
            result.put("reason", "LLM 失败:" + e.getMessage());
            result.put("content", "");
            result.put("used_memory_ids", new ArrayList<>());
        }

        String decision = result.get("decision") == null ? "silent" : result.get("decision").toString();
        String content = "speak".equals(decision) && result.get("content") != null
                ? result.get("content").toString() : "";
        if (!content.isEmpty()) {
            content = llm.ensureDialogueText(content);
            if (content.isEmpty()) decision = "silent";
        }
        Object usedIdsRaw = result.get("used_memory_ids");
        List<Object> usedIds = usedIdsRaw instanceof List ? new ArrayList<>((List<?>) usedIdsRaw) : new ArrayList<>();

        ProactiveLogEntity logRow = new ProactiveLogEntity();
        logRow.setRobotId(robotId);
        logRow.setUserId(userId);
        logRow.setTrigger(JsonUtil.dumps(signal));
        logRow.setDecision(decision);
        logRow.setContent(content);
        logRow.setRelatedMemoryIds(JsonUtil.dumps(usedIds));
        proactiveLogRepo.save(logRow);

        if ("speak".equals(decision) && !content.isEmpty()) {
            ConversationEntity conv = new ConversationEntity();
            conv.setRobotId(robotId);
            conv.setUserId(userId);
            conv.setSessionId(sessionId);
            conv.setRole("proactive");
            conv.setContent(content);
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("trigger", signal);
            md.put("used_memory_ids", usedIds);
            conv.setMetadata(JsonUtil.dumps(md));
            conversationRepo.save(conv);
            replyNotify.notifyReply(robotId, userId, sessionId, content, "proactive");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", decision);
        out.put("reason", result.get("reason") == null ? "" : result.get("reason"));
        out.put("content", content);
        out.put("used_memory_ids", usedIds);
        out.put("ts", TimeUtil.isoNow());
        return out;
    }
}
