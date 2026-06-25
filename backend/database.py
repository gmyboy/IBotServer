"""SQLite 持久化层：用 JSON TEXT 列存储灵活字段，模拟 PostgreSQL JSON 用法。"""
from __future__ import annotations
import sqlite3
import json
from contextlib import contextmanager
from typing import Optional
from .config import DB_PATH

SCHEMA = """
CREATE TABLE IF NOT EXISTS memories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    robot_id TEXT NOT NULL DEFAULT 'default',
    user_id TEXT NOT NULL,
    layer TEXT NOT NULL,                 -- L2 / L3 / L4
    content TEXT NOT NULL,               -- 记忆主体（结构化文本）
    summary TEXT,                        -- 一句话摘要
    raw_input TEXT,                      -- 原始输入
    modality TEXT DEFAULT 'text',        -- text / image / audio
    emotion_score REAL DEFAULT 0.0,      -- -1~1 情感效价（绝对值越大越重要）
    importance REAL DEFAULT 0.0,         -- 0~1 综合重要度
    repetition_count INTEGER DEFAULT 1,  -- 印证次数
    tags TEXT DEFAULT '[]',              -- JSON list
    flow_path TEXT DEFAULT '[]',         -- JSON list of {layer, ts, reason}
    metadata TEXT DEFAULT '{}',          -- JSON dict
    session_id TEXT,
    promoted_from INTEGER,               -- 上一层来源 id
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_mem_layer ON memories(user_id, layer);
CREATE INDEX IF NOT EXISTS idx_mem_session ON memories(session_id);
CREATE INDEX IF NOT EXISTS idx_mem_robot_layer ON memories(robot_id, layer);
CREATE INDEX IF NOT EXISTS idx_mem_robot_session ON memories(robot_id, session_id);

CREATE TABLE IF NOT EXISTS conversations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    robot_id TEXT NOT NULL DEFAULT 'default',
    user_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,                  -- user / assistant / proactive
    content TEXT NOT NULL,
    modality TEXT DEFAULT 'text',
    metadata TEXT DEFAULT '{}',
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_conv_session ON conversations(session_id);
CREATE INDEX IF NOT EXISTS idx_conv_robot_session ON conversations(robot_id, session_id);

CREATE TABLE IF NOT EXISTS proactive_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    robot_id TEXT NOT NULL DEFAULT 'default',
    user_id TEXT NOT NULL,
    trigger TEXT,
    decision TEXT,                       -- speak / silent
    content TEXT,
    related_memory_ids TEXT DEFAULT '[]',
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS reminders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    robot_id TEXT NOT NULL DEFAULT 'default',
    user_id TEXT NOT NULL,
    session_id TEXT,
    remind_at TEXT NOT NULL,             -- ISO8601 本地时区，到点触发
    content TEXT NOT NULL,               -- 要提醒的事情（原始用户描述）
    note TEXT DEFAULT '',                -- 抽取时附加的备注/原话
    status TEXT DEFAULT 'pending',       -- pending / fired / cancelled
    fired_at TEXT,
    fired_message TEXT,                  -- 实际发出的提醒话语
    source_text TEXT,                    -- 触发本提醒的用户原话
    interval_minutes INTEGER,            -- 周期性提醒间隔（分钟）；NULL=单次
    repeat_until TEXT,                 -- 周期提醒截止时间（ISO8601）
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rem_status ON reminders(status, remind_at);
CREATE INDEX IF NOT EXISTS idx_rem_user ON reminders(user_id);
CREATE INDEX IF NOT EXISTS idx_rem_robot ON reminders(robot_id);

CREATE TABLE IF NOT EXISTS robots (
    robot_id TEXT PRIMARY KEY,
    display_name TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT
);

CREATE TABLE IF NOT EXISTS owner_profiles (
    robot_id TEXT PRIMARY KEY,
    nickname TEXT NOT NULL,
    robot_name TEXT NOT NULL,
    gender TEXT,
    birthday TEXT,
    face_registered INTEGER NOT NULL DEFAULT 0,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);
"""

_ROBOT_ID_TABLES = ("memories", "conversations", "proactive_log", "reminders")


def _migrate_robot_id(conn: sqlite3.Connection) -> None:
    """幂等迁移：为旧库添加 robot_id 列并回填。"""
    for table in _ROBOT_ID_TABLES:
        try:
            conn.execute(
                f"ALTER TABLE {table} ADD COLUMN robot_id TEXT NOT NULL DEFAULT 'default'"
            )
        except sqlite3.OperationalError:
            pass
        conn.execute(
            f"UPDATE {table} SET robot_id = user_id "
            f"WHERE robot_id = 'default' AND user_id != 'default'"
        )
    for idx_sql in (
        "CREATE INDEX IF NOT EXISTS idx_mem_robot_layer ON memories(robot_id, layer)",
        "CREATE INDEX IF NOT EXISTS idx_mem_robot_session ON memories(robot_id, session_id)",
        "CREATE INDEX IF NOT EXISTS idx_conv_robot_session ON conversations(robot_id, session_id)",
        "CREATE INDEX IF NOT EXISTS idx_rem_robot ON reminders(robot_id)",
    ):
        conn.execute(idx_sql)


def _migrate_reminder_recurring(conn: sqlite3.Connection) -> None:
    """幂等迁移：为 reminders 表添加周期性提醒字段。"""
    for col, typedef in (
        ("interval_minutes", "INTEGER"),
        ("repeat_until", "TEXT"),
    ):
        try:
            conn.execute(f"ALTER TABLE reminders ADD COLUMN {col} {typedef}")
        except sqlite3.OperationalError:
            pass


def touch_robot(conn: sqlite3.Connection, robot_id: str) -> None:
    """注册或更新机器人最后活跃时间。"""
    now = _now_iso()
    conn.execute(
        """INSERT INTO robots(robot_id, last_seen_at) VALUES(?, ?)
           ON CONFLICT(robot_id) DO UPDATE SET last_seen_at=excluded.last_seen_at""",
        (robot_id, now),
    )


def _now_iso() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def init_db():
    with get_conn() as conn:
        conn.executescript(SCHEMA)
        _migrate_robot_id(conn)
        _migrate_reminder_recurring(conn)
    # journal_mode 不能在事务内切换，须在独立连接上设置
    wal_conn = sqlite3.connect(DB_PATH)
    try:
        wal_conn.execute("PRAGMA journal_mode=WAL")
    finally:
        wal_conn.close()


@contextmanager
def get_conn():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def row_to_dict(row: sqlite3.Row) -> dict:
    d = dict(row)
    for k in ("tags", "flow_path", "metadata"):
        if k in d and isinstance(d[k], str):
            try:
                d[k] = json.loads(d[k])
            except Exception:
                d[k] = []
    if "related_memory_ids" in d and isinstance(d["related_memory_ids"], str):
        try:
            d["related_memory_ids"] = json.loads(d["related_memory_ids"])
        except Exception:
            d["related_memory_ids"] = []
    return d


_ROBOT_ID_SOURCES = """
    SELECT robot_id FROM robots
    UNION
    SELECT DISTINCT robot_id FROM memories
    UNION
    SELECT DISTINCT robot_id FROM conversations
    UNION
    SELECT DISTINCT robot_id FROM reminders
    UNION
    SELECT DISTINCT robot_id FROM proactive_log
"""


def list_robots_with_stats(q: Optional[str] = None) -> list[dict]:
    """列出所有已知 robot_id 及统计信息。"""
    sql = f"""
        SELECT
            ids.robot_id,
            r.display_name,
            r.created_at,
            r.last_seen_at,
            (SELECT COUNT(*) FROM memories m WHERE m.robot_id = ids.robot_id) AS memories_count,
            (SELECT COUNT(*) FROM conversations c WHERE c.robot_id = ids.robot_id) AS conversations_count,
            (SELECT COUNT(*) FROM reminders rem
             WHERE rem.robot_id = ids.robot_id AND rem.status = 'pending') AS reminders_pending
        FROM ({_ROBOT_ID_SOURCES}) AS ids
        LEFT JOIN robots r ON r.robot_id = ids.robot_id
    """
    args: list = []
    if q:
        sql += " WHERE ids.robot_id LIKE ? OR r.display_name LIKE ?"
        pattern = f"%{q}%"
        args.extend([pattern, pattern])
    sql += " ORDER BY COALESCE(r.last_seen_at, '') DESC, ids.robot_id ASC"
    with get_conn() as conn:
        rows = conn.execute(sql, args).fetchall()
    return [dict(r) for r in rows]


def get_robot_detail(robot_id: str) -> Optional[dict]:
    """单机器人详情与分层统计。"""
    with get_conn() as conn:
        meta = conn.execute(
            "SELECT * FROM robots WHERE robot_id=?", (robot_id,)
        ).fetchone()
        layer_rows = conn.execute(
            """SELECT layer, COUNT(*) AS cnt FROM memories
               WHERE robot_id=? GROUP BY layer""",
            (robot_id,),
        ).fetchall()
        session_count = conn.execute(
            """SELECT COUNT(DISTINCT session_id) FROM conversations
               WHERE robot_id=?""",
            (robot_id,),
        ).fetchone()[0]
        memories_count = conn.execute(
            "SELECT COUNT(*) FROM memories WHERE robot_id=?", (robot_id,)
        ).fetchone()[0]
        conversations_count = conn.execute(
            "SELECT COUNT(*) FROM conversations WHERE robot_id=?", (robot_id,)
        ).fetchone()[0]
        reminders_pending = conn.execute(
            """SELECT COUNT(*) FROM reminders
               WHERE robot_id=? AND status='pending'""",
            (robot_id,),
        ).fetchone()[0]
        proactive_log_count = conn.execute(
            "SELECT COUNT(*) FROM proactive_log WHERE robot_id=?", (robot_id,)
        ).fetchone()[0]

    if not meta and memories_count == 0 and conversations_count == 0:
        return None

    layer_counts = {r["layer"]: r["cnt"] for r in layer_rows}
    base = dict(meta) if meta else {
        "robot_id": robot_id,
        "display_name": None,
        "created_at": None,
        "last_seen_at": None,
    }
    base.update({
        "memories_count": memories_count,
        "conversations_count": conversations_count,
        "reminders_pending": reminders_pending,
        "proactive_log_count": proactive_log_count,
        "session_count": session_count,
        "layer_counts": {
            "L2": layer_counts.get("L2", 0),
            "L3": layer_counts.get("L3", 0),
            "L4": layer_counts.get("L4", 0),
        },
    })
    return base


def update_robot_display_name(robot_id: str, display_name: str) -> dict:
    """更新或创建机器人备注名。"""
    now = _now_iso()
    with get_conn() as conn:
        conn.execute(
            """INSERT INTO robots(robot_id, display_name, last_seen_at)
               VALUES(?, ?, ?)
               ON CONFLICT(robot_id) DO UPDATE SET
                   display_name=excluded.display_name""",
            (robot_id, display_name, now),
        )
        row = conn.execute(
            "SELECT * FROM robots WHERE robot_id=?", (robot_id,)
        ).fetchone()
    return dict(row)


def delete_robot_all(robot_id: str) -> dict[str, int]:
    """删除机器人全部关联数据。"""
    deleted: dict[str, int] = {}
    with get_conn() as conn:
        for table in ("memories", "conversations", "reminders", "proactive_log",
                      "owner_profiles", "robots"):
            cur = conn.execute(f"DELETE FROM {table} WHERE robot_id=?", (robot_id,))
            deleted[table] = cur.rowcount
    return deleted


def _owner_row_to_dict(row: sqlite3.Row) -> dict:
    d = dict(row)
    d["face_registered"] = bool(d.get("face_registered"))
    return d


def upsert_owner_profile(robot_id: str, profile: dict) -> dict:
    """注册或更新主人档案（幂等 upsert）。"""
    now = _now_iso()
    with get_conn() as conn:
        touch_robot(conn, robot_id)
        conn.execute(
            """INSERT INTO owner_profiles(
                   robot_id, nickname, robot_name, gender, birthday,
                   face_registered, updated_at
               ) VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(robot_id) DO UPDATE SET
                   nickname=excluded.nickname,
                   robot_name=excluded.robot_name,
                   gender=excluded.gender,
                   birthday=excluded.birthday,
                   face_registered=excluded.face_registered,
                   updated_at=excluded.updated_at""",
            (
                robot_id,
                profile["nickname"],
                profile["robot_name"],
                profile.get("gender"),
                profile.get("birthday"),
                1 if profile.get("face_registered") else 0,
                now,
            ),
        )
        row = conn.execute(
            "SELECT nickname, robot_name, gender, birthday, face_registered "
            "FROM owner_profiles WHERE robot_id=?",
            (robot_id,),
        ).fetchone()
    return _owner_row_to_dict(row)


def get_owner_profile(robot_id: str) -> Optional[dict]:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT nickname, robot_name, gender, birthday, face_registered "
            "FROM owner_profiles WHERE robot_id=?",
            (robot_id,),
        ).fetchone()
    return _owner_row_to_dict(row) if row else None


def delete_owner_profile(robot_id: str) -> bool:
    with get_conn() as conn:
        cur = conn.execute(
            "DELETE FROM owner_profiles WHERE robot_id=?", (robot_id,),
        )
    return cur.rowcount > 0
