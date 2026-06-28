package com.pophie.entity;

import com.pophie.util.TimeUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** reminders 表（对应 database.py reminders）。 */
@Entity
@Table(name = "reminders", indexes = {
        @Index(name = "idx_rem_status", columnList = "status,remind_at"),
        @Index(name = "idx_rem_user", columnList = "user_id"),
        @Index(name = "idx_rem_robot", columnList = "robot_id"),
})
@Getter
@Setter
public class ReminderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "robot_id", nullable = false)
    private String robotId = "default";

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "remind_at", nullable = false)
    private String remindAt;              // ISO8601 本地时区

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note = "";

    @Column(name = "status")
    private String status = "pending";    // pending / fired / cancelled

    @Column(name = "fired_at")
    private String firedAt;

    @Column(name = "fired_message", columnDefinition = "TEXT")
    private String firedMessage;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "created_at")
    private String createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = TimeUtil.tsNow();
    }
}
