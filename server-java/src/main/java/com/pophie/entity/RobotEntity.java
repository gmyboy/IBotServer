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

    /** 机器人人设：角色定位、背景故事、性格描述等。 */
    @Column(name = "persona", columnDefinition = "TEXT")
    private String persona;

    /** TTS 音色 ID（对应服务端 tts_voices 中的 voice key）。 */
    @Column(name = "voice_id")
    private String voiceId;

    /** 语音风格：语速、音调、情感倾向等参数（JSON 字符串）。 */
    @Column(name = "voice_style", columnDefinition = "TEXT")
    private String voiceStyle;

    /** 问候语：用户首次交互时的欢迎语。 */
    @Column(name = "greeting", columnDefinition = "TEXT")
    private String greeting;

    /** 机器人头像 URL。 */
    @Column(name = "avatar_url")
    private String avatarUrl;

    /** 语言偏好（如 zh-CN / en-US）。 */
    @Column(name = "language")
    private String language;

    /** 性格标签（JSON 数组，如 ["温柔","幽默","理性"]）。 */
    @Column(name = "personality_tags", columnDefinition = "TEXT")
    private String personalityTags;

    /** 系统提示词覆盖（为空时用 persona 自动生成）。 */
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "last_seen_at")
    private String lastSeenAt;

    @Column(name = "updated_at")
    private String updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = TimeUtil.tsNow();
        if (updatedAt == null) updatedAt = TimeUtil.tsNow();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = TimeUtil.tsNow();
    }
}
