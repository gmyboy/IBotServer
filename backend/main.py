"""FastAPI 入口：聊天 / 记忆查询 / 主动 tick / 语音 STT-TTS / 静态前端。"""
from __future__ import annotations
import asyncio
import base64
import json
import logging
import os
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ValidationError

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("pophie")

from .config import (
    SERVER_CFG, SPEECH_CFG, CHAT_CFG,
    ADMIN_CONFIG_SCHEMA, get_config_for_admin, save_config, config_secret_issues,
)
from .database import (
    init_db, get_conn, row_to_dict, touch_robot,
    list_robots_with_stats, get_robot_detail,
    update_robot_display_name, delete_robot_all,
    upsert_owner_profile, get_owner_profile, delete_owner_profile,
)
from .llm import chat_json, parse_chat_json, iter_chat_stream, LLMError
from .streaming_text import StreamingReplyTextExtractor
from .memory import (
    L1_BUFFER, ingest_user_input, list_memories, recall_for_response,
    format_memories_for_prompt, delete_memory,
)
from .proactive import perceive_and_respond
from .reminder import (
    schedule_reminders, list_reminders, cancel_reminder,
    process_user_reminder_intents,
    start_scheduler,
)
from .welcome import send_welcome_message
from .schemas import (
    AudioPayload, ChatRequest, ChatResponse, FacialExpression,
    FACIAL_EXPRESSION_LABELS, FACIAL_EXPRESSION_ALIASES, GESTURE_LABELS,
    RobotState, ROBOT_STATE_LABELS,
    PerceptionInput, RobotOutput, SttRequest,
    SttResult, TtsRequest, TtsResponse, VoiceProsody,
    OwnerProfile, OwnerProfilePutRequest, OwnerProfilePutResponse,
    REPLY_JSON_INSTRUCTION, VOICE_KEYS, align_output_to_user_perception,
    format_perception_dict, is_vision_only_perception, parse_facial_expression,
    perception_to_dict, robot_output_from_llm,
    voice_for_expression,
)
from . import speech


app = FastAPI(title="Pophie")


@app.on_event("startup")
async def _on_startup():
    start_scheduler()
    for issue in config_secret_issues():
        log.warning("[startup] %s", issue)
    if SPEECH_CFG.get("enabled"):
        log.info("[startup] speech enabled STT=%s TTS=%s (Python %s)",
                 speech._stt_engine(), speech.tts_engine(),
                 sys.version.split()[0])
    else:
        log.info("[startup] 语音模块未启用 (speech.enabled=false)")

app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"],
                   allow_headers=["*"])


SKIP_LOG_PREFIXES = ("/static",)
MAX_BODY_LOG = 4000


def _truncate(s: str, limit: int = MAX_BODY_LOG) -> str:
    if len(s) <= limit:
        return s
    return s[:limit] + f"... <截断, 共 {len(s)} 字符>"


@app.middleware("http")
async def log_requests(request: Request, call_next):
    path = request.url.path
    if path == "/" or path.startswith(SKIP_LOG_PREFIXES):
        return await call_next(request)

    start = time.time()
    client = f"{request.client.host}:{request.client.port}" if request.client else "-"
    qs = dict(request.query_params)

    orig_receive = request.receive
    body_chunks: list[bytes] = []
    more_body = True
    while more_body:
        message = await orig_receive()
        body_chunks.append(message.get("body", b""))
        more_body = message.get("more_body", False)
    body_bytes = b"".join(body_chunks)

    body_text = ""
    if body_bytes:
        try:
            body_text = body_bytes.decode("utf-8")
            try:
                body_text = json.dumps(json.loads(body_text),
                                       ensure_ascii=False, indent=2)
            except Exception:
                pass
        except UnicodeDecodeError:
            body_text = f"<binary {len(body_bytes)} bytes>"

    body_replayed = False

    async def _receive():
        nonlocal body_replayed
        if not body_replayed:
            body_replayed = True
            return {"type": "http.request", "body": body_bytes, "more_body": False}
        return await orig_receive()

    request._receive = _receive  # type: ignore[attr-defined]

    log.info("--> %s %s client=%s qs=%s headers={ct=%s, len=%s}\n  body=%s",
             request.method, path, client, qs,
             request.headers.get("content-type"),
             request.headers.get("content-length"),
             _truncate(body_text) if body_text else "<empty>")

    try:
        response = await call_next(request)
    except Exception as e:
        log.exception("XX %s %s 异常: %s", request.method, path, e)
        raise

    dur = (time.time() - start) * 1000
    log.info("<-- %s %s status=%d in %.0fms",
             request.method, path, response.status_code, dur)
    return response

ROOT = Path(__file__).resolve().parent.parent
FRONTEND = ROOT / "frontend"

init_db()

_bg_pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="pophie-bg")


# -------- 工具 --------

def _should_inline_tts(chat_input) -> bool:
    if chat_input.skip_tts is True:
        return False
    if chat_input.skip_tts is False:
        return True
    return bool(CHAT_CFG.get("inline_tts", True))


def _format_reminder_side_effects(rem_result: dict) -> tuple[list, list]:
    scheduled = [
        {
            "id": rid,
            "remind_at": it["remind_at"],
            "content": it["content"],
            **({"interval_minutes": it["interval_minutes"],
                "repeat_until": it["repeat_until"]}
               if it.get("interval_minutes") else {}),
        }
        for rid, it in zip(rem_result.get("scheduled_ids", []),
                           rem_result.get("scheduled", []))
    ]
    cancelled = [{"id": rid} for rid in rem_result.get("cancelled_ids", [])]
    return scheduled, cancelled


def _defer_chat_side_tasks(robot_id: str, session_id: str, user_text: str,
                           text: str, recent: list[dict],
                           user_id: str = "default") -> None:
    def _work():
        try:
            ingest_user_input(robot_id, session_id, user_text,
                              modality="text", recent_context=recent[:-1],
                              user_id=user_id)
            rem_result = process_user_reminder_intents(robot_id, session_id, text)
            log.info("[chat.bg] 后台完成 reminders scheduled=%d cancelled=%d",
                     len(rem_result.get("scheduled_ids", [])),
                     len(rem_result.get("cancelled_ids", [])))
        except Exception:
            log.exception("[chat.bg] 后台任务失败")

    _bg_pool.submit(_work)


def _resolve_robot(robot_id: Optional[str] = None) -> str:
    if robot_id:
        return robot_id
    return SERVER_CFG.get("default_robot", "default")


def _resolve_user(user_id: Optional[str] = None) -> str:
    """用户标识：端侧身份识别结果；仅作溯源/回显，记忆仍按 robot_id 隔离。"""
    uid = (user_id or "").strip() if user_id else ""
    return uid or "default"


def _ensure_session(session_id: Optional[str]) -> str:
    return session_id or f"sess-{uuid.uuid4().hex[:8]}"


def _assistant_json_content(text: str, metadata: Optional[dict] = None) -> str:
    """把历史 assistant 消息格式化为 JSON，强化模型输出 JSON 的习惯。"""
    t = (text or "").strip()
    if not t:
        return t
    if t.startswith("{"):
        return t
    out = (metadata or {}).get("output") if metadata else None
    if isinstance(out, dict) and out.get("text"):
        return json.dumps({
            "text": out.get("text", t),
            "facial_expression": out.get("facial_expression", "neutral"),
            "voice": out.get("voice") or {
                "tone": "温柔", "intonation": "平稳", "speed": "正常",
            },
            "gesture": None,
            "posture": None,
        }, ensure_ascii=False)
    return json.dumps({
        "text": t,
        "facial_expression": "neutral",
        "voice": {"tone": "温柔", "intonation": "平稳", "speed": "正常"},
        "gesture": None,
        "posture": None,
    }, ensure_ascii=False)


def _recent_conversations(robot_id: str, session_id: str, n: int = 10) -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """SELECT role, content, modality, metadata, created_at FROM conversations
               WHERE robot_id=? AND session_id=? ORDER BY id DESC LIMIT ?""",
            (robot_id, session_id, n),
        ).fetchall()
    result = []
    for r in reversed(rows):
        row = dict(r)
        meta = None
        if row.get("metadata"):
            try:
                meta = json.loads(row["metadata"])
            except Exception:
                meta = None
        if row.get("role") != "user":
            row["content"] = _assistant_json_content(row.get("content", ""), meta)
        result.append(row)
    return result


def _save_conv(robot_id: str, session_id: str, role: str, content: str,
               modality: str = "text", metadata: Optional[dict] = None,
               user_id: str = "default"):
    with get_conn() as conn:
        conn.execute(
            """INSERT INTO conversations(robot_id, user_id, session_id, role, content,
                modality, metadata) VALUES(?,?,?,?,?,?,?)""",
            (robot_id, user_id, session_id, role, content, modality,
             json.dumps(metadata or {}, ensure_ascii=False)),
        )


SYSTEM_PROMPT = """你是 Pophie——一个温暖的桌面陪伴机器人。
你拥有四层记忆：L1 瞬时感知 / L2 当前会话 / L3 用户偏好 / L4 长期固化。
你**不主动开启对话**：等用户说话再回应（被动 tick 的主动场景除外）。

用户输入有两种形态：
1) 文字 + 可选感知：`[感知 语气:X 语调:X 语速:X 抚摸:X 表情:X] 文本`
   - 语气/语调/语速来自语音侧道，必然与文字"同时"到达，要联合解读
     （如文字"还好"+语气低落 ≠ 真的还好）。
2) 纯非语言信号（没有文字）：`[非语言信号 抚摸:X 表情:X]`
   - 用户没说话，只是做了动作/表情/抚摸了你，要像真人一样**主动而克制**地回应。
3) 纯视觉感知（没有文字）：`[视觉感知 画面:…]`
   - 端侧摄像头对环境的被动观察，**不是**用户在跟你说话。
   - **默认保持沉默**（`text` 留空）；只有把握充分、且有明确自然互动契机时才开口。
   - 拿不准、信息不足、或只是寻常日常画面 → 必须沉默，`robot_state` 用 gazing 注视即可。

通道语义：
- 抚摸是真实物理交互（摸头/拥抱/戳一下…），是亲密度信号；
- 面部表情仅限 7 类：恼怒、厌恶、恐惧、开心、中性、悲伤、惊讶；
- 身份是端侧摄像头识别到的「当前是谁」（如『身份:小明』）：你可以自然地用对方名字称呼、延续你们之间的关系，但**不要机械复读**「我知道你是小明」之类；身份不确定时按普通用户对待；
- 手势是端侧识别到的真实肢体动作（如『手势:挥手/点赞/比心/摇头』），是表达意图的信号，要联合表情与文字解读（如挥手=打招呼或告别，点赞=认可，比心=亲昵，摇头=否定）；
- 画面是端侧摄像头对环境的视觉理解（可见物品、人物状态/动作等）；可与文字或其它感知联合解读，但**单独送来时默认不搭话**（见上文第 3 种输入形态）；
- 体姿态字段已预留但当前未启用。
当感知与文字不一致时，优先相信非语言信号背后的情绪；回应时不要复读这些标签，要自然转化为关心或共情。

机器人输出表情（facial_expression 字段）是你自己的脸，不是复述用户的表情标签。
用户悲伤、恐惧或恼怒时，你在安慰共情须用 sad（心疼），语气温柔、语调下沉、语速偏慢；
不要用 neutral 面无表情地安抚。neutral 只用于平淡日常闲聊。

对话口吻（最重要）：
- 你是真实陪伴机器人，JSON 里的 text 就是你对用户说的原话，像真人一样直接开口；
- 禁止把思考过程、推理分析、记忆检索结论写进 text；用户只能看到你说的话，看不到你的内心戏。
- 多轮对话里你之前的 assistant 消息也是 JSON 格式，本次回复继续只输出 JSON，不要退回纯文本。

语音输出：
- 你**具备说话能力**：text 回复会由语音模块自动合成为语音播放；voice 字段控制语气、语调与语速。
- **严禁**声称「只能文字聊天」「没法发出声音」「没有语音能力」等——这与你的实际能力矛盾。
- 用户说听不见/没声音/听不到你说话时：像真人一样关心并协助排查（音量、静音、扬声器、是否插着耳机），
  表示你会再清楚说一遍或请对方检查一下；**不要否认你能说话**。

回应原则：
- 不要复读记忆，而是融入语气与内容；
- 短句、有温度、不审讯式追问；
- 若长期记忆里有重要事件/边界/家庭成员，请优先尊重。

定时提醒：
- 用户约定到点提醒或周期性提醒（如每隔 N 分钟喝水）时，后台会自动建提醒并在到点主动开口；
- 用户要求停止/取消提醒时，后台会自动取消尚未触发的待提醒；
- 你可以自然口吻确认已记下或已停止，实际调度由系统完成，不要编造未约定的提醒细节。"""


def _owner_prompt_block(robot_id: str) -> str:
    """主人档案上下文，注入 system prompt。"""
    owner = get_owner_profile(robot_id)
    if not owner:
        return ""
    lines = [
        f"\n[主人档案]",
        f"- 主人称呼：{owner['nickname']}",
        f"- 你的名字（主人起的）：{owner['robot_name']}",
    ]
    if owner.get("gender"):
        gender_labels = {"male": "男", "female": "女", "other": "其他"}
        lines.append(f"- 主人性别：{gender_labels.get(owner['gender'], owner['gender'])}")
    if owner.get("birthday"):
        lines.append(f"- 主人生日：{owner['birthday']}")
    if owner.get("face_registered"):
        lines.append("- 端侧已录入主人人脸（仅标志，无图像上传）")
    lines.append(
        "- 请自然地用主人称呼与其对话；自我介绍时使用主人给你起的名字。"
    )
    return "\n".join(lines)


def _system_prompt_for(robot_id: str) -> str:
    return SYSTEM_PROMPT + _owner_prompt_block(robot_id) + REPLY_JSON_INSTRUCTION


def _merge_voice(perception: Optional[PerceptionInput],
                 stt_voice: Optional[VoiceProsody]) -> Optional[VoiceProsody]:
    if not perception or not perception.voice:
        return stt_voice
    v = perception.voice
    if not stt_voice:
        return v
    return VoiceProsody(
        tone=v.tone or stt_voice.tone,
        intonation=v.intonation or stt_voice.intonation,
        speed=v.speed or stt_voice.speed,
    )


def _prepare_input(chat_input: ChatInput) -> tuple[str, dict, Optional[SttResult], bool]:
    """解析 STT、合并感知，返回 (text, p_data_dict, stt_result, stt_unrecognized)。"""
    text = (chat_input.text or "").strip()
    stt_result: Optional[SttResult] = None
    stt_unrecognized = False
    perception = chat_input.perception or PerceptionInput()

    if chat_input.audio and chat_input.audio.data and not text:
        if speech.is_enabled():
            try:
                stt_result = speech.transcribe(chat_input.audio)
                text = stt_result.text.strip()
                if not text:
                    stt_unrecognized = True
            except Exception as e:
                log.error("[chat] STT 失败: %s", e)
                stt_unrecognized = True
        else:
            raise HTTPException(400, "语音功能未启用，请提供 text 或启用 speech.enabled")

    merged_voice = _merge_voice(perception, stt_result.voice if stt_result else None)
    if merged_voice and (merged_voice.tone or merged_voice.intonation or merged_voice.speed):
        perception = PerceptionInput(
            facial_expression=perception.facial_expression,
            voice=merged_voice,
            touch=perception.touch,
            identity=perception.identity,
            gesture=perception.gesture,
            posture=perception.posture,
            vision=perception.vision,
        )

    p_data = perception_to_dict(perception)

    if not text:
        voice_present = {k: p_data[k] for k in list(p_data) if k in VOICE_KEYS}
        if voice_present:
            log.info("[chat] 无文字时忽略声音侧道: %s", voice_present)
            for k in VOICE_KEYS:
                p_data.pop(k, None)

    if stt_unrecognized:
        return "", {}, stt_result, True

    if not text and not p_data:
        raise HTTPException(400, "需要文字、语音或至少一项感知输入（表情/抚摸/画面等）")

    return text, p_data, stt_result, False


def _build_user_text(text: str, p_data: dict) -> str:
    perception_str = format_perception_dict(p_data)
    if text and perception_str:
        return f"[感知 {perception_str}] {text}"
    if text:
        return text
    if is_vision_only_perception(p_data):
        return f"[视觉感知 {perception_str}]"
    return f"[非语言信号 {perception_str}]"


def _chat_recent_turns() -> int:
    return int(CHAT_CFG.get("recent_turns", 8))


def _chat_recall_top_k() -> int:
    return int(CHAT_CFG.get("recall_top_k", 5))


def _build_chat_history(robot_id: str, session_id: str, user_text: str):
    """并行加载近期对话与记忆，构建 LLM history。"""
    recent_n = _chat_recent_turns()
    recall_k = _chat_recall_top_k()
    with ThreadPoolExecutor(max_workers=2) as pool:
        f_recent = pool.submit(_recent_conversations, robot_id, session_id, recent_n)
        f_mems = pool.submit(recall_for_response, robot_id, session_id, user_text, top_k=recall_k)
        recent = f_recent.result()
        mems = f_mems.result()
    mem_block = format_memories_for_prompt(mems)
    history = [{"role": "system", "content": _system_prompt_for(robot_id)
                + "\n\n[长期记忆上下文]\n" + mem_block}]
    for m in recent:
        role = "user" if m["role"] == "user" else "assistant"
        history.append({"role": role, "content": m["content"]})
    return history, recent, mems


def _generate_reply(history: list, p_data: dict) -> RobotOutput:
    log.info("[chat] >>> 调用 LLM 生成结构化回复")
    user_expr = parse_facial_expression(p_data.get("facial_expression"))
    try:
        raw = chat_json(history)
        output = robot_output_from_llm(raw)
        return align_output_to_user_perception(output, user_expr)
    except LLMError as e:
        log.error("[chat] LLM 失败，保持静默：%s", e)
        return _silent_output()


def _silent_output() -> RobotOutput:
    """无法识别或出错时不说话：空文本、不合成 TTS。"""
    return RobotOutput(
        text="",
        facial_expression=FacialExpression.neutral,
    ).finalize()


def _synthesize_output(output: RobotOutput, voice_id: Optional[str] = None) -> RobotOutput:
    if not speech.is_enabled() or not output.text.strip():
        output.audio = None
        return output.finalize()
    output.voice = voice_for_expression(output.facial_expression, output.voice)
    try:
        output.audio = speech.synthesize_payload(
            output.text, output.voice, voice_id=voice_id)
        log.info("[chat] TTS 成功 len=%d audio_b64=%d",
                 len(output.text), len(output.audio.data) if output.audio else 0)
    except Exception as e:
        log.error(
            "[chat] TTS 失败 text=%r voice_id=%r resolved=%r: %s",
            output.text[:80], voice_id, speech.resolve_voice_id(voice_id), e,
        )
    return output.finalize()


# -------- 路由 --------

@app.get("/api/health")
def health():
    return {
        "ok": True,
        "speech_enabled": speech.is_enabled(),
        "stt_engine": speech._stt_engine() if speech.is_enabled() else None,
        "stt_stream": speech.is_enabled(),
        "tts_engine": speech.tts_engine() if speech.is_enabled() else None,
    }


@app.get("/api/schema")
def api_schema():
    return {
        "facial_expressions": [
            {"key": e.value, "label": FACIAL_EXPRESSION_LABELS[e]}
            for e in FacialExpression
        ],
        # 端侧（XBot）可能发送的别名 → 标准 key 的映射（如 surprised→surprise）
        "facial_expression_aliases": {
            alias: expr.value for alias, expr in FACIAL_EXPRESSION_ALIASES.items()
        },
        "voice_prosody": {
            "tone": ["温柔", "平静", "急躁", "兴奋", "低落", "撒娇", "疑问", "冷淡"],
            "intonation": ["平稳", "上扬", "下沉", "起伏大"],
            "speed": ["慢", "正常", "快", "极快"],
        },
        # 端侧识别的手势 key → 中文（现已喂给 LLM）
        "gestures": [{"key": k, "label": v} for k, v in GESTURE_LABELS.items()],
        # 机器人回复可携带的互动动作状态（FSM 9 态），output.robot_state 取值范围
        "robot_states": [
            {"key": s.value, "label": ROBOT_STATE_LABELS[s]} for s in RobotState
        ],
        # 感知通道字段说明
        "perception_fields": {
            "facial_expression": "用户面部表情（7 类或别名）",
            "voice": "语音侧道（语气/语调/语速），仅与文字/STT 同时有效",
            "touch": "抚摸类物理交互，如 摸头/拥抱",
            "identity": "端侧身份识别到的人名（仅作感知上下文，记忆按 robot_id 隔离）",
            "gesture": "端侧手势识别结果（type 取 gestures 列表 key）",
            "posture": "体姿态（预留，暂不进入 LLM）",
            "vision": "摄像头视觉感知：objects_detail（检测目标列表）+ scene（场景描述）；也可在 perception 根级直接传 scene / objects_detail",
        },
        # 后端输出表情 → 端侧虚拟宠物 FSM 状态的建议映射
        "fsm_state_mapping": {
            "happy": "happy",
            "neutral": "idle",
            "sad": "sleepy",
            "angry": "confused",
            "disgust": "confused",
            "fear": "confused",
            "surprise": "confused",
        },
        "tts_voices": speech.list_tts_voices(),
        "reserved_fields": ["posture"],
    }


@app.post("/api/session/new")
def new_session(robot_id: Optional[str] = None, user_id: Optional[str] = None):
    rid = _resolve_robot(robot_id)
    with get_conn() as conn:
        touch_robot(conn, rid)
    return {
        "robot_id": rid,
        "user_id": _resolve_user(user_id),
        "session_id": _ensure_session(None),
    }


@app.put("/api/robots/{robot_id}/owner")
def put_owner(robot_id: str, req: OwnerProfilePutRequest):
    if not req.owner:
        raise HTTPException(400, "owner is required")
    nickname = (req.owner.nickname or "").strip()
    robot_name = (req.owner.robot_name or "").strip()
    if not nickname or not robot_name:
        raise HTTPException(400, "nickname and robot_name are required")
    try:
        profile = OwnerProfile(
            nickname=nickname,
            robot_name=robot_name,
            gender=req.owner.gender,
            birthday=req.owner.birthday,
            face_registered=req.owner.face_registered,
        )
    except ValidationError as e:
        raise HTTPException(400, str(e.errors()[0].get("msg", e))) from e
    is_new = get_owner_profile(robot_id) is None
    saved = upsert_owner_profile(robot_id, profile.model_dump())
    owner = OwnerProfile(**saved)
    welcome_message = None
    session_id = None
    if is_new:
        session_id = _ensure_session(req.session_id)
        welcome_message = send_welcome_message(
            robot_id, session_id, saved,
            user_id=nickname or "default",
        )
    return OwnerProfilePutResponse(
        robot_id=robot_id,
        owner=owner,
        is_new=is_new,
        welcome_message=welcome_message,
        session_id=session_id,
    ).model_dump()


@app.get("/api/robots/{robot_id}/owner")
def get_owner(robot_id: str):
    owner = get_owner_profile(robot_id)
    if not owner:
        raise HTTPException(404, "owner not found")
    return OwnerProfile(**owner).model_dump()


@app.delete("/api/robots/{robot_id}/owner")
def delete_owner(robot_id: str):
    if not delete_owner_profile(robot_id):
        raise HTTPException(404, "owner not found")
    return {"ok": True, "robot_id": robot_id}


@app.post("/api/stt")
def stt_api(req: SttRequest):
    if not speech.is_enabled():
        raise HTTPException(503, "语音功能未启用")
    try:
        return speech.transcribe(req.audio)
    except Exception as e:
        raise HTTPException(400, str(e)) from e


@app.websocket("/api/stt/stream")
async def stt_stream_ws(websocket: WebSocket):
    """流式 STT：客户端 WebSocket 推送 PCM 分片，服务端回推 partial/final。"""
    if not speech.is_enabled():
        await websocket.close(code=1013, reason="speech disabled")
        return

    await websocket.accept()
    session: Optional[speech.SttStreamSession] = None
    pump_task: Optional[asyncio.Task] = None
    idle_task: Optional[asyncio.Task] = None
    stopped = False
    shutdown = asyncio.Event()

    async def _pump_events() -> None:
        nonlocal stopped
        assert session is not None
        while not stopped and not shutdown.is_set():
            evt = await asyncio.to_thread(session.get_event, 0.25)
            if evt is None:
                continue
            await websocket.send_text(json.dumps(evt, ensure_ascii=False))
            if evt.get("type") == "error":
                stopped = True
                shutdown.set()
                break

    async def _idle_watchdog() -> None:
        nonlocal stopped
        while not shutdown.is_set():
            await asyncio.sleep(1.0)
            if session is None or not session.is_connected:
                continue
            if session.conversation_idle_expired():
                log.info(
                    "[stt/stream] conversation idle timeout (%.0fs)",
                    session.conversation_idle_sec(),
                )
                try:
                    payload = {
                        "type": "session_end",
                        "reason": "conversation_idle",
                        "message": "长时间无有效对话，会话已结束",
                        "conversation_idle_sec": session.conversation_idle_sec(),
                    }
                    await websocket.send_text(
                        json.dumps(payload, ensure_ascii=False),
                    )
                except Exception:
                    pass
                stopped = True
                shutdown.set()
                break

    try:
        while not shutdown.is_set():
            try:
                raw = await asyncio.wait_for(
                    websocket.receive_text(), timeout=1.0,
                )
            except asyncio.TimeoutError:
                continue

            msg = json.loads(raw)
            typ = msg.get("type")

            if typ == "start":
                if session is not None:
                    await asyncio.to_thread(session.close)
                if pump_task is not None:
                    stopped = True
                    pump_task.cancel()
                    try:
                        await pump_task
                    except asyncio.CancelledError:
                        pass
                    stopped = False
                if idle_task is not None:
                    idle_task.cancel()
                    try:
                        await idle_task
                    except asyncio.CancelledError:
                        pass

                session = speech.SttStreamSession(
                    turn_detection=msg.get("turn_detection"),
                    sample_rate=msg.get("sample_rate"),
                    language=msg.get("language"),
                )
                await asyncio.to_thread(session.start)
                shutdown.clear()
                pump_task = asyncio.create_task(_pump_events())
                idle_task = asyncio.create_task(_idle_watchdog())

            elif typ == "chunk":
                if session is None:
                    err = {"type": "error", "message": "STT 会话未启动，请先发送 start"}
                    await websocket.send_text(json.dumps(err, ensure_ascii=False))
                    continue
                data_b64 = msg.get("data") or ""
                if not data_b64:
                    continue
                pcm = base64.b64decode(data_b64)
                await asyncio.to_thread(session.append_audio, pcm)

            elif typ == "commit":
                if session is not None:
                    await asyncio.to_thread(session.commit)

            elif typ == "end":
                break

            else:
                err = {"type": "error", "message": f"未知消息类型: {typ}"}
                await websocket.send_text(json.dumps(err, ensure_ascii=False))

    except WebSocketDisconnect:
        log.info("[stt/stream] client disconnected")
    except json.JSONDecodeError:
        err = {"type": "error", "message": "无效 JSON"}
        try:
            await websocket.send_text(json.dumps(err, ensure_ascii=False))
        except Exception:
            pass
    except Exception as e:
        log.error("[stt/stream] 失败: %s", e)
        try:
            err = {"type": "error", "message": str(e)}
            await websocket.send_text(json.dumps(err, ensure_ascii=False))
        except Exception:
            pass
    finally:
        stopped = True
        shutdown.set()
        if idle_task is not None:
            idle_task.cancel()
            try:
                await idle_task
            except asyncio.CancelledError:
                pass
        if pump_task is not None:
            pump_task.cancel()
            try:
                await pump_task
            except asyncio.CancelledError:
                pass
        if session is not None:
            await asyncio.to_thread(session.close)
        try:
            await websocket.close(code=1000)
        except Exception:
            pass


@app.post("/api/tts")
def tts_api(req: TtsRequest):
    if not speech.is_enabled():
        raise HTTPException(503, "语音功能未启用")
    try:
        audio = speech.synthesize_payload(req.text, req.voice, voice_id=req.voice_id)
        return TtsResponse(text=req.text, voice=req.voice, audio=audio)
    except Exception as e:
        log.error("[tts] 合成失败 text=%r voice_id=%r: %s", req.text[:80], req.voice_id, e)
        raise HTTPException(400, str(e)) from e


@app.post("/api/tts/stream")
def tts_stream_api(req: TtsRequest):
    """WebSocket 流式 TTS：NDJSON 行流，客户端可边收边播以降低首声延迟。"""
    if not speech.is_enabled():
        raise HTTPException(503, "语音功能未启用")

    def _events():
        metrics: dict = {}
        meta = {
            "type": "meta",
            "format": speech.tts_stream_format(),
            "sample_rate": speech.tts_sample_rate(),
            "encoding": "base64",
        }
        yield json.dumps(meta, ensure_ascii=False) + "\n"
        try:
            for chunk in speech.iter_tts_chunks(
                req.text, req.voice, voice_id=req.voice_id, metrics=metrics,
            ):
                line = {
                    "type": "chunk",
                    "data": base64.b64encode(chunk).decode("ascii"),
                }
                yield json.dumps(line, ensure_ascii=False) + "\n"
        except Exception as e:
            log.error("[tts/stream] 失败 text=%r voice_id=%r: %s",
                      req.text[:80], req.voice_id, e)
            err = {"type": "error", "message": str(e)}
            yield json.dumps(err, ensure_ascii=False) + "\n"
            return
        done = {
            "type": "done",
            "first_packet_ms": metrics.get("first_packet_ms"),
        }
        yield json.dumps(done, ensure_ascii=False) + "\n"

    return StreamingResponse(_events(), media_type="application/x-ndjson")


@app.post("/api/chat")
def chat_api(req: ChatRequest):
    t_start = time.time()
    robot_id = _resolve_robot(req.robot_id)
    user_id = _resolve_user(req.user_id)
    session_id = _ensure_session(req.session_id)
    with get_conn() as conn:
        touch_robot(conn, robot_id)

    chat_input = req.input
    voice_id = chat_input.voice_id

    text, p_data, stt_result, stt_unrecognized = _prepare_input(chat_input)
    log.info("[chat] prepare %.0fms", (time.time() - t_start) * 1000)

    if stt_unrecognized:
        log.info("[chat] robot=%s session=%s 语音无法识别，保持静默", robot_id, session_id)
        _save_conv(robot_id, session_id, "user", "[语音] 未能识别", "text",
                   metadata={"stt_unrecognized": True,
                             "stt": stt_result.model_dump() if stt_result else None},
                   user_id=user_id)
        output = _silent_output()
        return ChatResponse(
            robot_id=robot_id,
            user_id=user_id,
            session_id=session_id,
            output=output,
            stt=stt_result,
        ).model_dump()

    user_text = _build_user_text(text, p_data)

    log.info("[chat] robot=%s user=%s session=%s text=%r perception=%s",
             robot_id, user_id, session_id, text, p_data)

    _save_conv(robot_id, session_id, "user", user_text, "text",
               metadata={"perception": p_data or None,
                         "text_only": text, "no_text": not text,
                         "stt": stt_result.model_dump() if stt_result else None},
               user_id=user_id)

    history, recent, mems = _build_chat_history(robot_id, session_id, user_text)

    def _do_reply():
        return _generate_reply(history, p_data)

    defer_side = bool(CHAT_CFG.get("defer_side_tasks", True))
    t_llm = time.time()
    ingest_result = {"produced": [], "l1_frames": []}
    rem_result: dict = {"scheduled": [], "scheduled_ids": [], "cancelled_ids": []}

    if defer_side:
        output = _do_reply()
        _defer_chat_side_tasks(robot_id, session_id, user_text, text, recent,
                               user_id=user_id)
    else:
        def _do_ingest():
            return ingest_user_input(robot_id, session_id, user_text,
                                     modality="text",
                                     recent_context=recent[:-1],
                                     user_id=user_id)

        def _do_reminders():
            if not text:
                return {"scheduled": [], "scheduled_ids": [], "cancelled_ids": []}
            return process_user_reminder_intents(robot_id, session_id, text)

        with ThreadPoolExecutor(max_workers=3) as pool:
            f_reply = pool.submit(_do_reply)
            f_ingest = pool.submit(_do_ingest)
            f_rem = pool.submit(_do_reminders)
            output = f_reply.result()
            ingest_result = f_ingest.result()
            rem_result = f_rem.result()

    log.info("[chat] LLM %.0fms defer_side=%s", (time.time() - t_llm) * 1000, defer_side)

    if _should_inline_tts(chat_input):
        t_tts = time.time()
        output = _synthesize_output(output, voice_id=voice_id)
        log.info("[chat] TTS %.0fms", (time.time() - t_tts) * 1000)
    else:
        log.warning("[chat] inline_tts=false，响应不含音频（客户端需另调 /api/tts）")
        output.audio = None
        output = output.finalize()

    log.info("[chat] output text=%r expr=%s total=%.0fms",
             output.text, output.facial_expression, (time.time() - t_start) * 1000)

    if output.text.strip():
        _save_conv(robot_id, session_id, "assistant", output.text, "text",
                   metadata={"recall_ids": [m["id"] for m in mems],
                             "output": output.model_dump()},
                   user_id=user_id)

    scheduled, cancelled = _format_reminder_side_effects(rem_result)

    resp = ChatResponse(
        robot_id=robot_id,
        user_id=user_id,
        session_id=session_id,
        output=output,
        stt=stt_result,
        memory_flow=ingest_result["produced"],
        l1_frames=ingest_result["l1_frames"],
        recalled=[{"id": m["id"], "layer": m["layer"],
                   "summary": m["summary"], "importance": m["importance"]}
                  for m in mems],
        scheduled_reminders=scheduled,
        cancelled_reminders=cancelled,
    )
    return resp.model_dump()


@app.post("/api/chat/stream")
def chat_stream_api(req: ChatRequest):
    """流式对话：LLM 生成过程中按句推送 speak 事件，客户端可提前开 TTS。"""
    t_start = time.time()
    robot_id = _resolve_robot(req.robot_id)
    user_id = _resolve_user(req.user_id)
    session_id = _ensure_session(req.session_id)
    with get_conn() as conn:
        touch_robot(conn, robot_id)

    chat_input = req.input
    voice_id = chat_input.voice_id

    text, p_data, stt_result, stt_unrecognized = _prepare_input(chat_input)
    log.info("[chat/stream] prepare %.0fms", (time.time() - t_start) * 1000)

    if stt_unrecognized:
        def _silent_events():
            yield json.dumps({
                "type": "done",
                "response": ChatResponse(
                    robot_id=robot_id,
                    user_id=user_id,
                    session_id=session_id,
                    output=_silent_output(),
                    stt=stt_result,
                ).model_dump(),
            }, ensure_ascii=False) + "\n"
        return StreamingResponse(_silent_events(), media_type="application/x-ndjson")

    user_text = _build_user_text(text, p_data)
    _save_conv(robot_id, session_id, "user", user_text, "text",
               metadata={"perception": p_data or None,
                         "text_only": text, "no_text": not text,
                         "stt": stt_result.model_dump() if stt_result else None},
               user_id=user_id)

    history, recent, mems = _build_chat_history(robot_id, session_id, user_text)
    defer_side = bool(CHAT_CFG.get("defer_side_tasks", True))

    def _events():
        extractor = StreamingReplyTextExtractor()
        ingest_result = {"produced": [], "l1_frames": []}
        rem_items: list = []
        t_llm = time.time()
        try:
            full_content = ""
            for delta in iter_chat_stream(history):
                full_content += delta
                for chunk in extractor.feed(delta):
                    yield json.dumps({"type": "speak", "text": chunk}, ensure_ascii=False) + "\n"
            tail = extractor.flush()
            if tail:
                yield json.dumps({"type": "speak", "text": tail}, ensure_ascii=False) + "\n"

            user_expr = parse_facial_expression(p_data.get("facial_expression"))
            raw = parse_chat_json(full_content)
            output = align_output_to_user_perception(
                robot_output_from_llm(raw), user_expr,
            )
            output.audio = None
            output = output.finalize()

            if defer_side:
                _defer_chat_side_tasks(robot_id, session_id, user_text, text, recent,
                                       user_id=user_id)
            log.info("[chat/stream] LLM %.0fms defer_side=%s",
                     (time.time() - t_llm) * 1000, defer_side)

            if output.text.strip():
                _save_conv(robot_id, session_id, "assistant", output.text, "text",
                           metadata={"recall_ids": [m["id"] for m in mems],
                                     "output": output.model_dump()},
                           user_id=user_id)

            resp = ChatResponse(
                robot_id=robot_id,
                user_id=user_id,
                session_id=session_id,
                output=output,
                stt=stt_result,
                memory_flow=ingest_result["produced"],
                l1_frames=ingest_result["l1_frames"],
                recalled=[{"id": m["id"], "layer": m["layer"],
                           "summary": m["summary"], "importance": m["importance"]}
                          for m in mems],
                scheduled_reminders=[],
            )
            log.info("[chat/stream] output text=%r total=%.0fms",
                     output.text, (time.time() - t_start) * 1000)
            yield json.dumps({"type": "done", "response": resp.model_dump()},
                             ensure_ascii=False) + "\n"
        except LLMError as e:
            log.error("[chat/stream] LLM 失败：%s", e)
            yield json.dumps({
                "type": "done",
                "response": ChatResponse(
                    robot_id=robot_id,
                    user_id=user_id,
                    session_id=session_id,
                    output=_silent_output(),
                    stt=stt_result,
                ).model_dump(),
            }, ensure_ascii=False) + "\n"
        except Exception as e:
            log.error("[chat/stream] 失败：%s", e)
            yield json.dumps({"type": "error", "message": str(e)}, ensure_ascii=False) + "\n"

    return StreamingResponse(_events(), media_type="application/x-ndjson")


class TickReqModel(BaseModel):
    robot_id: Optional[str] = None
    user_id: Optional[str] = None
    session_id: Optional[str] = None
    signal: dict


@app.post("/api/tick")
def tick(req: TickReqModel):
    robot_id = _resolve_robot(req.robot_id)
    user_id = _resolve_user(req.user_id)
    session_id = _ensure_session(req.session_id)
    log.info("[tick] robot=%s user=%s signal=%s", robot_id, user_id, req.signal)
    result = perceive_and_respond(robot_id, session_id, req.signal, user_id=user_id)
    output = None
    if result.get("decision") == "speak" and result.get("content"):
        output = RobotOutput(
            text=result["content"],
            facial_expression=FacialExpression.neutral,
            voice=VoiceProsody(tone="温柔", intonation="平稳", speed="正常"),
        )
        output = _synthesize_output(output)
        result["output"] = output.model_dump()
    log.info("[tick] decision=%s", result["decision"])
    return result


@app.get("/api/memories")
def memories(robot_id: Optional[str] = None,
             layer: Optional[str] = None, session_id: Optional[str] = None):
    return {"items": list_memories(_resolve_robot(robot_id), layer, session_id)}


@app.get("/api/memories/{mem_id}")
def memory_detail(mem_id: int, robot_id: Optional[str] = None):
    rid = _resolve_robot(robot_id)
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM memories WHERE id=?", (mem_id,)).fetchone()
    if not row:
        raise HTTPException(404, "not found")
    d = row_to_dict(row)
    if d.get("robot_id") and d["robot_id"] != rid:
        raise HTTPException(404, "not found")
    return d


@app.get("/api/conversations")
def conversations(robot_id: Optional[str] = None,
                  session_id: Optional[str] = None, limit: int = 100):
    rid = _resolve_robot(robot_id)
    sql = "SELECT * FROM conversations WHERE robot_id=?"
    args: list = [rid]
    if session_id:
        sql += " AND session_id=?"
        args.append(session_id)
    sql += " ORDER BY id DESC LIMIT ?"
    args.append(limit)
    with get_conn() as conn:
        rows = conn.execute(sql, args).fetchall()
    return {"items": [dict(r) for r in reversed(rows)]}


@app.get("/api/proactive_log")
def proactive_log(robot_id: Optional[str] = None,
                  limit: int = 50):
    rid = _resolve_robot(robot_id)
    with get_conn() as conn:
        rows = conn.execute(
            """SELECT * FROM proactive_log WHERE robot_id=?
               ORDER BY id DESC LIMIT ?""", (rid, limit),
        ).fetchall()
    items = [dict(r) for r in rows]
    for it in items:
        try:
            it["related_memory_ids"] = json.loads(it["related_memory_ids"])
        except Exception:
            pass
    return {"items": items}


@app.get("/api/proactive_messages")
def proactive_messages(robot_id: Optional[str] = None,
                       session_id: Optional[str] = None,
                       since_id: int = 0, limit: int = 50):
    rid = _resolve_robot(robot_id)
    sql = ("SELECT id, session_id, content, metadata, created_at "
           "FROM conversations WHERE robot_id=? AND role='proactive' AND id>?")
    args: list = [rid, since_id]
    if session_id:
        sql += " AND session_id=?"
        args.append(session_id)
    sql += " ORDER BY id ASC LIMIT ?"
    args.append(limit)
    with get_conn() as conn:
        rows = conn.execute(sql, args).fetchall()
    items = []
    for r in rows:
        d = dict(r)
        try:
            d["metadata"] = json.loads(d["metadata"]) if d.get("metadata") else {}
        except Exception:
            d["metadata"] = {}
        items.append(d)
    return {"items": items,
            "last_id": items[-1]["id"] if items else since_id}


@app.get("/api/reminders")
def reminders_list(robot_id: Optional[str] = None,
                   status: Optional[str] = None):
    return {"items": list_reminders(_resolve_robot(robot_id), status)}


@app.delete("/api/reminders/{reminder_id}")
def reminders_cancel(reminder_id: int):
    ok = cancel_reminder(reminder_id)
    if not ok:
        raise HTTPException(404, "not found or already fired")
    return {"ok": True, "id": reminder_id}


class WipeReqModel(BaseModel):
    scope: str = "memories"
    robot_id: Optional[str] = None


class RobotPatchModel(BaseModel):
    display_name: str


class ConfigUpdateModel(BaseModel):
    config: dict


class ServiceUpdateModel(BaseModel):
    speech_enabled: Optional[bool] = None


def _schedule_process_restart(delay: float = 0.6) -> None:
    """拉起新进程后退出当前进程（用于 admin 重启服务）。"""
    root = Path(__file__).resolve().parent.parent

    def _work():
        time.sleep(delay)
        cmd = [sys.executable, "-m", "backend.main"]
        kwargs = {
            "cwd": str(root),
            "stdin": subprocess.DEVNULL,
            "stdout": subprocess.DEVNULL,
            "stderr": subprocess.DEVNULL,
        }
        if sys.platform == "win32":
            kwargs["creationflags"] = (
                subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
            )
        subprocess.Popen(cmd, **kwargs)
        os._exit(0)

    threading.Thread(target=_work, daemon=True).start()


def _admin_token() -> str:
    from . import config as cfg_mod
    return (cfg_mod.SERVER_CFG.get("admin_token") or "").strip()


def verify_admin_token(x_admin_token: Optional[str] = None):
    token = _admin_token()
    if not token:
        return
    if x_admin_token != token:
        raise HTTPException(401, "invalid admin token")


def _admin_dep(request: Request):
    verify_admin_token(request.headers.get("X-Admin-Token"))


@app.get("/api/admin/robots", dependencies=[Depends(_admin_dep)])
def admin_robots_list(q: Optional[str] = None):
    return {"items": list_robots_with_stats(q)}


@app.get("/api/admin/robots/{robot_id}", dependencies=[Depends(_admin_dep)])
def admin_robot_detail(robot_id: str):
    detail = get_robot_detail(robot_id)
    if not detail:
        raise HTTPException(404, "robot not found")
    return detail


@app.patch("/api/admin/robots/{robot_id}", dependencies=[Depends(_admin_dep)])
def admin_robot_patch(robot_id: str, req: RobotPatchModel):
    return update_robot_display_name(robot_id, req.display_name.strip())


@app.delete("/api/admin/robots/{robot_id}", dependencies=[Depends(_admin_dep)])
def admin_robot_delete(robot_id: str):
    deleted = delete_robot_all(robot_id)
    L1_BUFFER.clear(robot_id)
    log.info("[admin.delete_robot] robot=%s deleted=%s", robot_id, deleted)
    return {"ok": True, "robot_id": robot_id, "deleted": deleted}


@app.delete("/api/admin/memories/{mem_id}", dependencies=[Depends(_admin_dep)])
def admin_memory_delete(mem_id: int, robot_id: Optional[str] = None):
    rid = _resolve_robot(robot_id)
    if not delete_memory(mem_id, rid):
        raise HTTPException(404, "not found")
    return {"ok": True, "id": mem_id}


@app.get("/api/admin/config", dependencies=[Depends(_admin_dep)])
def admin_config_get():
    return {
        "config": get_config_for_admin(),
        "schema": ADMIN_CONFIG_SCHEMA,
        "tts_voices": speech.list_tts_voices(),
        "config_path": str(Path(__file__).resolve().parent.parent / "config.yaml"),
        "restart_required_for": ["server.host", "server.port"],
    }


@app.put("/api/admin/config", dependencies=[Depends(_admin_dep)])
def admin_config_put(req: ConfigUpdateModel):
    if not isinstance(req.config, dict):
        raise HTTPException(400, "config 必须是对象")
    updated = save_config(req.config)
    log.info("[admin.config] updated sections=%s", list(req.config.keys()))
    return {
        "ok": True,
        "config": updated,
        "tts_voices": speech.list_tts_voices(),
        "restart_required_for": ["server.host", "server.port"],
    }


@app.get("/api/admin/service", dependencies=[Depends(_admin_dep)])
def admin_service_get():
    return {
        "pid": os.getpid(),
        "host": SERVER_CFG.get("host", "0.0.0.0"),
        "port": int(SERVER_CFG.get("port", 8000)),
        "speech_config_enabled": bool(SPEECH_CFG.get("enabled", False)),
        "speech_runtime": speech.is_enabled(),
    }


@app.put("/api/admin/service", dependencies=[Depends(_admin_dep)])
def admin_service_put(req: ServiceUpdateModel):
    if req.speech_enabled is None:
        raise HTTPException(400, "需要 speech_enabled")
    updated = save_config({"speech": {"enabled": req.speech_enabled}})
    enabled = bool(updated.get("speech", {}).get("enabled"))
    log.info("[admin.service] speech.enabled=%s runtime=%s",
             enabled, speech.is_enabled())
    return {
        "ok": True,
        "speech_config_enabled": enabled,
        "speech_runtime": speech.is_enabled(),
    }


@app.post("/api/admin/service/restart", dependencies=[Depends(_admin_dep)])
def admin_service_restart():
    log.info("[admin.service] restart requested pid=%s", os.getpid())
    _schedule_process_restart()
    return {"ok": True, "message": "服务正在重启"}


@app.post("/api/admin/wipe", dependencies=[Depends(_admin_dep)])
def admin_wipe(req: WipeReqModel):
    robot_id = _resolve_robot(req.robot_id)
    scope = (req.scope or "memories").lower()
    if scope not in {"memories", "conversations", "reminders", "all"}:
        raise HTTPException(400, "scope 必须是 memories/conversations/reminders/all")

    deleted: dict[str, int] = {}
    with get_conn() as conn:
        def _del(table: str):
            cur = conn.execute(f"DELETE FROM {table} WHERE robot_id=?", (robot_id,))
            deleted[table] = cur.rowcount

        if scope in ("memories", "all"):
            _del("memories")
        if scope in ("conversations", "all"):
            _del("conversations")
        if scope in ("reminders", "all"):
            _del("reminders")
        if scope == "all":
            _del("proactive_log")

    if scope in ("memories", "all"):
        L1_BUFFER.clear(robot_id)

    log.info("[admin.wipe] robot=%s scope=%s deleted=%s", robot_id, scope, deleted)
    return {"ok": True, "scope": scope, "deleted": deleted}


@app.get("/api/l1_frames")
def l1_frames(robot_id: Optional[str] = None):
    return {"items": L1_BUFFER.snapshot(_resolve_robot(robot_id))}


if FRONTEND.exists():
    app.mount("/static", StaticFiles(directory=str(FRONTEND)), name="static")

    @app.get("/")
    def index():
        return FileResponse(str(FRONTEND / "index.html"))

    @app.get("/admin")
    def admin_page():
        return FileResponse(str(FRONTEND / "admin.html"))


def run():
    import uvicorn
    uvicorn.run(app, host=SERVER_CFG.get("host", "0.0.0.0"),
                port=int(SERVER_CFG.get("port", 8000)))


if __name__ == "__main__":
    run()
