package com.pophie.service;

import com.pophie.entity.OwnerProfileEntity;
import com.pophie.entity.RobotEntity;
import com.pophie.repository.ConversationRepository;
import com.pophie.repository.MemoryRepository;
import com.pophie.repository.OwnerProfileRepository;
import com.pophie.repository.ProactiveLogRepository;
import com.pophie.repository.ReminderRepository;
import com.pophie.repository.RobotRepository;
import com.pophie.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 机器人与主人档案数据访问，对应 database.py 中的相关函数。
 */
@Service
public class RobotService {

    private final RobotRepository robotRepo;
    private final MemoryRepository memoryRepo;
    private final ConversationRepository conversationRepo;
    private final ReminderRepository reminderRepo;
    private final ProactiveLogRepository proactiveLogRepo;
    private final OwnerProfileRepository ownerRepo;

    public RobotService(RobotRepository robotRepo, MemoryRepository memoryRepo,
                        ConversationRepository conversationRepo, ReminderRepository reminderRepo,
                        ProactiveLogRepository proactiveLogRepo, OwnerProfileRepository ownerRepo) {
        this.robotRepo = robotRepo;
        this.memoryRepo = memoryRepo;
        this.conversationRepo = conversationRepo;
        this.reminderRepo = reminderRepo;
        this.proactiveLogRepo = proactiveLogRepo;
        this.ownerRepo = ownerRepo;
    }

    /** 注册或更新机器人最后活跃时间（对应 touch_robot，INSERT…ON CONFLICT 更新 last_seen_at）。 */
    public void touchRobot(String robotId) {
        String now = TimeUtil.isoNow();
        RobotEntity r = robotRepo.findById(robotId).orElseGet(() -> {
            RobotEntity n = new RobotEntity();
            n.setRobotId(robotId);
            return n;
        });
        r.setLastSeenAt(now);
        robotRepo.save(r);
    }

    private Set<String> allRobotIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(robotRepo.allRobotIds());
        ids.addAll(memoryRepo.distinctRobotIds());
        ids.addAll(conversationRepo.distinctRobotIds());
        ids.addAll(reminderRepo.distinctRobotIds());
        ids.addAll(proactiveLogRepo.distinctRobotIds());
        return ids;
    }

    /** 对应 list_robots_with_stats(q)。 */
    public List<Map<String, Object>> listRobotsWithStats(String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String robotId : allRobotIds()) {
            RobotEntity meta = robotRepo.findById(robotId).orElse(null);
            String displayName = meta != null ? meta.getDisplayName() : null;
            String createdAt = meta != null ? meta.getCreatedAt() : null;
            String lastSeenAt = meta != null ? meta.getLastSeenAt() : null;

            if (q != null && !q.isEmpty()) {
                boolean match = robotId.contains(q)
                        || (displayName != null && displayName.contains(q));
                if (!match) continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("robot_id", robotId);
            row.put("display_name", displayName);
            row.put("created_at", createdAt);
            row.put("last_seen_at", lastSeenAt);
            row.put("memories_count", memoryRepo.countByRobotId(robotId));
            row.put("conversations_count", conversationRepo.countByRobotId(robotId));
            row.put("reminders_pending", reminderRepo.countByRobotIdAndStatus(robotId, "pending"));
            out.add(row);
        }
        out.sort(Comparator
                .comparing((Map<String, Object> m) -> {
                    Object v = m.get("last_seen_at");
                    return v == null ? "" : v.toString();
                }, Comparator.reverseOrder())
                .thenComparing(m -> m.get("robot_id").toString()));
        return out;
    }

    /** 对应 get_robot_detail(robot_id)。 */
    public Map<String, Object> getRobotDetail(String robotId) {
        RobotEntity meta = robotRepo.findById(robotId).orElse(null);
        long memoriesCount = memoryRepo.countByRobotId(robotId);
        long conversationsCount = conversationRepo.countByRobotId(robotId);
        long remindersPending = reminderRepo.countByRobotIdAndStatus(robotId, "pending");
        long proactiveLogCount = proactiveLogRepo.countByRobotId(robotId);
        long sessionCount = conversationRepo.countDistinctSessions(robotId);

        Map<String, Long> layerCounts = new LinkedHashMap<>();
        for (Object[] r : memoryRepo.countByLayer(robotId)) {
            layerCounts.put((String) r[0], (Long) r[1]);
        }

        if (meta == null && memoriesCount == 0 && conversationsCount == 0) {
            return null;
        }

        Map<String, Object> base = new LinkedHashMap<>();
        if (meta != null) {
            base.put("robot_id", meta.getRobotId());
            base.put("display_name", meta.getDisplayName());
            base.put("created_at", meta.getCreatedAt());
            base.put("last_seen_at", meta.getLastSeenAt());
        } else {
            base.put("robot_id", robotId);
            base.put("display_name", null);
            base.put("created_at", null);
            base.put("last_seen_at", null);
        }
        base.put("memories_count", memoriesCount);
        base.put("conversations_count", conversationsCount);
        base.put("reminders_pending", remindersPending);
        base.put("proactive_log_count", proactiveLogCount);
        base.put("session_count", sessionCount);

        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("L2", layerCounts.getOrDefault("L2", 0L));
        layers.put("L3", layerCounts.getOrDefault("L3", 0L));
        layers.put("L4", layerCounts.getOrDefault("L4", 0L));
        base.put("layer_counts", layers);
        return base;
    }

    /** 对应 update_robot_display_name。 */
    public Map<String, Object> updateRobotDisplayName(String robotId, String displayName) {
        RobotEntity r = robotRepo.findById(robotId).orElseGet(() -> {
            RobotEntity n = new RobotEntity();
            n.setRobotId(robotId);
            n.setLastSeenAt(TimeUtil.isoNow());
            return n;
        });
        r.setDisplayName(displayName);
        robotRepo.save(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("robot_id", r.getRobotId());
        out.put("display_name", r.getDisplayName());
        out.put("created_at", r.getCreatedAt());
        out.put("last_seen_at", r.getLastSeenAt());
        return out;
    }

    /** 对应 delete_robot_all（按固定表顺序删除，返回各表 rowcount）。 */
    public Map<String, Integer> deleteRobotAll(String robotId) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("memories", memoryRepo.deleteByRobotId(robotId));
        deleted.put("conversations", conversationRepo.deleteByRobotId(robotId));
        deleted.put("reminders", reminderRepo.deleteByRobotId(robotId));
        deleted.put("proactive_log", proactiveLogRepo.deleteByRobotId(robotId));
        deleted.put("owner_profiles", ownerRepo.deleteByRobotId(robotId));
        deleted.put("robots", robotRepo.existsById(robotId) ? deleteRobot(robotId) : 0);
        return deleted;
    }

    private int deleteRobot(String robotId) {
        robotRepo.deleteById(robotId);
        return 1;
    }

    // ---------- 主人档案 ----------

    /** 对应 upsert_owner_profile（幂等 upsert，同时 touch robot）。返回 5 字段子集。 */
    public Map<String, Object> upsertOwnerProfile(String robotId, Map<String, Object> profile) {
        touchRobot(robotId);
        OwnerProfileEntity o = ownerRepo.findById(robotId).orElseGet(() -> {
            OwnerProfileEntity n = new OwnerProfileEntity();
            n.setRobotId(robotId);
            return n;
        });
        o.setNickname((String) profile.get("nickname"));
        o.setRobotName((String) profile.get("robot_name"));
        o.setGender((String) profile.get("gender"));
        o.setBirthday((String) profile.get("birthday"));
        Object fr = profile.get("face_registered");
        o.setFaceRegistered(Boolean.TRUE.equals(fr));
        o.setUpdatedAt(TimeUtil.isoNow());
        ownerRepo.save(o);
        return ownerRowToDict(o);
    }

    /** 对应 get_owner_profile，返回 5 字段子集；不存在返回 null。 */
    public Map<String, Object> getOwnerProfile(String robotId) {
        Optional<OwnerProfileEntity> o = ownerRepo.findById(robotId);
        return o.map(this::ownerRowToDict).orElse(null);
    }

    /** 对应 delete_owner_profile。 */
    public boolean deleteOwnerProfile(String robotId) {
        if (!ownerRepo.existsById(robotId)) return false;
        ownerRepo.deleteById(robotId);
        return true;
    }

    private Map<String, Object> ownerRowToDict(OwnerProfileEntity o) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nickname", o.getNickname());
        d.put("robot_name", o.getRobotName());
        d.put("gender", o.getGender());
        d.put("birthday", o.getBirthday());
        d.put("face_registered", o.isFaceRegistered());
        return d;
    }
}
