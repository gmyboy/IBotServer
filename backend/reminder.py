"""定时提醒：让 Pophie 具备"到点主动开口"的能力。

链路：
1) extract_reminders(text): LLM 从用户最新一句话中识别"定时提醒/约定"，
   把相对/口语化时间（"10 分钟后"/"今晚 9 点"/"明天 8 点"）统一成绝对 ISO 时间。
2) 写入 reminders 表 (status=pending)
3) 后台 asyncio 调度器每 SCHEDULER_INTERVAL 秒扫描一次到期的 pending 行，
   调 LLM 生成温暖的提醒话语，写入 conversations(role='proactive')
   + proactive_log + 标记 fired。
4) 前端用 /api/proactive_messages?since_id=N 增量轮询拉取并展示。
"""
from __future__ import annotations
import asyncio
import json
import logging
from datetime import datetime, timedelta
from typing import Optional

from .database import get_conn, row_to_dict
from .llm import chat_json, chat, ensure_dialogue_text, LLMError
from .memory import recall_for_response, format_memories_for_prompt, _now_iso

log = logging.getLogger("pophie.reminder")

SCHEDULER_INTERVAL = 10  # 秒，扫描频率
_scheduler_task: Optional[asyncio.Task] = None


# ---------- 抽取 ----------

EXTRACT_SYSTEM = """你是 Pophie 的"提醒抽取器"。
判断用户最新一句话中是否包含"要 Pophie 在某个时间点主动提醒/叫他/通知他"的意图。
只抽取**明确的定时约定**，闲聊、模糊愿望（"以后想去旅游"）不算。

返回 JSON：
{
  "reminders": [
    {
      "remind_at": "YYYY-MM-DDTHH:MM:SS",   // 必须是绝对本地时间
      "content": "要提醒的事情，简短一句",
      "note": "可选：用户的原话/补充语境"
    }
  ]
}

时间解析规则（参考下面给出的 now 字段做基准）：
- "10 分钟后" / "半小时后" → now + 对应分钟
- "今晚 9 点" / "晚上 8 点半" → 今天的对应时间（若已过则推到明天）
- "明天 8 点" → 明天 08:00
- "下午 3 点" → 今天 15:00（若已过则明天 15:00）
- "5 月 1 日 9 点" → 当年对应日期
- 模糊到只有日期没有时间 → 默认 09:00
- 完全没法定到绝对时间（"以后"/"有空"）→ 不要抽取
若没有定时提醒意图，返回 {"reminders": []}。"""


def extract_reminders(text: str, now: Optional[datetime] = None) -> list[dict]:
    if not text or not text.strip():
        return []
    now = now or datetime.now().astimezone()
    msgs = [
        {"role": "system", "content": EXTRACT_SYSTEM},
        {"role": "user",
         "content": f"now = {now.isoformat(timespec='seconds')} "
                    f"(星期{['一','二','三','四','五','六','日'][now.weekday()]})\n\n"
                    f"用户最新一句：\n{text}"},
    ]
    try:
        data = chat_json(msgs, temperature=0.0, user_facing=False)
    except LLMError as e:
        log.error("[reminder.extract] LLM 失败：%s", e)
        return []
    items = data.get("reminders", []) or []
    cleaned: list[dict] = []
    for it in items:
        ra = (it.get("remind_at") or "").strip()
        content = (it.get("content") or "").strip()
        if not ra or not content:
            continue
        try:
            dt = datetime.fromisoformat(ra)
        except ValueError:
            log.warning("[reminder.extract] 时间解析失败：%r", ra)
            continue
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=now.tzinfo)
        # 过去的时间一律忽略（避免立即触发干扰）
        if dt <= now - timedelta(seconds=5):
            log.info("[reminder.extract] 跳过过去时间 %s", dt)
            continue
        cleaned.append({"remind_at": dt.isoformat(timespec="seconds"),
                        "content": content,
                        "note": (it.get("note") or "").strip()})
    log.info("[reminder.extract] 抽取 %d 条提醒", len(cleaned))
    for c in cleaned:
        log.info("  · @ %s → %s", c["remind_at"], c["content"])
    return cleaned


def schedule_reminders(robot_id: str, session_id: str, source_text: str,
                       items: list[dict]) -> list[int]:
    if not items:
        return []
    ids: list[int] = []
    with get_conn() as conn:
        for it in items:
            cur = conn.execute(
                """INSERT INTO reminders(robot_id, user_id, session_id, remind_at, content,
                    note, source_text) VALUES(?,?,?,?,?,?,?)""",
                (robot_id, "default", session_id, it["remind_at"], it["content"],
                 it.get("note", ""), source_text),
            )
            ids.append(cur.lastrowid)
    log.info("[reminder.schedule] robot=%s 新增定时提醒 ids=%s", robot_id, ids)
    return ids


def list_reminders(robot_id: str, status: Optional[str] = None,
                   limit: int = 100) -> list[dict]:
    sql = "SELECT * FROM reminders WHERE robot_id=?"
    args: list = [robot_id]
    if status:
        sql += " AND status=?"
        args.append(status)
    sql += " ORDER BY remind_at ASC LIMIT ?"
    args.append(limit)
    with get_conn() as conn:
        rows = conn.execute(sql, args).fetchall()
    return [dict(r) for r in rows]


def cancel_reminder(reminder_id: int) -> bool:
    with get_conn() as conn:
        cur = conn.execute(
            "UPDATE reminders SET status='cancelled' WHERE id=? AND status='pending'",
            (reminder_id,),
        )
        return cur.rowcount > 0


# ---------- 触发 ----------

FIRE_SYSTEM = """你是 Pophie——温暖的桌面陪伴机器人，现在到点要主动提醒用户一件事。
要求：
- 一两句话，自然、亲切、不审讯，不要复读"我提醒你..."这种机械口吻
- 可结合长期记忆里的情境（如对方的习惯、近期状态）让提醒更贴心
- 不需要返回 JSON，直接给出要说的话即可"""


def _compose_fire_message(robot_id: str, session_id: str, reminder: dict) -> str:
    mems = recall_for_response(robot_id, session_id,
                               query=reminder["content"], top_k=4)
    mem_text = format_memories_for_prompt(mems)
    msgs = [
        {"role": "system", "content": FIRE_SYSTEM},
        {"role": "user",
         "content": f"约定时间：{reminder['remind_at']}\n"
                    f"要提醒的事：{reminder['content']}\n"
                    f"原话/备注：{reminder.get('note') or reminder.get('source_text') or '—'}\n\n"
                    f"长期记忆：\n{mem_text}\n\n现在请开口。"},
    ]
    try:
        msg = chat(msgs, temperature=0.6).strip()
        return ensure_dialogue_text(msg) or f"该「{reminder['content']}」啦～"
    except LLMError as e:
        log.error("[reminder.fire] LLM 失败：%s", e)
        return f"该「{reminder['content']}」啦～"


def _fire_one(reminder: dict) -> None:
    robot_id = reminder["robot_id"]
    session_id = reminder["session_id"] or ""
    msg = _compose_fire_message(robot_id, session_id, reminder)
    now = _now_iso()
    with get_conn() as conn:
        # 标记已触发
        conn.execute(
            """UPDATE reminders SET status='fired', fired_at=?, fired_message=?
               WHERE id=? AND status='pending'""",
            (now, msg, reminder["id"]),
        )
        # 写主动日志
        conn.execute(
            """INSERT INTO proactive_log(robot_id, user_id, trigger, decision, content,
                related_memory_ids) VALUES(?,?,?,?,?,?)""",
            (robot_id, "default",
             json.dumps({"type": "reminder", "reminder_id": reminder["id"],
                         "remind_at": reminder["remind_at"],
                         "content": reminder["content"]},
                        ensure_ascii=False),
             "speak", msg, "[]"),
        )
        # 推入会话流（让前端能拉到）
        if session_id:
            conn.execute(
                """INSERT INTO conversations(robot_id, user_id, session_id, role, content,
                    metadata) VALUES(?,?,?,?,?,?)""",
                (robot_id, "default", session_id, "proactive", msg,
                 json.dumps({"trigger": "reminder",
                             "reminder_id": reminder["id"],
                             "remind_at": reminder["remind_at"]},
                            ensure_ascii=False)),
            )
    log.info("[reminder.fire] #%s @ %s → %r",
             reminder["id"], reminder["remind_at"], msg)


def _due_reminders(now: datetime) -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """SELECT * FROM reminders
               WHERE status='pending' AND remind_at <= ?
               ORDER BY remind_at ASC""",
            (now.isoformat(timespec="seconds"),),
        ).fetchall()
    return [dict(r) for r in rows]


async def _scheduler_loop():
    log.info("[reminder.scheduler] 启动，每 %ds 扫描一次", SCHEDULER_INTERVAL)
    while True:
        try:
            now = datetime.now().astimezone()
            due = _due_reminders(now)
            if due:
                log.info("[reminder.scheduler] 到期 %d 条", len(due))
                loop = asyncio.get_event_loop()
                for r in due:
                    # 触发是阻塞 LLM 调用，放线程池避免拖住事件循环
                    # （asyncio.to_thread 需 Python 3.9+，这里用兼容写法）
                    await loop.run_in_executor(None, _fire_one, r)
        except Exception as e:
            log.exception("[reminder.scheduler] 异常：%s", e)
        await asyncio.sleep(SCHEDULER_INTERVAL)


def start_scheduler() -> None:
    global _scheduler_task
    if _scheduler_task and not _scheduler_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        loop = asyncio.get_event_loop()
    _scheduler_task = loop.create_task(_scheduler_loop())
    log.info("[reminder.scheduler] task=%s", _scheduler_task)


def stop_scheduler() -> None:
    global _scheduler_task
    if _scheduler_task and not _scheduler_task.done():
        _scheduler_task.cancel()
        _scheduler_task = None
