package com.pophie.entity;

import com.pophie.db.DbTables;
import com.pophie.util.TimeUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** robots 表（对应 database.py robots）。robot_id 作主键。 */
@Entity
@Table(name = DbTables.CORE_ROBOTS)
@Getter
@Setter
public class RobotEntity {

    @Id
    @Column(name = "robot_id")
    private String robotId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "last_seen_at")
    private String lastSeenAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = TimeUtil.tsNow();
    }
}
