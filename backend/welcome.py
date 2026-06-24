"""首次激活欢迎语：主人完成注册后机器人主动自我介绍。"""
from __future__ import annotations
import json
import logging

from .database import get_conn

log = logging.getLogger("pophie.welcome")

def compose_welcome_message(owner: dict) -> str:
    nickname = owner["nickname"]
    robot_name = owner["robot_name"]
    return f"{nickname}，很高兴认识你！我是{robot_name}，以后我会一直陪在你身边～"


def send_welcome_message(robot_id: str, session_id: str, owner: dict,
                         user_id: str = "default") -> str:
    content = compose_welcome_message(owner)
    trigger = json.dumps(
        {"type": "welcome", "nickname": owner["nickname"],
         "robot_name": owner["robot_name"]},
        ensure_ascii=False,
    )
    with get_conn() as conn:
        conn.execute(
            """INSERT INTO proactive_log(robot_id, user_id, trigger, decision, content,
                related_memory_ids) VALUES(?,?,?,?,?,?)""",
            (robot_id, user_id, trigger, "speak", content, "[]"),
        )
        conn.execute(
            """INSERT INTO conversations(robot_id, user_id, session_id, role, content,
                metadata) VALUES(?,?,?,?,?,?)""",
            (robot_id, user_id, session_id, "proactive", content,
             json.dumps({"trigger": "welcome"}, ensure_ascii=False)),
        )
    log.info("[welcome] robot=%s session=%s → %r", robot_id, session_id, content)
    return content
