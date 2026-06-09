"""
四层漏斗记忆系统 (L1 感知 / L2 工作 / L3 偏好 / L4 固化)。

下沉规则：
- L1：每次输入产生瞬时感知帧，仅在内存 deque 中保留最近若干帧，不入库。
- L2：每轮对话由 LLM 抽取「候选记忆」(候选事实/偏好/情绪)，写入 L2，会话级。
- L2 → L3：当候选 importance >= L2_TO_L3 阈值，或同一语义在历史中出现过(印证)，下沉为 L3。
- L3 巩固：相同语义再次命中 → repetition_count +1，importance 增强。
- L3 → L4：repetition_count 达阈值，或单条 importance 达 L3_TO_L4 阈值。
- 直跃 L4：importance 超过 direct_l4 阈值（用户显式声明、重大事件、身份信息）。

每条记忆都有 flow_path：[{layer, ts, reason}]，完整记录从 L1 到当前层级的流转链路。
"""
from __future__ import annotations
import json
import logging
import time
import threading
from collections import deque
from datetime import datetime, timezone
from typing import Optional

from .database import get_conn, row_to_dict
from .llm import chat_json, LLMError
from .config import MEM_CFG

log = logging.getLogger("pophie.memory")


# ============ L1：感知层（瞬时，进程内） ============

class L1PerceptionBuffer:
    """瞬时多模态感知缓存：仅当前帧/最近若干帧，转瞬即逝。"""

    def __init__(self, capacity: int = 8):
        self._buf: dict[str, deque] = {}
        self._lock = threading.Lock()
        self.capacity = capacity

    def push(self, robot_id: str, frame: dict) -> dict:
        frame = dict(frame)
        frame.setdefault("ts", _now_iso())
        frame.setdefault("layer", "L1")
        with self._lock:
            self._buf.setdefault(robot_id, deque(maxlen=self.capacity)).append(frame)
        return frame

    def snapshot(self, robot_id: str) -> list[dict]:
        with self._lock:
            return list(self._buf.get(robot_id, []))

    def clear(self, robot_id: str) -> None:
        with self._lock:
            self._buf.pop(robot_id, None)


L1_BUFFER = L1PerceptionBuffer()


# ============ 工具 ============

def _now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _flow_step(layer: str, reason: str) -> dict:
    return {"layer": layer, "ts": _now_iso(), "reason": reason}


def compute_retention(importance: float, emotion_score: float) -> float:
    """复合留存分：重要性与情感强度双驱动。

    陪伴机器人不只记「重要的事」，更要记「情绪浓度高的事」。
    情感强度取效价绝对值，正负皆算（开心的高光与难过的低谷同等值得被记住）。
    """
    emotion_intensity = abs(emotion_score)
    w_imp = float(MEM_CFG.get("importance_weight", 0.6))
    w_emo = float(MEM_CFG.get("emotion_weight", 0.5))
    return min(1.0, w_imp * importance + w_emo * emotion_intensity)


# ============ L2/L3/L4：DB 持久化 ============

def insert_memory(robot_id: str, layer: str, *, content: str, summary: str = "",
                  raw_input: str = "", modality: str = "text",
                  emotion_score: float = 0.0, importance: float = 0.0,
                  tags: Optional[list] = None, flow_path: Optional[list] = None,
                  metadata: Optional[dict] = None, session_id: Optional[str] = None,
                  promoted_from: Optional[int] = None,
                  user_id: str = "default") -> int:
    flow_path = flow_path or [_flow_step("L1", "raw perception")]
    if not flow_path or flow_path[-1]["layer"] != layer:
        flow_path = flow_path + [_flow_step(layer, "stored")]
    with get_conn() as conn:
        cur = conn.execute(
            """INSERT INTO memories(robot_id, user_id, layer, content, summary, raw_input,
                modality, emotion_score, importance, tags, flow_path, metadata,
                session_id, promoted_from)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (robot_id, user_id, layer, content, summary, raw_input, modality,
             emotion_score, importance,
             json.dumps(tags or [], ensure_ascii=False),
             json.dumps(flow_path, ensure_ascii=False),
             json.dumps(metadata or {}, ensure_ascii=False),
             session_id, promoted_from),
        )
        return cur.lastrowid


def list_memories(robot_id: str, layer: Optional[str] = None,
                  session_id: Optional[str] = None, limit: int = 200) -> list[dict]:
    sql = "SELECT * FROM memories WHERE robot_id=?"
    args: list = [robot_id]
    if layer:
        sql += " AND layer=?"
        args.append(layer)
    if session_id:
        sql += " AND session_id=?"
        args.append(session_id)
    sql += " ORDER BY id DESC LIMIT ?"
    args.append(limit)
    with get_conn() as conn:
        rows = conn.execute(sql, args).fetchall()
    return [row_to_dict(r) for r in rows]


def get_memory(mem_id: int) -> Optional[dict]:
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM memories WHERE id=?", (mem_id,)).fetchone()
    return row_to_dict(row) if row else None


def delete_memory(mem_id: int, robot_id: str) -> bool:
    with get_conn() as conn:
        cur = conn.execute(
            "DELETE FROM memories WHERE id=? AND robot_id=?",
            (mem_id, robot_id),
        )
    return cur.rowcount > 0


def update_memory_layer(mem_id: int, *, new_layer: str, reason: str,
                        importance: Optional[float] = None,
                        repetition_inc: int = 0):
    mem = get_memory(mem_id)
    if not mem:
        return
    flow = mem["flow_path"] + [_flow_step(new_layer, reason)]
    new_importance = importance if importance is not None else mem["importance"]
    new_rep = mem["repetition_count"] + repetition_inc
    with get_conn() as conn:
        conn.execute(
            """UPDATE memories
               SET layer=?, importance=?, repetition_count=?, flow_path=?,
                   updated_at=CURRENT_TIMESTAMP
               WHERE id=?""",
            (new_layer, new_importance, new_rep,
             json.dumps(flow, ensure_ascii=False), mem_id),
        )


def reinforce_memory(mem_id: int, reason: str, importance_boost: float = 0.05):
    mem = get_memory(mem_id)
    if not mem:
        return
    flow = mem["flow_path"] + [_flow_step(mem["layer"], f"reinforced: {reason}")]
    new_imp = min(1.0, mem["importance"] + importance_boost)
    with get_conn() as conn:
        conn.execute(
            """UPDATE memories
               SET importance=?, repetition_count=repetition_count+1,
                   flow_path=?, updated_at=CURRENT_TIMESTAMP
               WHERE id=?""",
            (new_imp, json.dumps(flow, ensure_ascii=False), mem_id),
        )


# ============ 抽取与下沉 ============

EXTRACT_SYSTEM = """你是 Pophie 陪伴机器人的"记忆抽取器"。
对用户最新一句话做记忆候选抽取。仅返回 JSON，结构为：
{
  "candidates": [
    {
      "summary": "一句话摘要（中文，第三人称）",
      "content": "可被长期复用的结构化记忆内容",
      "tags": ["标签1","标签2"],
      "emotion_score": -1~1 之间的情感效价,
      "importance": 0~1 之间的重要度,
      "category": "identity|preference|event|relation|habit|trivia|emotion"
    }
  ]
}
评分规则：
- 身份/家庭成员/重要日期/价值观/明确边界 → importance >= 0.9（可直跃 L4）
- 长期习惯/明确偏好/情感事件 → importance 0.55~0.85
- 一次性闲聊/天气/客套 → importance < 0.4，可不返回
情感效价（emotion_score）很重要，正负都要如实给：
- 这是陪伴机器人，情绪浓度高的时刻（无论开心的高光还是难过的低谷）哪怕「事实重要性」不高，也务必返回，
  并把 emotion_score 的绝对值打高（强烈情绪 |emotion_score| >= 0.85）。
- 例如「我和对象分手了」「我升职了好开心」这类，importance 可以中等，但 emotion_score 要充分体现强度与正负。
若无可沉淀信息，返回 {"candidates": []}。"""


def extract_candidates(user_text: str, recent_context: list[dict]) -> list[dict]:
    """让 LLM 从用户输入中抽取候选记忆。"""
    ctx = "\n".join(f"[{m['role']}] {m['content']}" for m in recent_context[-6:])
    msgs = [
        {"role": "system", "content": EXTRACT_SYSTEM},
        {"role": "user",
         "content": f"最近上下文：\n{ctx}\n\n本轮用户输入：\n{user_text}"},
    ]
    try:
        data = chat_json(msgs, temperature=0.1, user_facing=False)
        cands = data.get("candidates", []) or []
        log.info("[extract] LLM 抽取候选=%d", len(cands))
        for c in cands:
            log.info("  · cand imp=%.2f emo=%.2f cat=%s tags=%s summary=%r",
                     float(c.get("importance", 0)), float(c.get("emotion_score", 0)),
                     c.get("category"), c.get("tags"), c.get("summary"))
        return cands
    except LLMError as e:
        log.error("[extract] 抽取失败：%s", e)
        return []


def find_similar_l3_l4(robot_id: str, summary: str, tags: list[str]) -> Optional[dict]:
    """简易语义印证：通过标签交集 + 摘要子串判定是否为同一语义。"""
    rows = list_memories(robot_id, layer="L3") + list_memories(robot_id, layer="L4")
    s_low = summary.lower()
    tag_set = {t.lower() for t in tags}
    best = None
    best_score = 0
    for r in rows:
        score = 0
        rs = (r.get("summary") or "").lower()
        if rs and (rs in s_low or s_low in rs):
            score += 2
        rtags = {t.lower() for t in (r.get("tags") or [])}
        score += len(rtags & tag_set)
        if score > best_score:
            best, best_score = r, score
    return best if best_score >= 2 else None


def ingest_user_input(robot_id: str, session_id: str, text: str,
                      modality: str = "text",
                      recent_context: Optional[list[dict]] = None,
                      user_id: str = "default") -> dict:
    """
    主入口：处理一条用户输入。
    1) 推入 L1 缓冲
    2) LLM 抽取候选 → 写 L2，并按规则下沉到 L3 / L4
    返回本轮产生的所有记忆条目摘要（含 flow_path 标识）。

    user_id 仅作溯源写入（端侧身份）；召回仍按 robot_id 隔离，不分人。
    """
    L1_BUFFER.push(robot_id, {"role": "user", "modality": modality, "text": text})

    candidates = extract_candidates(text, recent_context or [])
    produced: list[dict] = []

    for cand in candidates:
        summary = cand.get("summary", "").strip()
        content = cand.get("content", "").strip() or summary
        if not summary:
            continue
        importance = float(cand.get("importance", 0.0))
        emotion = float(cand.get("emotion_score", 0.0))
        emotion_intensity = abs(emotion)
        retention = compute_retention(importance, emotion)
        tags = cand.get("tags", []) or []

        flow = [_flow_step("L1", f"perception: {modality} input"),
                _flow_step("L2", "LLM extracted candidate")]

        # 写入 L2
        l2_id = insert_memory(
            robot_id, "L2",
            content=content, summary=summary, raw_input=text,
            modality=modality, emotion_score=emotion, importance=importance,
            tags=tags, flow_path=flow, session_id=session_id,
            metadata={"category": cand.get("category", ""),
                      "retention": round(retention, 3)},
            user_id=user_id,
        )

        log.info("[ingest] 写入 L2 #%s imp=%.2f emo=%.2f retention=%.2f summary=%r",
                 l2_id, importance, emotion, retention, summary)

        # 印证：与 L3/L4 已有语义匹配？→ 强化
        existing = find_similar_l3_l4(robot_id, summary, tags)
        if existing:
            log.info("[ingest] L2#%s 命中已有 %s#%s → 强化",
                     l2_id, existing["layer"], existing["id"])
            reinforce_memory(existing["id"], "matched by new L2", importance_boost=0.08)
            # 同步把 L2 的 flow_path 推进
            update_memory_layer(l2_id, new_layer="L2",
                                reason=f"reinforces L{existing['layer'][-1]}#{existing['id']}",
                                importance=importance)
            # L3 命中达阈值 → 升 L4
            ex = get_memory(existing["id"])
            if ex and ex["layer"] == "L3" and (
                ex["repetition_count"] >= MEM_CFG["l3_to_l4_reinforce_count"]
                or ex["importance"] >= MEM_CFG["l3_to_l4_importance"]
            ):
                log.info("[ingest] L3#%s 印证累计达阈值 → 升 L4", existing["id"])
                update_memory_layer(existing["id"], new_layer="L4",
                                    reason="reinforced enough → consolidated",
                                    importance=min(1.0, ex["importance"] + 0.1))
            produced.append(get_memory(l2_id))
            continue

        # 直跃 L4：高重要度，或情感强度达峰值（情感锚点，正负皆可）
        emo_peak = emotion_intensity >= float(
            MEM_CFG.get("emotion_direct_l4_intensity", 0.85))
        if importance >= MEM_CFG["direct_l4_importance"] or emo_peak:
            reason = ("high importance → direct leap to L4" if not emo_peak
                      else f"emotion peak (|emo|={emotion_intensity:.2f}) "
                           "→ direct leap to L4")
            log.info("[ingest] #%s %s", l2_id,
                     "高重要度 → 直跃 L4" if not emo_peak
                     else f"情感峰值(|emo|={emotion_intensity:.2f}) → 直跃 L4")
            update_memory_layer(l2_id, new_layer="L4", reason=reason)
            produced.append(get_memory(l2_id))
            continue

        # L2 → L3：由复合留存分驱动（重要性 + 情感强度）
        if retention >= MEM_CFG["l2_to_l3_importance"]:
            log.info("[ingest] #%s 留存分 (%.2f) >= L3 阈值 → 下沉 L3",
                     l2_id, retention)
            update_memory_layer(l2_id, new_layer="L3",
                                reason=f"retention {retention:.2f} >= threshold "
                                       "→ sink to L3")
            if retention >= MEM_CFG["l3_to_l4_importance"]:
                log.info("[ingest] #%s 留存分同时跨过 L4 阈值 → 继续固化", l2_id)
                update_memory_layer(l2_id, new_layer="L4",
                                    reason=f"retention {retention:.2f} >= L4 "
                                           "threshold → consolidate")
            produced.append(get_memory(l2_id))
            continue

        produced.append(get_memory(l2_id))

    return {
        "l1_frames": L1_BUFFER.snapshot(robot_id),
        "produced": produced,
    }


# ============ 记忆召回（注入 Prompt） ============

def recall_for_response(robot_id: str, session_id: str, query: str,
                        top_k: int = 8) -> list[dict]:
    """
    简易召回：直接取用户全部 L4 + L3（按 importance/最近 desc），
    再叠加本会话 L2 候选。生产环境可换向量检索。
    """
    l4 = list_memories(robot_id, layer="L4", limit=20)
    l3 = list_memories(robot_id, layer="L3", limit=20)
    l2 = list_memories(robot_id, layer="L2", session_id=session_id, limit=10)
    # 排序：L4 优先，按「重要性 + 情感强度」综合分（情绪浓的记忆更易浮现）
    w_emo = float(MEM_CFG.get("recall_emotion_weight", 0.5))

    def _recall_score(m: dict) -> float:
        return m["importance"] + w_emo * abs(m.get("emotion_score", 0.0) or 0.0)

    l4.sort(key=_recall_score, reverse=True)
    l3.sort(key=_recall_score, reverse=True)
    merged = l4[:top_k] + l3[:top_k] + l2[:top_k]
    return merged


def format_memories_for_prompt(mems: list[dict]) -> str:
    if not mems:
        return "(暂无长期记忆)"
    lines = []
    for m in mems:
        lines.append(f"- [{m['layer']} #{m['id']} imp={m['importance']:.2f}] "
                     f"{m['summary']}")
    return "\n".join(lines)
