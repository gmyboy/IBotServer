package com.pophie.entity;

import com.pophie.db.DbTables;
import com.pophie.util.TimeUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 设备与用户的绑定关系（device_id 为端侧唯一标识）。 */
@Entity
@Table(name = DbTables.CORE_DEVICES, indexes = {
        @Index(name = "pb_dev_idx_user", columnList = "user_id"),
        @Index(name = "pb_dev_idx_robot", columnList = "robot_id"),
})
@Getter
@Setter
public class DeviceEntity {

    @Id
    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "robot_id", nullable = false)
    private String robotId;

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "bound_at")
    private String boundAt;

    @Column(name = "last_seen_at")
    private String lastSeenAt;

    @PrePersist
    void onCreate() {
        String now = TimeUtil.isoNow();
        if (boundAt == null) boundAt = now;
        lastSeenAt = now;
    }

    @PreUpdate
    void onUpdate() {
        lastSeenAt = TimeUtil.isoNow();
    }
}
