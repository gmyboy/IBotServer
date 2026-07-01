package com.pophie.db;

/**
 * MySQL 物理表名：{@code pb_{模块}_{表名}}。
 * <ul>
 *   <li>一级前缀 {@code pb}：Pophie 业务库</li>
 *   <li>二级前缀：core / mem / chat / rem / pro / voice</li>
 * </ul>
 */
public final class DbTables {

    private DbTables() {}

    public static final String PREFIX = "pb";

    /** 机器人与主人档案 */
    public static final String CORE_ROBOTS = "pb_core_robots";
    public static final String CORE_OWNER_PROFILES = "pb_core_owner_profiles";
    public static final String CORE_USERS = "pb_core_users";
    public static final String CORE_DEVICES = "pb_core_devices";

    /** 记忆 */
    public static final String MEM_MEMORIES = "pb_mem_memories";

    /** 对话 */
    public static final String CHAT_CONVERSATIONS = "pb_chat_conversations";

    /** 提醒 */
    public static final String REM_REMINDERS = "pb_rem_reminders";

    /** 主动感知日志 */
    public static final String PRO_PROACTIVE_LOG = "pb_pro_proactive_log";

    /** 语音段流水 */
    public static final String VOICE_SEGMENT_LOGS = "pb_voice_segment_logs";
}
