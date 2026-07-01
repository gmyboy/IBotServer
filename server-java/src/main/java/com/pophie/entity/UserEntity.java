package com.pophie.entity;

import com.pophie.db.DbTables;
import com.pophie.util.TimeUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 终端用户（跨设备共享记忆/对话等数据）。 */
@Entity
@Table(name = DbTables.CORE_USERS)
@Getter
@Setter
public class UserEntity {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    @PrePersist
    void onCreate() {
        String now = TimeUtil.tsNow();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = TimeUtil.tsNow();
    }
}
