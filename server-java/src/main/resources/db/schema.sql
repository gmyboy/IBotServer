-- Pophie MySQL schema：pb_{模块}_{表名}
-- 新库直接执行；旧库请先跑 migrate_legacy_table_names.sql
-- 旧库升级请执行 migrate_add_user_robot_fields.sql

CREATE TABLE IF NOT EXISTS pb_core_robots (
    robot_id VARCHAR(255) NOT NULL PRIMARY KEY,
    display_name VARCHAR(255) NULL,
    persona TEXT NULL,
    voice_id VARCHAR(255) NULL,
    voice_style TEXT NULL,
    greeting TEXT NULL,
    avatar_url VARCHAR(512) NULL,
    language VARCHAR(32) NULL,
    personality_tags TEXT NULL,
    system_prompt TEXT NULL,
    created_at VARCHAR(255) NULL,
    last_seen_at VARCHAR(255) NULL,
    updated_at VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_core_owner_profiles (
    robot_id VARCHAR(255) NOT NULL PRIMARY KEY,
    nickname VARCHAR(255) NOT NULL,
    robot_name VARCHAR(255) NOT NULL,
    gender VARCHAR(255) NULL,
    birthday VARCHAR(255) NULL,
    face_registered BIT(1) NOT NULL,
    created_at VARCHAR(255) NULL,
    updated_at VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_core_users (
    user_id VARCHAR(255) NOT NULL PRIMARY KEY,
    display_name VARCHAR(255) NULL,
    nickname VARCHAR(255) NULL,
    gender VARCHAR(32) NULL,
    birthday VARCHAR(32) NULL,
    avatar_url VARCHAR(512) NULL,
    voice_data LONGTEXT NULL,
    voice_data_format VARCHAR(32) NULL,
    voice_data_sample_rate INT NULL,
    voice_enrolled BIT(1) NULL,
    created_at VARCHAR(255) NULL,
    updated_at VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_core_devices (
    device_id VARCHAR(255) NOT NULL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    robot_id VARCHAR(255) NOT NULL,
    device_name VARCHAR(255) NULL,
    bound_at VARCHAR(255) NULL,
    last_seen_at VARCHAR(255) NULL,
    INDEX pb_dev_idx_user (user_id),
    INDEX pb_dev_idx_robot (robot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_mem_memories (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    robot_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    layer VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    summary TEXT NULL,
    raw_input TEXT NULL,
    modality VARCHAR(255) NULL,
    emotion_score DOUBLE NULL,
    importance DOUBLE NULL,
    repetition_count INT NULL,
    tags TEXT NULL,
    flow_path TEXT NULL,
    metadata TEXT NULL,
    session_id VARCHAR(255) NULL,
    promoted_from BIGINT NULL,
    created_at VARCHAR(255) NULL,
    updated_at VARCHAR(255) NULL,
    INDEX pb_mem_idx_layer (user_id, layer),
    INDEX pb_mem_idx_session (session_id),
    INDEX pb_mem_idx_robot_layer (robot_id, layer),
    INDEX pb_mem_idx_robot_session (robot_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_chat_conversations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    robot_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    modality VARCHAR(255) NULL,
    metadata TEXT NULL,
    created_at VARCHAR(255) NULL,
    INDEX pb_chat_idx_session (session_id),
    INDEX pb_chat_idx_robot_session (robot_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_rem_reminders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    robot_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NULL,
    remind_at VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    note TEXT NULL,
    status VARCHAR(255) NULL,
    fired_at VARCHAR(255) NULL,
    fired_message TEXT NULL,
    source_text TEXT NULL,
    created_at VARCHAR(255) NULL,
    INDEX pb_rem_idx_status (status, remind_at),
    INDEX pb_rem_idx_user (user_id),
    INDEX pb_rem_idx_robot (robot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_pro_proactive_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    robot_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    trigger_text TEXT NULL,
    decision VARCHAR(255) NULL,
    content TEXT NULL,
    related_memory_ids TEXT NULL,
    created_at VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_voice_segment_logs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    robot_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NULL,
    client_segment_id VARCHAR(255) NULL,
    duration_ms BIGINT NULL,
    sample_rate INT NULL,
    speaker_name VARCHAR(255) NULL,
    speaker_state VARCHAR(255) NULL,
    is_owner BIT(1) NOT NULL,
    confidence FLOAT NULL,
    margin FLOAT NULL,
    runner_up VARCHAR(255) NULL,
    raw_score FLOAT NULL,
    stt_text TEXT NULL,
    stt_source VARCHAR(255) NULL,
    audio_format VARCHAR(255) NULL,
    audio_encoding VARCHAR(255) NULL,
    audio_data LONGTEXT NULL,
    embedding TEXT NULL,
    metadata TEXT NULL,
    created_at VARCHAR(255) NULL,
    INDEX pb_voice_idx_robot (robot_id),
    INDEX pb_voice_idx_robot_user (robot_id, user_id),
    INDEX pb_voice_idx_session (session_id),
    INDEX pb_voice_idx_owner (robot_id, is_owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
