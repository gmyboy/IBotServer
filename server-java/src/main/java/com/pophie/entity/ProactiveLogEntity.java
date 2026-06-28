package com.pophie.entity;

import com.pophie.util.TimeUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** proactive_log 表（对应 database.py proactive_log）。 */
@Entity
@Table(name = "proactive_log")
@Getter
@Setter
public class ProactiveLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "robot_id", nullable = false)
    private String robotId = "default";

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "trigger_text", columnDefinition = "TEXT")
    private String trigger;               // 列名 trigger 是 SQL 保留字，物理列名用 trigger_text

    @Column(name = "decision")
    private String decision;              // speak / silent

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "related_memory_ids", columnDefinition = "TEXT")
    private String relatedMemoryIds = "[]";

    @Column(name = "created_at")
    private String createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = TimeUtil.tsNow();
    }
}
