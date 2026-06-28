package com.pophie.entity;

import com.pophie.util.TimeUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** owner_profiles 表（对应 database.py owner_profiles）。robot_id 作主键。 */
@Entity
@Table(name = "owner_profiles")
@Getter
@Setter
public class OwnerProfileEntity {

    @Id
    @Column(name = "robot_id")
    private String robotId;

    @Column(name = "nickname", nullable = false)
    private String nickname;

    @Column(name = "robot_name", nullable = false)
    private String robotName;

    @Column(name = "gender")
    private String gender;

    @Column(name = "birthday")
    private String birthday;

    @Column(name = "face_registered", nullable = false)
    private boolean faceRegistered = false;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    @PrePersist
    void onCreate() {
        String now = TimeUtil.tsNow();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }
}
