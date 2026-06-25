"""定时提醒：让 Pophie 具备"到点主动开口"的能力。

链路：
1) parse_reminder_intents(): LLM 结合当前待提醒列表，理解用户是要新建还是取消提醒
2) 写入 reminders 表 (status=pending)；周期性提醒带 interval_minutes + repeat_until
3) 后台 asyncio 调度器每 SCHEDULER_INTERVAL 秒扫描一次到期的 pending 行，
   调 LLM 生成温暖的提醒话语，写入 conversations(role='proactive')
   + proactive_log；单次提醒标记 fired，周期提醒在 repeat_until 前自动排下次。
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
MIN_INTERVAL_MINUTES = 1
_scheduler_task: Optional[asyncio.Task] = None


INTENT_SYSTEM = """你是 Pophie 的"提醒意图解析器"。根据用户最新一句话，判断是否要：
1) 取消尚未触发的提醒
2) 新建定时/周期性提醒
二者可同时发生（例如：先停掉站起来活动，再改成每 30 分钟提醒喝水）。

你会收到 now、当前待触发提醒列表（含 id / content / 周期信息）、用户原话。
**一切语义判断由你完成，不要依赖固定词表。**

返回 JSON：
{
  "cancellations": {
    "cancel": true/false,
    "reminder_ids": [],
    "scope": null
  },
  "reminders": [
    {
      "remind_at": "YYYY-MM-DDTHH:MM:SS",
      "content": "要提醒的事情，简短一句",
      "note": "可选：用户原话片段",
      "interval_minutes": null,
      "repeat_until": null
    }
  ]
}

## 取消（cancellations）

- 用户点名要停掉某一类/某几条 → 从待提醒列表中选出语义匹配的 id 填入 reminder_ids，**不要误取消无关项**
  例：列表里有「喝水」「站起来活动」，用户说「别提醒我活动了」→ 只选站起来活动那条的 id
- 未点名具体事项、只说「结束提醒」「不要再提醒了」→ cancel=true，reminder_ids=[]，scope=session
- 「全部/所有提醒」→ scope=all
- 「今天不要再…」且未点名具体事项 → scope=today
- 单纯应答（「知道了」「好的」）、闲聊、或仅在**新建**提醒 → cancel=false

reminder_ids 与 scope 二选一为主：点名了具体提醒用 reminder_ids；批量取消用 scope。

## 新建（reminders）

只抽取**明确的定时约定**；闲聊、模糊愿望不算。

时间解析（以 now 为基准）：
- 「10 分钟后」「半小时后」→ now + 对应分钟（单次，不设 interval_minutes）
- 「今晚 9 点」「明天 8 点」等 → 对应绝对本地时间
- 只有日期无时间 → 默认 09:00
- 无法落到绝对时间 → 不要抽取

周期性提醒：
- 「每隔 N 分钟」「每 N 分钟」→ interval_minutes=N，首次 remind_at=now+N 分钟
- 「今天内」「到今天结束」→ repeat_until=当天 23:59:59
- 「接下来一小时/N 小时」→ repeat_until=now+对应时长
- 有周期但未给截止 → repeat_until=当天 23:59:59
- 间隔最小 1 分钟

若用户在同一句里主要是取消，不要顺带新建；若主要是新建，不要误判为取消。

无对应意图时：cancellations.cancel=false 且 reminders=[]。"""


def _end_of_day(dt: datetime) -> datetime:
    return dt.replace(hour=23, minute=59, second=59, microsecond=0)


def _parse_iso_dt(raw: str, tz) -> Optional[datetime]:
    try:
        dt = datetime.fromisoformat(raw.strip())
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=tz)
    return dt


def _normalize_interval(value) -> Optional[int]:
    if value is None:
        return None
    try:
        minutes = int(value)
    except (TypeError, ValueError):
        return None
    if minutes < MIN_INTERVAL_MINUTES:
        return MIN_INTERVAL_MINUTES
    return minutes


def _normalize_reminder_item(it: dict, now: datetime) -> Optional[dict]:
    ra = (it.get("remind_at") or "").strip()
    content = (it.get("content") or "").strip()
    if not ra or not content:
        return None

    dt = _parse_iso_dt(ra, now.tzinfo)
    if dt is None:
        log.warning("[reminder.extract] 时间解析失败：%r", ra)
        return None
    if dt <= now - timedelta(seconds=5):
        log.info("[reminder.extract] 跳过过去时间 %s", dt)
        return None

    interval = _normalize_interval(it.get("interval_minutes"))
    repeat_until_raw = (it.get("repeat_until") or "").strip()
    repeat_until_dt = _parse_iso_dt(repeat_until_raw, now.tzinfo) if repeat_until_raw else None

    if interval and repeat_until_dt is None:
        repeat_until_dt = _end_of_day(now)
        repeat_until_raw = repeat_until_dt.isoformat(timespec="seconds")

    if interval and repeat_until_dt and dt > repeat_until_dt:
        log.info("[reminder.extract] 跳过：首次时间 %s 已超过截止 %s", dt, repeat_until_dt)
        return None

    item = {
        "remind_at": dt.isoformat(timespec="seconds"),
        "content": content,
        "note": (it.get("note") or "").strip(),
    }
    if interval:
        item["interval_minutes"] = interval
        item["repeat_until"] = repeat_until_raw
    return item


def _format_pending_for_cancel(pending: list[dict]) -> str:
    if not pending:
        return "（当前无待触发提醒）"
    lines = []
    for p in pending:
        extra = ""
        if p.get("interval_minutes"):
            extra = f" 每{p['interval_minutes']}分钟"
            if p.get("repeat_until"):
                extra += f" 至{p['repeat_until']}"
        lines.append(
            f"- id={p['id']} content={p.get('content', '')!r}{extra}"
            f" note={p.get('note', '')!r}"
            f" source={p.get('source_text', '')!r}"
        )
    return "\n".join(lines)


def _list_pending_for_cancel(robot_id: str,
                             session_id: Optional[str] = None) -> list[dict]:
    pending = list_reminders(robot_id, status="pending", limit=50)
    if session_id:
        pending = [p for p in pending if p.get("session_id") == session_id]
    return pending


def _normalize_intent_cancellations(part: dict,
                                    pending: list[dict]) -> Optional[dict]:
    if not part or not part.get("cancel"):
        return None
    pending_ids = {int(p["id"]) for p in pending}
    ids: list[int] = []
    for rid in part.get("reminder_ids") or []:
        try:
            i = int(rid)
        except (TypeError, ValueError):
            continue
        if i in pending_ids:
            ids.append(i)
    if ids:
        return {"reminder_ids": ids}

    scope = (part.get("scope") or "").strip()
    if scope in ("session", "all", "today"):
        return {"scope": scope}
    if pending:
        return {"scope": "session"}
    return None


def parse_reminder_intents(text: str, robot_id: str, session_id: str,
                           now: Optional[datetime] = None) -> dict:
    """LLM 统一解析取消 + 新建提醒。返回 {cancel_spec, reminders}。"""
    result: dict = {"cancel_spec": None, "reminders": []}
    if not text or not text.strip():
        return result

    now = now or datetime.now().astimezone()
    pending = _list_pending_for_cancel(robot_id, session_id)
    pending_block = _format_pending_for_cancel(pending)
    weekday = ['一', '二', '三', '四', '五', '六', '日'][now.weekday()]

    msgs = [
        {"role": "system", "content": INTENT_SYSTEM},
        {"role": "user",
         "content": f"now = {now.isoformat(timespec='seconds')} (星期{weekday})\n\n"
                    f"当前待触发提醒：\n{pending_block}\n\n"
                    f"用户最新一句：\n{text}"},
    ]
    try:
        data = chat_json(msgs, temperature=0.0, user_facing=False)
    except LLMError as e:
        log.error("[reminder.intent] LLM 失败：%s", e)
        return result

    cancel_part = data.get("cancellations") or {}
    result["cancel_spec"] = _normalize_intent_cancellations(cancel_part, pending)

    for it in data.get("reminders") or []:
        norm = _normalize_reminder_item(it, now)
        if norm:
            result["reminders"].append(norm)

    if result["cancel_spec"]:
        log.info("[reminder.intent] 取消 spec=%s", result["cancel_spec"])
    if result["reminders"]:
        log.info("[reminder.intent] 新建 %d 条", len(result["reminders"]))
        for c in result["reminders"]:
            extra = ""
            if c.get("interval_minutes"):
                extra = f" (每{c['interval_minutes']}分钟，至{c['repeat_until']})"
            log.info("  · @ %s → %s%s", c["remind_at"], c["content"], extra)
    return result


def extract_reminder_cancellations(text: str,
                                   robot_id: Optional[str] = None,
                                   session_id: Optional[str] = None,
                                   now: Optional[datetime] = None) -> Optional[dict]:
    """兼容入口：仅返回取消 spec。"""
    if not robot_id or not session_id:
        return None
    parsed = parse_reminder_intents(text, robot_id, session_id, now=now)
    return parsed.get("cancel_spec")


def extract_reminders(text: str, robot_id: Optional[str] = None,
                      session_id: Optional[str] = None,
                      now: Optional[datetime] = None) -> list[dict]:
    """兼容入口：仅返回新建列表。"""
    if robot_id and session_id:
        return parse_reminder_intents(text, robot_id, session_id, now=now)["reminders"]
    if not text or not text.strip():
        return []
    now = now or datetime.now().astimezone()
    msgs = [
        {"role": "system", "content": INTENT_SYSTEM},
        {"role": "user",
         "content": f"now = {now.isoformat(timespec='seconds')}\n\n"
                    f"当前待触发提醒：\n（未提供列表）\n\n"
                    f"用户最新一句：\n{text}"},
    ]
    cleaned: list[dict] = []
    try:
        data = chat_json(msgs, temperature=0.0, user_facing=False)
        for it in data.get("reminders") or []:
            norm = _normalize_reminder_item(it, now)
            if norm:
                cleaned.append(norm)
    except LLMError as e:
        log.error("[reminder.extract] LLM 失败：%s", e)
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
                    note, source_text, interval_minutes, repeat_until)
                   VALUES(?,?,?,?,?,?,?,?,?)""",
                (robot_id, "default", session_id, it["remind_at"], it["content"],
                 it.get("note", ""), source_text,
                 it.get("interval_minutes"), it.get("repeat_until")),
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


def cancel_pending_reminders(robot_id: str, *,
                             session_id: Optional[str] = None,
                             scope: Optional[str] = None,
                             reminder_ids: Optional[list[int]] = None,
                             source_text: str = "") -> list[int]:
    """取消待触发提醒：优先按 reminder_ids 精确取消，否则按 scope。"""
    where = ["robot_id=?", "status='pending'"]
    args: list = [robot_id]

    if reminder_ids:
        placeholders = ",".join("?" * len(reminder_ids))
        where.append(f"id IN ({placeholders})")
        args.extend(reminder_ids)
        if session_id:
            where.append("session_id=?")
            args.append(session_id)
    elif scope == "all":
        pass
    elif scope == "today":
        today = datetime.now().astimezone().date().isoformat()
        where.append("(remind_at LIKE ? OR repeat_until LIKE ?)")
        args.extend([f"{today}%", f"{today}%"])
        if session_id:
            where.append("session_id=?")
            args.append(session_id)
    elif scope == "session" and session_id:
        where.append("session_id=?")
        args.append(session_id)
    else:
        return []

    clause = " AND ".join(where)
    with get_conn() as conn:
        rows = conn.execute(
            f"SELECT id, content FROM reminders WHERE {clause}", args).fetchall()
        ids = [int(r[0]) for r in rows]
        if ids:
            conn.execute(f"UPDATE reminders SET status='cancelled' WHERE {clause}", args)
    log.info("[reminder.cancel] robot=%s scope=%r ids=%r cancelled=%s contents=%s source=%r",
             robot_id, scope, reminder_ids, ids,
             [r[1] for r in rows], source_text[:80])
    return ids


def process_user_reminder_intents(robot_id: str, session_id: str,
                                  text: str) -> dict:
    """解析用户一句话中的取消/新建提醒意图并执行。返回 scheduled / cancelled 摘要。"""
    result = {"scheduled": [], "scheduled_ids": [], "cancelled_ids": []}
    if not text or not text.strip():
        return result

    parsed = parse_reminder_intents(text, robot_id, session_id)
    cancel_spec = parsed.get("cancel_spec")
    if cancel_spec:
        result["cancelled_ids"] = cancel_pending_reminders(
            robot_id,
            session_id=session_id,
            scope=cancel_spec.get("scope"),
            reminder_ids=cancel_spec.get("reminder_ids"),
            source_text=text,
        )

    rem_items = parsed.get("reminders") or []
    if rem_items:
        result["scheduled_ids"] = schedule_reminders(
            robot_id, session_id, text, rem_items)
        result["scheduled"] = rem_items
    return result


def compute_next_remind_at(reminder: dict, fired_at: datetime) -> Optional[str]:
    """若周期提醒尚未到期，返回下一次 remind_at；否则 None。"""
    interval = reminder.get("interval_minutes")
    repeat_until_raw = reminder.get("repeat_until")
    if not interval or not repeat_until_raw:
        return None
    interval = _normalize_interval(interval)
    if not interval:
        return None
    repeat_until = _parse_iso_dt(repeat_until_raw, fired_at.tzinfo)
    if repeat_until is None:
        return None
    next_at = fired_at + timedelta(minutes=interval)
    if next_at > repeat_until:
        return None
    return next_at.isoformat(timespec="seconds")


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
    recurring = ""
    if reminder.get("interval_minutes"):
        recurring = f"\n（这是周期性提醒，每 {reminder['interval_minutes']} 分钟一次）"
    msgs = [
        {"role": "system", "content": FIRE_SYSTEM},
        {"role": "user",
         "content": f"约定时间：{reminder['remind_at']}\n"
                    f"要提醒的事：{reminder['content']}\n"
                    f"原话/备注：{reminder.get('note') or reminder.get('source_text') or '—'}"
                    f"{recurring}\n\n"
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
    fired_at = datetime.now().astimezone()
    fired_iso = fired_at.isoformat(timespec="seconds")
    next_at = compute_next_remind_at(reminder, fired_at)
    trigger_meta = {
        "type": "reminder",
        "reminder_id": reminder["id"],
        "remind_at": reminder["remind_at"],
        "content": reminder["content"],
    }
    if reminder.get("interval_minutes"):
        trigger_meta["interval_minutes"] = reminder["interval_minutes"]
        trigger_meta["repeat_until"] = reminder.get("repeat_until")
        trigger_meta["rescheduled"] = bool(next_at)

    with get_conn() as conn:
        if next_at:
            conn.execute(
                """UPDATE reminders SET remind_at=?, fired_at=?, fired_message=?, status='pending'
                   WHERE id=? AND status='pending'""",
                (next_at, fired_iso, msg, reminder["id"]),
            )
        else:
            conn.execute(
                """UPDATE reminders SET status='fired', fired_at=?, fired_message=?
                   WHERE id=? AND status='pending'""",
                (fired_iso, msg, reminder["id"]),
            )
        conn.execute(
            """INSERT INTO proactive_log(robot_id, user_id, trigger, decision, content,
                related_memory_ids) VALUES(?,?,?,?,?,?)""",
            (robot_id, "default",
             json.dumps(trigger_meta, ensure_ascii=False),
             "speak", msg, "[]"),
        )
        if session_id:
            conv_meta = {
                "trigger": "reminder",
                "reminder_id": reminder["id"],
                "remind_at": reminder["remind_at"],
            }
            if next_at:
                conv_meta["next_remind_at"] = next_at
            conn.execute(
                """INSERT INTO conversations(robot_id, user_id, session_id, role, content,
                    metadata) VALUES(?,?,?,?,?,?)""",
                (robot_id, "default", session_id, "proactive", msg,
                 json.dumps(conv_meta, ensure_ascii=False)),
            )
    if next_at:
        log.info("[reminder.fire] #%s @ %s → %r；下次 @ %s",
                 reminder["id"], reminder["remind_at"], msg, next_at)
    else:
        log.info("[reminder.fire] #%s @ %s → %r（周期结束或单次完成）",
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
