package com.pophie.service;

import com.pophie.entity.DeviceEntity;
import com.pophie.entity.UserEntity;
import com.pophie.exception.ApiException;
import com.pophie.repository.DeviceRepository;
import com.pophie.repository.UserRepository;
import com.pophie.schema.DeviceBindRequest;
import com.pophie.schema.DeviceBindResponse;
import com.pophie.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 设备绑定：端侧以稳定 {@code device_id} 注册/关联用户，业务数据按 {@code user_id} 隔离。
 */
@Service
public class DeviceBindService {

    private static final Logger log = LoggerFactory.getLogger("pophie.device");

    private final UserRepository userRepo;
    private final DeviceRepository deviceRepo;
    private final RobotService robotService;

    public DeviceBindService(UserRepository userRepo, DeviceRepository deviceRepo, RobotService robotService) {
        this.userRepo = userRepo;
        this.deviceRepo = deviceRepo;
        this.robotService = robotService;
    }

    public record ResolvedIdentity(String userId, String robotId, String deviceId, boolean fromDevice) {}

    @Transactional
    public DeviceBindResponse bind(DeviceBindRequest req) {
        if (req == null) throw new ApiException(400, "请求体不能为空");
        String deviceId = trim(req.getDeviceId());
        if (deviceId == null) throw new ApiException(400, "device_id 不能为空");

        Optional<DeviceEntity> existing = deviceRepo.findById(deviceId);
        if (existing.isPresent()) {
            DeviceEntity dev = existing.get();
            dev.setLastSeenAt(TimeUtil.isoNow());
            String name = trim(req.getDeviceName());
            if (name != null) dev.setDeviceName(name);
            deviceRepo.save(dev);
            ensureUserExists(dev.getUserId());
            robotService.touchRobot(dev.getRobotId());
            log.info("[device] touch device={} user={} robot={}", deviceId, dev.getUserId(), dev.getRobotId());
            return new DeviceBindResponse(deviceId, dev.getUserId(), dev.getRobotId(), false, false);
        }

        boolean newUser = false;
        String userId = trim(req.getUserId());
        if (userId == null) {
            userId = newUserId();
            createUser(userId, trim(req.getDisplayName()));
            newUser = true;
        } else if (!userRepo.existsById(userId)) {
            createUser(userId, trim(req.getDisplayName()));
            newUser = true;
        } else {
            String displayName = trim(req.getDisplayName());
            if (displayName != null) {
                userRepo.findById(userId).ifPresent(u -> {
                    u.setDisplayName(displayName);
                    userRepo.save(u);
                });
            }
        }

        String robotId = trim(req.getRobotId());
        if (robotId == null) {
            robotId = "robot-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        robotService.touchRobot(robotId);

        DeviceEntity dev = new DeviceEntity();
        dev.setDeviceId(deviceId);
        dev.setUserId(userId);
        dev.setRobotId(robotId);
        dev.setDeviceName(trim(req.getDeviceName()));
        deviceRepo.save(dev);

        log.info("[device] bind new device={} user={} robot={} newUser={}", deviceId, userId, robotId, newUser);
        return new DeviceBindResponse(deviceId, userId, robotId, newUser, true);
    }

    public DeviceBindResponse getBinding(String deviceId) {
        String did = trim(deviceId);
        if (did == null) throw new ApiException(400, "device_id 不能为空");
        DeviceEntity dev = deviceRepo.findById(did)
                .orElseThrow(() -> new ApiException(404, "设备未绑定"));
        return new DeviceBindResponse(dev.getDeviceId(), dev.getUserId(), dev.getRobotId(), false, false);
    }

    /** 请求携带 device_id 时，以绑定关系为准覆盖 user_id / robot_id。 */
    public ResolvedIdentity resolve(String deviceId, String robotId, String userId) {
        String did = trim(deviceId);
        if (did != null) {
            DeviceEntity dev = deviceRepo.findById(did)
                    .orElseThrow(() -> new ApiException(404, "设备未绑定，请先调用 POST /api/device/bind"));
            dev.setLastSeenAt(TimeUtil.isoNow());
            deviceRepo.save(dev);
            robotService.touchRobot(dev.getRobotId());
            return new ResolvedIdentity(dev.getUserId(), dev.getRobotId(), did, true);
        }
        String rid = robotId != null && !robotId.isBlank() ? robotId.trim() : "default";
        String uid = userId == null || userId.isBlank() ? "default" : userId.trim();
        return new ResolvedIdentity(uid, rid, null, false);
    }

    public String resolveUserId(String deviceId, String userId) {
        String did = trim(deviceId);
        if (did != null) {
            return deviceRepo.findById(did)
                    .map(DeviceEntity::getUserId)
                    .orElseThrow(() -> new ApiException(404, "设备未绑定"));
        }
        return userId == null || userId.isBlank() ? "default" : userId.trim();
    }

    private void ensureUserExists(String userId) {
        if (userId == null || userId.isEmpty() || "demo".equals(userId)) return;
        if (!userRepo.existsById(userId)) {
            createUser(userId, null);
            log.info("[device] auto-created missing user={}", userId);
        }
    }

    private void createUser(String userId, String displayName) {
        UserEntity u = new UserEntity();
        u.setUserId(userId);
        u.setDisplayName(displayName);
        userRepo.save(u);
    }

    private static String newUserId() {
        return "usr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
