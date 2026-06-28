package com.pophie.util;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 时间字符串工具，复刻 Python 端两种时间格式：
 * - tsNow(): "yyyy-MM-dd HH:mm:ss"，对应 SQLite CURRENT_TIMESTAMP（memories/conversations/reminders.created_at 等）
 * - isoNow(): ISO8601 带本地时区偏移、秒精度，对应 Python _now_iso()
 *   （reminders.fired_at、robots.last_seen_at、owner_profiles.updated_at 等）
 */
public final class TimeUtil {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeUtil() {}

    /** 对应 SQLite CURRENT_TIMESTAMP（UTC）。SQLite 默认存 UTC，这里同样取 UTC 保持一致。 */
    public static String tsNow() {
        return TS.format(ZonedDateTime.now(java.time.ZoneOffset.UTC));
    }

    /** 对应 Python datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")。 */
    public static String isoNow() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
