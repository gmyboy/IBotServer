-- 将无前缀旧表重命名为 pb_{模块}_{表名}（幂等，仅旧表存在且新表不存在时执行）

SET @db = DATABASE();

DROP PROCEDURE IF EXISTS pb_rename_table_if_needed;
DELIMITER //
CREATE PROCEDURE pb_rename_table_if_needed(IN old_name VARCHAR(64), IN new_name VARCHAR(64))
BEGIN
    DECLARE old_cnt INT DEFAULT 0;
    DECLARE new_cnt INT DEFAULT 0;
    SELECT COUNT(*) INTO old_cnt FROM information_schema.tables
        WHERE table_schema = @db AND table_name = old_name;
    SELECT COUNT(*) INTO new_cnt FROM information_schema.tables
        WHERE table_schema = @db AND table_name = new_name;
    IF old_cnt > 0 AND new_cnt = 0 THEN
        SET @sql = CONCAT('RENAME TABLE `', old_name, '` TO `', new_name, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

CALL pb_rename_table_if_needed('robots', 'pb_core_robots');
CALL pb_rename_table_if_needed('owner_profiles', 'pb_core_owner_profiles');
CALL pb_rename_table_if_needed('memories', 'pb_mem_memories');
CALL pb_rename_table_if_needed('conversations', 'pb_chat_conversations');
CALL pb_rename_table_if_needed('reminders', 'pb_rem_reminders');
CALL pb_rename_table_if_needed('proactive_log', 'pb_pro_proactive_log');
CALL pb_rename_table_if_needed('voice_segment_logs', 'pb_voice_segment_logs');

DROP PROCEDURE IF EXISTS pb_rename_table_if_needed;
