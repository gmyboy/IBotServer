"""聆听期停止意图判定：关键词初筛 + 轻量 LLM 确认（宽泛、不硬编码短语列表）。

用于 STT WebSocket 在聆听期收到 final 文本时判定：用户是否要【立即停止说话 /
结束本次语音交互】。命中则经 WS 推送 stop_speaking 指令，端侧据此退出聆听。

两段式设计：
1. [maybe_stop_intent] 关键词正则初筛——只决定"是否值得花一次 LLM 调用"，宽泛匹配。
2. [confirm_stop_intent] 轻量 LLM 确认——宽泛语义判定，排除"安静的地方真好"等顺带提及。
"""
from __future__ import annotations

import logging
import re

from .llm import LLMError, _parse_json_text, chat

log = logging.getLogger("pophie.intent")

# 初筛关键词：宽泛匹配，宁可多调一次 LLM 确认，也不漏掉真实停止意图。
# 覆盖中文（安静/闭嘴/别说了/结束对话…）与常见英文（stop/shut up/quiet）。
_STOP_HINT_RE = re.compile(
    r"安静|闭嘴|住口|别(说|讲|说了|讲了|再说了|讲了|吵)|"
    r"不要说了|不用说了|不(想|要)(聊|听|说)|不想继续|"
    r"结束对话|退出对话|停止(对话|说话)?|够了|打住|歇会|歇会儿|"
    r"停下来|先别(说|讲)|stop|shut\s*up|quiet|be\s*quiet",
    re.IGNORECASE,
)


def maybe_stop_intent(text: str) -> bool:
    """关键词初筛：命中才进 LLM 确认，避免对每句 final 都调 LLM。"""
    t = (text or "").strip()
    if len(t) < 2:
        return False
    return bool(_STOP_HINT_RE.search(t))


def confirm_stop_intent(text: str) -> tuple[bool, str]:
    """轻量 LLM 确认停止意图。返回 (是否停止, 理由)。LLM 异常时降级 False（不打断正常对话）。"""
    t = (text or "").strip()
    if not t:
        return False, "空文本"
    prompt = (
        "判断用户这句话是不是在让机器人【立即停止说话 / 结束本次语音交互】"
        "（如让机器人闭嘴、安静、别说了、不想聊了、结束对话）。\n"
        '只回 JSON：{"stop": true/false, "reason":"简短理由"}\n'
        "- 用户明确要求闭嘴/安静/停下/结束/不想聊了 → stop=true\n"
        '- 用户是在正常表达内容里顺带提到（如"你别说了我来""找个安静的地方"）→ stop=false\n'
        f"用户说：{t}"
    )
    try:
        raw = chat(
            [{"role": "user", "content": prompt}],
            temperature=0.0,
            user_facing=False,
        )
        data = _parse_json_text(raw)
        stop = bool(data.get("stop"))
        reason = str(data.get("reason") or "")[:80]
        log.info("[intent] stop 确认 text=%r stop=%s reason=%s", t, stop, reason)
        return stop, reason
    except (LLMError, Exception) as e:  # noqa: BLE001 - 降级不停止
        log.warning("[intent] LLM 确认失败，降级不停止: %s", e)
        return False, f"LLM 确认失败: {e}"
