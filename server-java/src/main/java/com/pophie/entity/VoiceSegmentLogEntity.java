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

/** voice_segment_logs：客户端上传的语音段流水（含主人/非主人声纹标记）。 */
@Entity
@Table(name = DbTables.VOICE_SEGMENT_LOGS, indexes = {
        @Index(name = "pb_voice_idx_robot", columnList = "robot_id"),
        @Index(name = "pb_voice_idx_robot_user", columnList = "robot_id,user_id"),
        @Index(name = "pb_voice_idx_session", columnList = "session_id"),
        @Index(name = "pb_voice_idx_owner", columnList = "robot_id,is_owner"),
})
@Getter
@Setter
public class VoiceSegmentLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "robot_id", nullable = false)
    private String robotId = "default";

    @Column(name = "user_id", nullable = false)
    private String userId = "default";

    @Column(name = "session_id")
    private String sessionId;

    /** 客户端生成的段 id，用于去重/关联。 */
    @Column(name = "client_segment_id")
    private String clientSegmentId;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "sample_rate")
    private Integer sampleRate;

    @Column(name = "speaker_name")
    private String speakerName;

    @Column(name = "speaker_state")
    private String speakerState;

    @Column(name = "is_owner", nullable = false)
    private boolean owner;

    @Column(name = "confidence")
    private Float confidence;

    @Column(name = "margin")
    private Float margin;

    @Column(name = "runner_up")
    private String runnerUp;

    @Column(name = "raw_score")
    private Float rawScore;

    @Column(name = "stt_text", columnDefinition = "TEXT")
    private String sttText;

    /** client / server / none */
    @Column(name = "stt_source")
    private String sttSource = "none";

    @Column(name = "audio_format")
    private String audioFormat;

    @Column(name = "audio_encoding")
    private String audioEncoding;

    @Column(name = "audio_data", columnDefinition = "LONGTEXT")
    private String audioData;

    @Column(name = "embedding", columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata = "{}";

    @Column(name = "created_at")
    private String createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = TimeUtil.tsNow();
    }
}
