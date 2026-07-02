-- 旧库升级脚本：为 pb_core_users 和 pb_core_robots 增加新字段
-- 执行前请备份

ALTER TABLE pb_core_users
    ADD COLUMN IF NOT EXISTS nickname VARCHAR(255) NULL AFTER display_name,
    ADD COLUMN IF NOT EXISTS gender VARCHAR(32) NULL AFTER nickname,
    ADD COLUMN IF NOT EXISTS birthday VARCHAR(32) NULL AFTER gender,
    ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512) NULL AFTER birthday,
    ADD COLUMN IF NOT EXISTS voice_data LONGTEXT NULL AFTER avatar_url,
    ADD COLUMN IF NOT EXISTS voice_data_format VARCHAR(32) NULL AFTER voice_data,
    ADD COLUMN IF NOT EXISTS voice_data_sample_rate INT NULL AFTER voice_data_format,
    ADD COLUMN IF NOT EXISTS voice_enrolled BIT(1) NULL AFTER voice_data_sample_rate;

ALTER TABLE pb_core_robots
    ADD COLUMN IF NOT EXISTS persona TEXT NULL AFTER display_name,
    ADD COLUMN IF NOT EXISTS voice_id VARCHAR(255) NULL AFTER persona,
    ADD COLUMN IF NOT EXISTS voice_style TEXT NULL AFTER voice_id,
    ADD COLUMN IF NOT EXISTS greeting TEXT NULL AFTER voice_style,
    ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512) NULL AFTER greeting,
    ADD COLUMN IF NOT EXISTS language VARCHAR(32) NULL AFTER avatar_url,
    ADD COLUMN IF NOT EXISTS personality_tags TEXT NULL AFTER language,
    ADD COLUMN IF NOT EXISTS system_prompt TEXT NULL AFTER personality_tags,
    ADD COLUMN IF NOT EXISTS updated_at VARCHAR(255) NULL AFTER last_seen_at;
