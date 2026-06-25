"""提醒逻辑单元测试（不调用 LLM）。"""
from datetime import datetime, timedelta, timezone

from backend.reminder import (
    _normalize_intent_cancellations,
    _normalize_reminder_item,
    compute_next_remind_at,
)


def _dt(y, m, d, h, mi, s=0):
    return datetime(y, m, d, h, mi, s, tzinfo=timezone(timedelta(hours=8)))


def _pending():
    return [
        {"id": 1, "content": "喝水", "note": "", "source_text": "每隔两分钟喝水"},
        {"id": 2, "content": "站起来活动", "note": "", "source_text": "每半小时活动"},
    ]


def test_normalize_recurring_reminder():
    now = _dt(2026, 6, 25, 10, 0)
    item = {
        "remind_at": "2026-06-25T10:02:00",
        "content": "喝水",
        "interval_minutes": 2,
        "repeat_until": "2026-06-25T23:59:59",
    }
    out = _normalize_reminder_item(item, now)
    assert out is not None
    assert out["interval_minutes"] == 2


def test_normalize_cancel_by_reminder_ids():
    spec = _normalize_intent_cancellations(
        {"cancel": True, "reminder_ids": [2], "scope": None},
        _pending(),
    )
    assert spec == {"reminder_ids": [2]}


def test_normalize_cancel_ignores_unknown_ids():
    spec = _normalize_intent_cancellations(
        {"cancel": True, "reminder_ids": [99], "scope": None},
        _pending(),
    )
    assert spec == {"scope": "session"}


def test_normalize_cancel_session_scope():
    spec = _normalize_intent_cancellations(
        {"cancel": True, "reminder_ids": [], "scope": "session"},
        _pending(),
    )
    assert spec == {"scope": "session"}


def test_normalize_cancel_no_pending():
    spec = _normalize_intent_cancellations(
        {"cancel": True, "reminder_ids": [], "scope": "session"},
        [],
    )
    assert spec is None


def test_compute_next_remind_at_within_window():
    fired = _dt(2026, 6, 25, 10, 2)
    reminder = {
        "interval_minutes": 2,
        "repeat_until": "2026-06-25T23:59:59",
    }
    assert compute_next_remind_at(reminder, fired) == "2026-06-25T10:04:00"
