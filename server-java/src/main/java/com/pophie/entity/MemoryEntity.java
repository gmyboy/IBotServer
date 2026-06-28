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

/**
 * memories 表（对应 database.py memories）。
 * JSON 列（tags / flow_path / metadata）以原始文本存储，行为与 SQLite TEXT 完全一致，
 * 由业务层按需解析，可逐端点复刻"解析/不解析"的契约差异。
 */
@Entity
@Table(name = "memories", indexes = {
        @Index(name = "idx_mem_layer", columnList = "user_id,layer"),
        @Index(name = "idx_mem_session", columnList = "session_id"),
        @Index(name = "idx_mem_robot_layer", columnList = "robot_id,layer"),
        @Index(name = "idx_mem_robot_session", columnList = "robot_id,session_id"),
})
@Getter
@Setter
public class MemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "robot_id", nullable = false)
    private String robotId = "default";

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "layer", nullable = false)
    private String layer;                 // L2 / L3 / L4

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "raw_input", columnDefinition = "TEXT")
    private String rawInput;

    @Column(name = "modality")
    private String modality = "text";

    @Column(name = "emotion_score")
    private double emotionScore = 0.0;

    @Column(name = "importance")
    private double importance = 0.0;

    @Column(name = "repetition_count")
    private int repetitionCount = 1;

    @Column(name = "tags", columnDefinition = "TEXT")
    private String tags = "[]";           // JSON list

    @Column(name = "flow_path", columnDefinition = "TEXT")
    private String flowPath = "[]";       // JSON list of {layer, ts, reason}

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata = "{}";       // JSON dict

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "promoted_from")
    private Long promotedFrom;

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
