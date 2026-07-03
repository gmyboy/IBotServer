package com.pophie.service;

import com.pophie.entity.DeviceEntity;
import com.pophie.entity.UserEntity;
import com.pophie.exception.ApiException;
import com.pophie.repository.DeviceRepository;
import com.pophie.repository.UserRepository;
import com.pophie.schema.UserProfileRequest;
import com.pophie.schema.UserProfileResponse;
import com.pophie.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户档案管理：昵称、性别、生日、头像、声纹原始数据等。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger("pophie.user");

    private final UserRepository userRepo;
    private final DeviceRepository deviceRepo;

    public UserService(UserRepository userRepo, DeviceRepository deviceRepo) {
        this.userRepo = userRepo;
        this.deviceRepo = deviceRepo;
    }

    /** 获取用户档案（不含声纹原始数据，避免大字段传输）。 */
    public UserProfileResponse getProfile(String userId) {
        UserEntity u = requireUser(userId);
        return toResponse(u);
    }

    /** 更新用户档案（部分更新，仅覆盖非 null 字段）。 */
    @Transactional
    public UserProfileResponse updateProfile(String userId, UserProfileRequest req) {
        UserEntity u = requireUser(userId);
        boolean changed = false;

        if (req.getNickname() != null) {
            u.setNickname(req.getNickname().trim());
            changed = true;
        }
        if (req.getGender() != null) {
            String g = req.getGender().trim().toLowerCase();
            if (!g.equals("male") && !g.equals("female") && !g.equals("other")) {
                throw new ApiException(400, "gender must be one of: male, female, other");
            }
            u.setGender(g);
            changed = true;
        }
        if (req.getBirthday() != null) {
            String b = req.getBirthday().trim();
            if (!b.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                throw new ApiException(400, "birthday must be yyyy-MM-dd");
            }
            u.setBirthday(b);
            changed = true;
        }
        if (req.getAvatarUrl() != null) {
            u.setAvatarUrl(req.getAvatarUrl().trim());
            changed = true;
        }
        if (req.getVoiceData() != null) {
            u.setVoiceData(req.getVoiceData());
            u.setVoiceDataFormat(req.getVoiceDataFormat() != null ? req.getVoiceDataFormat() : "wav");
            u.setVoiceDataSampleRate(req.getVoiceDataSampleRate() != null ? req.getVoiceDataSampleRate() : 16000);
            u.setVoiceEnrolled(true);
            changed = true;
            log.info("[user] voice enrolled for user={}", userId);
        }
        if (req.getVoiceEnrolled() != null) {
            u.setVoiceEnrolled(req.getVoiceEnrolled());
            changed = true;
        }

        if (changed) {
            userRepo.save(u);
        }
        return toResponse(u);
    }

    /** 获取主人声纹原始音频（base64）。 */
    public Map<String, Object> getVoiceData(String userId) {
        UserEntity u = requireUser(userId);
        if (u.getVoiceData() == null || u.getVoiceData().isEmpty()) {
            throw new ApiException(404, "未录入声纹数据");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", u.getUserId());
        m.put("voice_data", u.getVoiceData());
        m.put("voice_data_format", u.getVoiceDataFormat());
        m.put("voice_data_sample_rate", u.getVoiceDataSampleRate());
        m.put("voice_enrolled", Boolean.TRUE.equals(u.getVoiceEnrolled()));
        return m;
    }

    /** 删除声纹原始数据。 */
    @Transactional
    public Map<String, Object> deleteVoiceData(String userId) {
        UserEntity u = requireUser(userId);
        u.setVoiceData(null);
        u.setVoiceDataFormat(null);
        u.setVoiceDataSampleRate(null);
        u.setVoiceEnrolled(false);
        userRepo.save(u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("user_id", userId);
        return m;
    }

    /** 查询用户绑定的所有机器人列表。 */
    public List<Map<String, Object>> listBoundRobots(String userId) {
        requireUser(userId);
        List<DeviceEntity> devices = deviceRepo.findByUserIdOrderByBoundAtAsc(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DeviceEntity dev : devices) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("device_id", dev.getDeviceId());
            m.put("robot_id", dev.getRobotId());
            m.put("device_name", dev.getDeviceName());
            m.put("bound_at", dev.getBoundAt());
            m.put("last_seen_at", dev.getLastSeenAt());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> listUsersWithStats(String q) {
        List<UserEntity> users;
        if (q != null && !q.trim().isEmpty()) {
            String like = "%" + q.trim() + "%";
            users = userRepo.findAll();
        java.util.List<UserEntity> filtered = new ArrayList<>();
        for (UserEntity u : users) {
            boolean match = false;
            if (u.getNickname() != null && u.getNickname().contains(q.trim())) match = true;
            if (u.getUserId() != null && u.getUserId().contains(q.trim())) match = true;
            if (match) filtered.add(u);
        }
        users = filtered;
        } else {
            users = userRepo.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserEntity u : users) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", u.getUserId());
            m.put("display_name", u.getDisplayName());
            m.put("nickname", u.getNickname());
            m.put("gender", u.getGender());
            m.put("avatar_url", u.getAvatarUrl());
            m.put("voice_enrolled", Boolean.TRUE.equals(u.getVoiceEnrolled()));
            m.put("created_at", u.getCreatedAt());
            m.put("last_seen_at", u.getUpdatedAt());
            m.put("devices_count", deviceRepo.countByUserId(u.getUserId()));
            List<DeviceEntity> devs = deviceRepo.findByUserIdOrderByBoundAtAsc(u.getUserId());
            List<String> robotIds = new ArrayList<>();
            for (DeviceEntity d : devs) {
                if (d.getRobotId() != null && !robotIds.contains(d.getRobotId())) {
                    robotIds.add(d.getRobotId());
                }
            }
            m.put("robot_ids", robotIds);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> getUserDetail(String userId) {
        UserEntity u = requireUser(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", u.getUserId());
        m.put("display_name", u.getDisplayName());
        m.put("nickname", u.getNickname());
        m.put("gender", u.getGender());
        m.put("birthday", u.getBirthday());
        m.put("avatar_url", u.getAvatarUrl());
        m.put("voice_enrolled", Boolean.TRUE.equals(u.getVoiceEnrolled()));
        m.put("voice_data_format", u.getVoiceDataFormat());
        m.put("voice_data_sample_rate", u.getVoiceDataSampleRate());
        m.put("created_at", u.getCreatedAt());
        m.put("updated_at", u.getUpdatedAt());
        m.put("devices", listBoundRobots(userId));
        return m;
    }

    private UserEntity requireUser(String userId) {
        return userRepo.findById(userId)
                .orElseThrow(() -> new ApiException(404, "用户不存在: " + userId));
    }

    private UserProfileResponse toResponse(UserEntity u) {
        return new UserProfileResponse(
                u.getUserId(),
                u.getDisplayName(),
                u.getNickname(),
                u.getGender(),
                u.getBirthday(),
                u.getAvatarUrl(),
                Boolean.TRUE.equals(u.getVoiceEnrolled()),
                u.getCreatedAt(),
                u.getUpdatedAt()
        );
    }
}
