package com.pophie.entity;

import com.pophie.db.DbTables;
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

/** conversations 表（对应 database.py conversations）。 */
@Entity
@Table(name = DbTables.CHAT_CONVERSATIONS, indexes = {
        @Index(name = "pb_chat_idx_session", columnList = "session_id"),
        @Index(name = "pb_chat_idx_robot_session", columnList = "robot_id,session_id"),
})
@Getter
@Setter
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "robot_id", nullable = false)
    private String robotId = "default";

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "role", nullable = false)
    private String role;                  // user / assistant / proactive

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "modality")
    private String modality = "text";

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata = "{}";

    @Column(name = "created_at")
    private String createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = TimeUtil.tsNow();
    }
}
