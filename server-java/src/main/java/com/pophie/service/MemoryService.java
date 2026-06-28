package com.pophie.service;

import com.pophie.config.RuntimeConfigService;
import com.pophie.entity.MemoryEntity;
import com.pophie.repository.MemoryRepository;
import com.pophie.util.JsonUtil;
import com.pophie.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 四层漏斗记忆系统（L2 工作 / L3 偏好 / L4 固化）。逐函数对应 memory.py。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger("pophie.memory");

    static final String EXTRACT_SYSTEM = """
你是 Pophie 陪伴机器人的"记忆抽取器"。
对用户最新一句话做记忆候选抽取。仅返回 JSON，结构为：
{
  "candidates": [
    {
      "summary": "一句话摘要（中文，第三人称）",
      "content": "可被长期复用的结构化记忆内容",
      "tags": ["标签1","标签2"],
      "emotion_score": -1~1 之间的情感效价,
      "importance": 0~1 之间的重要度,
      "category": "identity|preference|event|relation|habit|trivia|emotion"
    }
  ]
}
评分规则：
- 身份/家庭成员/重要日期/价值观/明确边界 → importance >= 0.9（可直跃 L4）
- 长期习惯/明确偏好/情感事件 → importance 0.55~0.85
- 一次性闲聊/天气/客套 → importance < 0.4，可不返回
情感效价（emotion_score）很重要，正负都要如实给：
- 这是陪伴机器人，情绪浓度高的时刻（无论开心的高光还是难过的低谷）哪怕「事实重要性」不高，也务必返回，
  并把 emotion_score 的绝对值打高（强烈情绪 |emotion_score| >= 0.85）。
- 例如「我和对象分手了」「我升职了好开心」这类，importance 可以中等，但 emotion_score 要充分体现强度与正负。
若无可沉淀信息，返回 {"candidates": []}。""";

    private final MemoryRepository repo;
    private final LlmService llm;
    private final RuntimeConfigService cfg;
    private final L1PerceptionBuffer l1Buffer;

    public MemoryService(MemoryRepository repo, LlmService llm, RuntimeConfigService cfg,
                         L1PerceptionBuffer l1Buffer) {
        this.repo = repo;
        this.llm = llm;
        this.cfg = cfg;
        this.l1Buffer = l1Buffer;
    }

    public L1PerceptionBuffer l1() {
        return l1Buffer;
    }

    // ---------- 工具 ----------

    private Map<String, Object> flowStep(String layer, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layer", layer);
        m.put("ts", TimeUtil.isoNow());
        m.put("reason", reason);
        return m;
    }

    /** 复合留存分：重要性与情感强度双驱动。对应 compute_retention。 */
    public double computeRetention(double importance, double emotionScore) {
        double emotionIntensity = Math.abs(emotionScore);
        double wImp = RuntimeConfigService.dbl(cfg.memory(), "importance_weight", 0.6);
        double wEmo = RuntimeConfigService.dbl(cfg.memory(), "emotion_weight", 0.5);
        return Math.min(1.0, wImp * importance + wEmo * emotionIntensity);
    }

    // ---------- 持久化 ----------

    public Long insertMemory(String robotId, String layer, String content, String summary,
                             String rawInput, String modality, double emotionScore, double importance,
                             List<Object> tags, List<Map<String, Object>> flowPath,
                             Map<String, Object> metadata, String sessionId, Long promotedFrom,
                             String userId) {
        List<Object> flow = flowPath == null ? new ArrayList<>() : new ArrayList<>(flowPath);
        if (flow.isEmpty()) {
            flow.add(flowStep("L1", "raw perception"));
        }
        Object last = flow.get(flow.size() - 1);
        boolean lastIsLayer = last instanceof Map && layer.equals(((Map<?, ?>) last).get("layer"));
        if (!lastIsLayer) {
            flow.add(flowStep(layer, "stored"));
        }
        MemoryEntity e = new MemoryEntity();
        e.setRobotId(robotId);
        e.setUserId(userId);
        e.setLayer(layer);
        e.setContent(content);
        e.setSummary(summary);
        e.setRawInput(rawInput);
        e.setModality(modality);
        e.setEmotionScore(emotionScore);
        e.setImportance(importance);
        e.setTags(JsonUtil.dumps(tags == null ? new ArrayList<>() : tags));
        e.setFlowPath(JsonUtil.dumps(flow));
        e.setMetadata(JsonUtil.dumps(metadata == null ? new LinkedHashMap<>() : metadata));
        e.setSessionId(sessionId);
        e.setPromotedFrom(promotedFrom);
        repo.save(e);
        return e.getId();
    }

    public List<Map<String, Object>> listMemories(String robotId, String layer, String sessionId, int limit) {
        List<MemoryEntity> rows = repo.listMemories(robotId, layer, sessionId, PageRequest.of(0, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MemoryEntity e : rows) out.add(memToDict(e));
        return out;
    }

    public Map<String, Object> getMemory(Long id) {
        return repo.findById(id).map(this::memToDict).orElse(null);
    }

    public boolean deleteMemory(Long id, String robotId) {
        return repo.deleteByIdAndRobotId(id, robotId) > 0;
    }

    public void updateMemoryLayer(Long memId, String newLayer, String reason,
                                  Double importance, int repetitionInc) {
        MemoryEntity e = repo.findById(memId).orElse(null);
        if (e == null) return;
        List<Object> flow = JsonUtil.parseList(e.getFlowPath());
        flow.add(flowStep(newLayer, reason));
        double newImportance = importance != null ? importance : e.getImportance();
        int newRep = e.getRepetitionCount() + repetitionInc;
        e.setLayer(newLayer);
        e.setImportance(newImportance);
        e.setRepetitionCount(newRep);
        e.setFlowPath(JsonUtil.dumps(flow));
        e.setUpdatedAt(TimeUtil.tsNow());
        repo.save(e);
    }

    public void reinforceMemory(Long memId, String reason, double importanceBoost) {
        MemoryEntity e = repo.findById(memId).orElse(null);
        if (e == null) return;
        List<Object> flow = JsonUtil.parseList(e.getFlowPath());
        flow.add(flowStep(e.getLayer(), "reinforced: " + reason));
        double newImp = Math.min(1.0, e.getImportance() + importanceBoost);
        e.setImportance(newImp);
        e.setRepetitionCount(e.getRepetitionCount() + 1);
        e.setFlowPath(JsonUtil.dumps(flow));
        e.setUpdatedAt(TimeUtil.tsNow());
        repo.save(e);
    }

    // ---------- 抽取与下沉 ----------

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractCandidates(String userText, List<Map<String, Object>> recentContext) {
        List<Map<String, Object>> ctxList = recentContext == null ? new ArrayList<>() : recentContext;
        int from = Math.max(0, ctxList.size() - 6);
        StringBuilder ctx = new StringBuilder();
        for (int i = from; i < ctxList.size(); i++) {
            Map<String, Object> m = ctxList.get(i);
            if (ctx.length() > 0) ctx.append("\n");
            ctx.append("[").append(m.get("role")).append("] ").append(m.get("content"));
        }
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", EXTRACT_SYSTEM));
        msgs.add(Map.of("role", "user",
                "content", "最近上下文：\n" + ctx + "\n\n本轮用户输入：\n" + userText));
        try {
            Map<String, Object> data = llm.chatJson(msgs, 0.1, false);
            Object c = data.get("candidates");
            List<Map<String, Object>> cands = new ArrayList<>();
            if (c instanceof List<?> l) {
                for (Object o : l) if (o instanceof Map) cands.add((Map<String, Object>) o);
            }
            log.info("[extract] LLM 抽取候选={}", cands.size());
            for (Map<String, Object> cand : cands) {
                log.info("  · cand imp={} emo={} cat={} tags={} summary={}",
                        toDouble(cand.get("importance"), 0), toDouble(cand.get("emotion_score"), 0),
                        cand.get("category"), cand.get("tags"), cand.get("summary"));
            }
            return cands;
        } catch (LlmException e) {
            log.error("[extract] 抽取失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 简易语义印证：标签交集 + 摘要子串。对应 find_similar_l3_l4。 */
    public Map<String, Object> findSimilarL3L4(String robotId, String summary, List<Object> tags) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(listMemories(robotId, "L3", null, 200));
        rows.addAll(listMemories(robotId, "L4", null, 200));
        String sLow = summary.toLowerCase();
        Set<String> tagSet = new LinkedHashSet<>();
        for (Object t : tags) tagSet.add(String.valueOf(t).toLowerCase());

        Map<String, Object> best = null;
        int bestScore = 0;
        for (Map<String, Object> r : rows) {
            int score = 0;
            String rs = strOrEmpty(r.get("summary")).toLowerCase();
            if (!rs.isEmpty() && (sLow.contains(rs) || rs.contains(sLow))) score += 2;
            Set<String> rtags = new LinkedHashSet<>();
            Object rtagsRaw = r.get("tags");
            if (rtagsRaw instanceof List<?> l) {
                for (Object t : l) rtags.add(String.valueOf(t).toLowerCase());
            }
            int inter = 0;
            for (String t : rtags) if (tagSet.contains(t)) inter++;
            score += inter;
            if (score > bestScore) {
                best = r;
                bestScore = score;
            }
        }
        return bestScore >= 2 ? best : null;
    }

    /** 主入口：处理一条用户输入。对应 ingest_user_input（严格分支顺序）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingestUserInput(String robotId, String sessionId, String text,
                                               String modality, List<Map<String, Object>> recentContext,
                                               String userId) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("role", "user");
        frame.put("modality", modality);
        frame.put("text", text);
        l1Buffer.push(robotId, frame);

        List<Map<String, Object>> candidates = extractCandidates(text, recentContext);
        List<Object> produced = new ArrayList<>();

        Map<String, Object> mem = cfg.memory();
        int l3ToL4ReinforceCount = RuntimeConfigService.integer(mem, "l3_to_l4_reinforce_count", 3);
        double l3ToL4Importance = RuntimeConfigService.dbl(mem, "l3_to_l4_importance", 0.8);
        double directL4Importance = RuntimeConfigService.dbl(mem, "direct_l4_importance", 0.9);
        double emotionDirectL4 = RuntimeConfigService.dbl(mem, "emotion_direct_l4_intensity", 0.85);
        double l2ToL3Importance = RuntimeConfigService.dbl(mem, "l2_to_l3_importance", 0.55);

        for (Map<String, Object> cand : candidates) {
            String summary = strOrEmpty(cand.get("summary")).trim();
            String content = strOrEmpty(cand.get("content")).trim();
            if (content.isEmpty()) content = summary;
            if (summary.isEmpty()) continue;
            double importance = toDouble(cand.get("importance"), 0.0);
            double emotion = toDouble(cand.get("emotion_score"), 0.0);
            double emotionIntensity = Math.abs(emotion);
            double retention = computeRetention(importance, emotion);
            List<Object> tags = (cand.get("tags") instanceof List) ? (List<Object>) cand.get("tags") : new ArrayList<>();

            List<Map<String, Object>> flow = new ArrayList<>();
            flow.add(flowStep("L1", "perception: " + modality + " input"));
            flow.add(flowStep("L2", "LLM extracted candidate"));

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("category", cand.getOrDefault("category", ""));
            meta.put("retention", round3(retention));

            Long l2Id = insertMemory(robotId, "L2", content, summary, text, modality,
                    emotion, importance, tags, flow, meta, sessionId, null, userId);

            log.info("[ingest] 写入 L2 #{} imp={} emo={} retention={} summary={}",
                    l2Id, fmt2(importance), fmt2(emotion), fmt2(retention), summary);

            Map<String, Object> existing = findSimilarL3L4(robotId, summary, tags);
            if (existing != null) {
                String exLayer = String.valueOf(existing.get("layer"));
                Object exId = existing.get("id");
                log.info("[ingest] L2#{} 命中已有 {}#{} → 强化", l2Id, exLayer, exId);
                reinforceMemory(toLong(exId), "matched by new L2", 0.08);
                String lastChar = exLayer.isEmpty() ? "" : exLayer.substring(exLayer.length() - 1);
                updateMemoryLayer(l2Id, "L2",
                        "reinforces L" + lastChar + "#" + exId, importance, 0);
                Map<String, Object> ex = getMemory(toLong(exId));
                if (ex != null && "L3".equals(ex.get("layer"))
                        && (toInt(ex.get("repetition_count")) >= l3ToL4ReinforceCount
                            || toDouble(ex.get("importance"), 0) >= l3ToL4Importance)) {
                    log.info("[ingest] L3#{} 印证累计达阈值 → 升 L4", exId);
                    updateMemoryLayer(toLong(exId), "L4",
                            "reinforced enough → consolidated",
                            Math.min(1.0, toDouble(ex.get("importance"), 0) + 0.1), 0);
                }
                produced.add(getMemory(l2Id));
                continue;
            }

            boolean emoPeak = emotionIntensity >= emotionDirectL4;
            if (importance >= directL4Importance || emoPeak) {
                String reason = !emoPeak ? "high importance → direct leap to L4"
                        : "emotion peak (|emo|=" + fmt2(emotionIntensity) + ") → direct leap to L4";
                log.info("[ingest] #{} {}", l2Id, !emoPeak ? "高重要度 → 直跃 L4"
                        : "情感峰值(|emo|=" + fmt2(emotionIntensity) + ") → 直跃 L4");
                updateMemoryLayer(l2Id, "L4", reason, null, 0);
                produced.add(getMemory(l2Id));
                continue;
            }

            if (retention >= l2ToL3Importance) {
                log.info("[ingest] #{} 留存分 ({}) >= L3 阈值 → 下沉 L3", l2Id, fmt2(retention));
                updateMemoryLayer(l2Id, "L3",
                        "retention " + fmt2(retention) + " >= threshold → sink to L3", null, 0);
                if (retention >= l3ToL4Importance) {
                    log.info("[ingest] #{} 留存分同时跨过 L4 阈值 → 继续固化", l2Id);
                    updateMemoryLayer(l2Id, "L4",
                            "retention " + fmt2(retention) + " >= L4 threshold → consolidate", null, 0);
                }
                produced.add(getMemory(l2Id));
                continue;
            }

            produced.add(getMemory(l2Id));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("l1_frames", l1Buffer.snapshot(robotId));
        result.put("produced", produced);
        return result;
    }

    /** 简易召回：L4 + L3 + 本会话 L2。对应 recall_for_response。 */
    public List<Map<String, Object>> recallForResponse(String robotId, String sessionId, String query, int topK) {
        List<Map<String, Object>> l4 = new ArrayList<>(listMemories(robotId, "L4", null, 20));
        List<Map<String, Object>> l3 = new ArrayList<>(listMemories(robotId, "L3", null, 20));
        List<Map<String, Object>> l2 = listMemories(robotId, "L2", sessionId, 10);
        double wEmo = RuntimeConfigService.dbl(cfg.memory(), "recall_emotion_weight", 0.5);

        l4.sort((a, b) -> Double.compare(recallScore(b, wEmo), recallScore(a, wEmo)));
        l3.sort((a, b) -> Double.compare(recallScore(b, wEmo), recallScore(a, wEmo)));

        List<Map<String, Object>> merged = new ArrayList<>();
        merged.addAll(l4.subList(0, Math.min(topK, l4.size())));
        merged.addAll(l3.subList(0, Math.min(topK, l3.size())));
        merged.addAll(l2.subList(0, Math.min(topK, l2.size())));
        return merged;
    }

    private double recallScore(Map<String, Object> m, double wEmo) {
        return toDouble(m.get("importance"), 0) + wEmo * Math.abs(toDouble(m.get("emotion_score"), 0));
    }

    public String formatMemoriesForPrompt(List<Map<String, Object>> mems) {
        if (mems == null || mems.isEmpty()) return "(暂无长期记忆)";
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : mems) {
            lines.add("- [" + m.get("layer") + " #" + m.get("id")
                    + " imp=" + fmt2(toDouble(m.get("importance"), 0)) + "] " + m.get("summary"));
        }
        return String.join("\n", lines);
    }

    // ---------- row_to_dict ----------

    public Map<String, Object> memToDict(MemoryEntity e) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", e.getId());
        d.put("robot_id", e.getRobotId());
        d.put("user_id", e.getUserId());
        d.put("layer", e.getLayer());
        d.put("content", e.getContent());
        d.put("summary", e.getSummary());
        d.put("raw_input", e.getRawInput());
        d.put("modality", e.getModality());
        d.put("emotion_score", e.getEmotionScore());
        d.put("importance", e.getImportance());
        d.put("repetition_count", e.getRepetitionCount());
        d.put("tags", JsonUtil.parseList(e.getTags()));
        d.put("flow_path", JsonUtil.parseList(e.getFlowPath()));
        d.put("metadata", JsonUtil.parseMap(e.getMetadata()));
        d.put("session_id", e.getSessionId());
        d.put("promoted_from", e.getPromotedFrom());
        d.put("created_at", e.getCreatedAt());
        d.put("updated_at", e.getUpdatedAt());
        return d;
    }

    // ---------- 小工具 ----------

    private static String strOrEmpty(Object o) {
        return o == null ? "" : o.toString();
    }

    private static double toDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return def; }
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private static String fmt2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
