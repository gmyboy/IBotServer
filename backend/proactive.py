"""主动感知与主动响应：
模拟 Pophie 的 "Living Loop"——给定被动感知信号(时间/姿态/静默时长/环境)，
结合 L3/L4 长期记忆，由 LLM 决策 "该静静 / 该陪陪"。
"""
from __future__ import annotations
import json
import logging
from typing import Optional

from .database import get_conn
from .llm import chat, chat_json, ensure_dialogue_text, LLMError
from .memory import recall_for_response, format_memories_for_prompt, _now_iso

log = logging.getLogger("pophie.proactive")


PROACTIVE_SYSTEM = """你是 Pophie 桌面陪伴机器人的"主动交互决策器"。
基于被动感知信号 + 长期记忆，决定是否在此刻主动开口。
原则：
- 用户专注/在多人对话/明显不希望被打扰 → 静默(silent)
- 检测到疲惫/低落/独处 + 长期偏好支持 → 主动陪伴(speak)
- 重要日期/事件临近 → 主动关怀(speak)
- 没有合适契机 → 静默
仅返回 JSON：
{
  "decision": "speak" | "silent",
  "reason": "为什么这样决定",
  "content": "若 speak，机器人要对用户亲口说的话（直接对话，禁止写分析/推理）；否则空字符串",
  "used_memory_ids": [引用到的记忆 id 列表]
}"""


def perceive_and_respond(robot_id: str, session_id: str,
                         signal: dict, user_id: str = "default") -> dict:
    """signal 示例: {time:'15:00', posture:'slouched', silence_min:30, scene:'独处'}

    端侧可在 signal 内附 present/identity/facial_expression/gesture 等连续感知字段。
    user_id 仅作溯源写入；召回仍按 robot_id。
    """
    mems = recall_for_response(robot_id, session_id, query=str(signal), top_k=6)
    log.info("[proactive] 召回 %d 条长期记忆用于决策", len(mems))
    mem_text = format_memories_for_prompt(mems)
    msgs = [
        {"role": "system", "content": PROACTIVE_SYSTEM},
        {"role": "user",
         "content": f"被动感知信号：{json.dumps(signal, ensure_ascii=False)}\n\n"
                    f"可用长期记忆：\n{mem_text}\n\n请决策。"},
    ]
    try:
        result = chat_json(msgs, temperature=0.4, user_facing=False)
    except LLMError as e:
        result = {"decision": "silent", "reason": f"LLM 失败:{e}",
                  "content": "", "used_memory_ids": []}

    decision = result.get("decision", "silent")
    content = result.get("content", "") if decision == "speak" else ""
    if content:
        content = ensure_dialogue_text(content)
        if not content:
            decision = "silent"
    used_ids = result.get("used_memory_ids", []) or []

    with get_conn() as conn:
        conn.execute(
            """INSERT INTO proactive_log(robot_id, user_id, trigger, decision, content,
                related_memory_ids) VALUES(?,?,?,?,?,?)""",
            (robot_id, user_id, json.dumps(signal, ensure_ascii=False),
             decision, content,
             json.dumps(used_ids, ensure_ascii=False)),
        )
        if decision == "speak" and content:
            conn.execute(
                """INSERT INTO conversations(robot_id, user_id, session_id, role, content,
                    metadata) VALUES(?,?,?,?,?,?)""",
                (robot_id, user_id, session_id, "proactive", content,
                 json.dumps({"trigger": signal, "used_memory_ids": used_ids},
                            ensure_ascii=False)),
            )
    return {
        "decision": decision,
        "reason": result.get("reason", ""),
        "content": content,
        "used_memory_ids": used_ids,
        "ts": _now_iso(),
    }
