"""JSON API 契约：表情枚举、语音属性、聊天请求/响应模型。"""
from __future__ import annotations

import re
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, field_validator


class FacialExpression(str, Enum):
    angry = "angry"
    disgust = "disgust"
    fear = "fear"
    happy = "happy"
    neutral = "neutral"
    sad = "sad"
    surprise = "surprise"


class RobotState(str, Enum):
    """虚拟宠物有限状态机（FSM）9 态——机器人回复可携带的互动动作状态。"""
    idle = "idle"
    gazing = "gazing"
    listening = "listening"
    thinking = "thinking"
    happy = "happy"
    confused = "confused"
    sleepy = "sleepy"
    sleeping = "sleeping"
    waking = "waking"


ROBOT_STATE_LABELS: dict[RobotState, str] = {
    RobotState.idle: "待机",
    RobotState.gazing: "注视",
    RobotState.listening: "聆听",
    RobotState.thinking: "思考",
    RobotState.happy: "高兴",
    RobotState.confused: "困惑",
    RobotState.sleepy: "困倦",
    RobotState.sleeping: "睡眠",
    RobotState.waking: "苏醒",
}


# 机器人输出表情 → FSM 状态的建议映射（robot_state 缺省时由此推导）
EXPRESSION_TO_STATE: dict[FacialExpression, RobotState] = {
    FacialExpression.happy: RobotState.happy,
    FacialExpression.neutral: RobotState.idle,
    FacialExpression.sad: RobotState.sleepy,
    FacialExpression.angry: RobotState.confused,
    FacialExpression.disgust: RobotState.confused,
    FacialExpression.fear: RobotState.confused,
    FacialExpression.surprise: RobotState.confused,
}


def parse_robot_state(value: Optional[str]) -> Optional[RobotState]:
    if not value:
        return None
    v = value.strip()
    if not v:
        return None
    try:
        return RobotState(v)
    except ValueError:
        return None


def robot_state_for_expression(expr: FacialExpression) -> RobotState:
    return EXPRESSION_TO_STATE.get(expr, RobotState.idle)


FACIAL_EXPRESSION_LABELS: dict[FacialExpression, str] = {
    FacialExpression.angry: "恼怒",
    FacialExpression.disgust: "厌恶",
    FacialExpression.fear: "恐惧",
    FacialExpression.happy: "开心",
    FacialExpression.neutral: "中性",
    FacialExpression.sad: "悲伤",
    FacialExpression.surprise: "惊讶",
}

# 表情别名（LLM、网页端或 XBot 端侧可能返回中文标签或英文变体）
FACIAL_EXPRESSION_ALIASES: dict[str, FacialExpression] = {
    # 中文
    "愤怒": FacialExpression.angry,
    "恼怒": FacialExpression.angry,
    "厌恶": FacialExpression.disgust,
    "恐惧": FacialExpression.fear,
    "快乐": FacialExpression.happy,
    "开心": FacialExpression.happy,
    "中性": FacialExpression.neutral,
    "悲伤": FacialExpression.sad,
    "惊讶": FacialExpression.surprise,
    "轻蔑": FacialExpression.disgust,
    # 英文变体（XBot 端侧使用 surprised/disgusted/fearful 等）
    "surprised": FacialExpression.surprise,
    "disgusted": FacialExpression.disgust,
    "fearful": FacialExpression.fear,
    "afraid": FacialExpression.fear,
    "mad": FacialExpression.angry,
    "anger": FacialExpression.angry,
    "joyful": FacialExpression.happy,
    "joy": FacialExpression.happy,
    "calm": FacialExpression.neutral,
}


def parse_facial_expression(value: Optional[str]) -> Optional[FacialExpression]:
    if not value:
        return None
    v = value.strip()
    if not v:
        return None
    try:
        return FacialExpression(v)
    except ValueError:
        return (FACIAL_EXPRESSION_ALIASES.get(v)
                or FACIAL_EXPRESSION_ALIASES.get(v.lower()))


def facial_expression_label(expr: Optional[FacialExpression]) -> Optional[str]:
    if expr is None:
        return None
    return FACIAL_EXPRESSION_LABELS.get(expr)


# 手势词表：XBot 端侧手势 key → 中文（用于喂给 LLM）
GESTURE_LABELS: dict[str, str] = {
    "wave": "挥手",
    "nod": "点头",
    "shake_head": "摇头",
    "thumbs_up": "点赞",
    "heart": "比心",
    "raise_hand": "举手",
    "ok": "OK手势",
    "victory": "比耶",
    "fist": "握拳",
    "point": "指向",
    "open_palm": "张开手掌",
}


def gesture_label(key: Optional[str]) -> Optional[str]:
    if not key:
        return None
    k = key.strip()
    if not k:
        return None
    return GESTURE_LABELS.get(k) or GESTURE_LABELS.get(k.lower()) or k


_MONOLOGUE_MARKERS: list[tuple[str, int]] = [
    (r"长期记忆", 2),
    (r"用户.{0,20}(表情|说|长期|可能|悲伤|开心|抚摸)", 2),
    (r"我(需要|应该|得)(回应|安慰|安抚|回复|温暖)", 2),
    (r"结合.{0,30}(抚摸|表情|语气|悲伤)", 2),
    (r"似乎(有点|有些|是)", 1),
    (r"不要追问", 2),
    (r"\[感知", 2),
    (r"L[1-4][\s/]", 2),
    (r"触发了", 1),
    (r"被用户", 2),
    (r"分析一下|推测|推断", 2),
]


def looks_like_internal_monologue(text: str) -> bool:
    """判断文本是否像模型内心分析，而非对用户说的原话。"""
    t = (text or "").strip()
    if len(t) < 12:
        return False
    score = 0
    for pat, weight in _MONOLOGUE_MARKERS:
        if re.search(pat, t):
            score += weight
    return score >= 2


class VoiceProsody(BaseModel):
    """语音副通道：语气 / 语调 / 语速。"""
    tone: Optional[str] = None
    intonation: Optional[str] = None
    speed: Optional[str] = None


def voice_for_expression(expr: FacialExpression,
                         voice: Optional[VoiceProsody] = None) -> VoiceProsody:
    """按表情补全语音情感参数。"""
    defaults = {
        FacialExpression.happy: VoiceProsody(tone="兴奋", intonation="上扬", speed="正常"),
        FacialExpression.sad: VoiceProsody(tone="低落", intonation="下沉", speed="慢"),
        FacialExpression.angry: VoiceProsody(tone="急躁", intonation="起伏大", speed="快"),
        FacialExpression.fear: VoiceProsody(tone="疑问", intonation="起伏大", speed="快"),
        FacialExpression.surprise: VoiceProsody(tone="惊讶", intonation="上扬", speed="快"),
        FacialExpression.disgust: VoiceProsody(tone="冷淡", intonation="平稳", speed="正常"),
        FacialExpression.neutral: VoiceProsody(tone="温柔", intonation="平稳", speed="正常"),
    }
    base = defaults.get(expr, VoiceProsody(tone="温柔", intonation="平稳", speed="正常"))
    if not voice:
        return base
    return VoiceProsody(
        tone=voice.tone or base.tone,
        intonation=voice.intonation or base.intonation,
        speed=voice.speed or base.speed,
    )


class GestureAction(BaseModel):
    """手势（预留，当前不实现）。"""
    type: Optional[str] = Field(None, description="预留：挥手/点头等")
    params: Optional[Dict[str, Any]] = None


class PostureAction(BaseModel):
    """体姿态（预留，当前不实现）。"""
    type: Optional[str] = Field(None, description="预留：端坐/瘫坐等")
    params: Optional[Dict[str, Any]] = None


class AudioPayload(BaseModel):
    format: str = "wav"
    encoding: str = "base64"
    sample_rate: int = 16000
    data: str = ""


class PerceptionInput(BaseModel):
    facial_expression: Optional[FacialExpression] = None
    voice: Optional[VoiceProsody] = None
    touch: Optional[str] = None
    identity: Optional[str] = Field(
        None, description="端侧身份识别到的人名（如『小明』）；仅作感知上下文，不分人记忆")
    gesture: Optional[GestureAction] = None
    posture: Optional[PostureAction] = None

    @field_validator("facial_expression", mode="before")
    @classmethod
    def _normalize_expression(cls, v):
        """归一化端侧表情 key（surprised/disgusted/fearful 等）与中文别名。"""
        if v is None or isinstance(v, FacialExpression):
            return v
        if isinstance(v, str):
            expr = parse_facial_expression(v)
            return expr  # None 时交由后续逻辑当作未提供
        return v

    @field_validator("identity", mode="before")
    @classmethod
    def _normalize_identity(cls, v):
        """兼容端侧传 {name, confidence} 结构，取 name。"""
        if isinstance(v, dict):
            name = v.get("name") or v.get("label")
            return str(name).strip() if name else None
        if isinstance(v, str):
            return v.strip() or None
        return v


class ChatInput(BaseModel):
    text: str = ""
    audio: Optional[AudioPayload] = None
    perception: Optional[PerceptionInput] = None
    voice_id: Optional[str] = Field(None, description="TTS 音色 ID，如 gentle_female")
    skip_tts: Optional[bool] = Field(
        None,
        description="true 时 chat 不内嵌 TTS，客户端可另调 POST /api/tts 以加快文字回复",
    )


class RobotOutput(BaseModel):
    text: str = ""
    facial_expression: FacialExpression = FacialExpression.neutral
    facial_expression_label: Optional[str] = None
    robot_state: Optional[RobotState] = None
    robot_state_label: Optional[str] = None
    voice: Optional[VoiceProsody] = None
    audio: Optional[AudioPayload] = None
    gesture: Optional[GestureAction] = None
    posture: Optional[PostureAction] = None

    def finalize(self) -> "RobotOutput":
        self.facial_expression_label = facial_expression_label(self.facial_expression)
        # robot_state 缺省时按表情推导，保证回复始终携带一个合法 FSM 互动状态
        if self.robot_state is None:
            self.robot_state = robot_state_for_expression(self.facial_expression)
        self.robot_state_label = ROBOT_STATE_LABELS.get(self.robot_state)
        return self


class SttResult(BaseModel):
    text: str = ""
    voice: Optional[VoiceProsody] = None


class ChatRequest(BaseModel):
    robot_id: Optional[str] = None
    user_id: Optional[str] = Field(
        None, description="可选用户标识（端侧身份）；仅做溯源回显，记忆仍按 robot_id 隔离")
    session_id: Optional[str] = None
    input: ChatInput


class ChatResponse(BaseModel):
    robot_id: str
    user_id: str = "default"
    session_id: str
    output: RobotOutput
    stt: Optional[SttResult] = None
    memory_flow: List = []
    l1_frames: List = []
    recalled: List = []
    scheduled_reminders: List = []


class SttRequest(BaseModel):
    audio: AudioPayload


class TtsRequest(BaseModel):
    text: str
    voice: Optional[VoiceProsody] = None
    voice_id: Optional[str] = Field(None, description="TTS 音色 ID")


class TtsResponse(BaseModel):
    text: str
    voice: Optional[VoiceProsody] = None
    audio: Optional[AudioPayload] = None


VALID_OWNER_GENDERS = frozenset({"male", "female", "other"})
_BIRTHDAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class OwnerProfile(BaseModel):
    """主人档案：首次激活向导采集，绑定 robot_id。"""
    nickname: str
    robot_name: str
    gender: Optional[str] = None
    birthday: Optional[str] = None
    face_registered: bool = False

    @field_validator("nickname", "robot_name", mode="before")
    @classmethod
    def _strip_required(cls, v):
        if v is None:
            return v
        return str(v).strip()

    @field_validator("gender", mode="before")
    @classmethod
    def _normalize_gender(cls, v):
        if v is None or v == "":
            return None
        g = str(v).strip().lower()
        if g not in VALID_OWNER_GENDERS:
            raise ValueError("gender must be male, female, or other")
        return g

    @field_validator("birthday", mode="before")
    @classmethod
    def _normalize_birthday(cls, v):
        if v is None or v == "":
            return None
        b = str(v).strip()
        if not _BIRTHDAY_RE.match(b):
            raise ValueError("birthday must be YYYY-MM-DD")
        return b


class OwnerProfilePutRequest(BaseModel):
    owner: OwnerProfile


class OwnerProfilePutResponse(BaseModel):
    ok: bool = True
    robot_id: str
    owner: OwnerProfile


def perception_to_dict(perception: Optional[PerceptionInput]) -> Dict:
    """把 PerceptionInput 展平为给 LLM 用的 dict。"""
    if not perception:
        return {}
    data: Dict = {}
    if perception.facial_expression:
        data["facial_expression"] = facial_expression_label(perception.facial_expression)
    if perception.voice:
        if perception.voice.tone:
            data["tone"] = perception.voice.tone
        if perception.voice.intonation:
            data["intonation"] = perception.voice.intonation
        if perception.voice.speed:
            data["speed"] = perception.voice.speed
    if perception.touch:
        data["touch"] = perception.touch
    if perception.identity:
        data["identity"] = perception.identity
    if perception.gesture and perception.gesture.type:
        data["gesture"] = gesture_label(perception.gesture.type)
    # posture reserved — not sent to LLM yet
    return data


PERCEPTION_LABELS = [
    ("tone", "语气"),
    ("intonation", "语调"),
    ("speed", "语速"),
    ("touch", "抚摸"),
    ("gesture", "手势"),
    ("identity", "身份"),
    ("facial_expression", "表情"),
]

VOICE_KEYS = {"tone", "intonation", "speed"}


def format_perception_dict(data: Dict) -> str:
    parts = []
    for key, label in PERCEPTION_LABELS:
        v = data.get(key)
        if isinstance(v, str):
            v = v.strip()
        if v:
            parts.append(f"{label}:{v}")
    return " ".join(parts)


def robot_output_from_llm(data: Dict, fallback_text: str = "") -> RobotOutput:
    """从 LLM JSON 解析 RobotOutput，失败时 fallback。"""
    text = (data.get("text") or fallback_text or "").strip()
    expr = parse_facial_expression(data.get("facial_expression")) or FacialExpression.neutral
    voice_raw = data.get("voice") or {}
    voice = None
    if isinstance(voice_raw, dict) and any(voice_raw.get(k) for k in ("tone", "intonation", "speed")):
        voice = VoiceProsody(
            tone=voice_raw.get("tone"),
            intonation=voice_raw.get("intonation"),
            speed=voice_raw.get("speed"),
        )
    voice = voice_for_expression(expr, voice)
    # robot_state 仅接受 FSM 9 态；非法/缺省交由 finalize 按表情推导
    state = parse_robot_state(data.get("robot_state"))
    return RobotOutput(
        text=text,
        facial_expression=expr,
        robot_state=state,
        voice=voice,
        gesture=None,
        posture=None,
    ).finalize()


# 用户负面情绪时，机器人若回 neutral 会显得冷漠
_EMPATHY_EXPRESSION: dict[FacialExpression, FacialExpression] = {
    FacialExpression.sad: FacialExpression.sad,
    FacialExpression.fear: FacialExpression.sad,
    FacialExpression.angry: FacialExpression.sad,
}


def align_output_to_user_perception(
    output: RobotOutput,
    user_expr: Optional[FacialExpression],
) -> RobotOutput:
    """用户有明显负面情绪时，将机器人 neutral 表情纠正为共情（sad）。"""
    if not user_expr or output.facial_expression != FacialExpression.neutral:
        return output
    target = _EMPATHY_EXPRESSION.get(user_expr)
    if not target:
        return output
    output.facial_expression = target
    # 共情安慰：表情心疼，语气温柔、语调下沉、语速慢（勿用 neutral 的平稳正常）
    output.voice = VoiceProsody(tone="温柔", intonation="下沉", speed="慢")
    # 表情被纠正后，FSM 互动状态同步按新表情推导
    output.robot_state = robot_state_for_expression(target)
    return output.finalize()


REPLY_JSON_INSTRUCTION = """
【输出格式 — 最高优先级，每次必须遵守】
你只输出一个 JSON 对象，不要 markdown、不要 ```、不要 JSON 前后的任何说明。
第一个字符必须是 {，最后一个字符必须是 }。

固定结构：
{"text":"对用户说的话","facial_expression":"sad","robot_state":"sleepy","voice":{"tone":"温柔","intonation":"下沉","speed":"慢"},"gesture":null,"posture":null}

完整示例（照着这个格式回，只改 text/表情/robot_state/voice 内容）：

用户输入：[非语言信号 表情:悲伤]
你的输出：{"text":"我在这儿呢，想安静待着我就陪着。","facial_expression":"sad","robot_state":"sleepy","voice":{"tone":"温柔","intonation":"下沉","speed":"慢"},"gesture":null,"posture":null}

用户输入：[感知 表情:悲伤] 你好
你的输出：{"text":"你好呀，我在这儿陪着你。","facial_expression":"sad","robot_state":"sleepy","voice":{"tone":"温柔","intonation":"下沉","speed":"慢"},"gesture":null,"posture":null}

用户输入：今天天气不错
你的输出：{"text":"是呀，要不要出去走走？","facial_expression":"happy","robot_state":"happy","voice":{"tone":"兴奋","intonation":"上扬","speed":"正常"},"gesture":null,"posture":null}

字段说明：
- text: 你对用户亲口说的话（1～3 句，用「你/我」直接对话）。禁止写思考过程、禁止第三人称分析用户、禁止提长期记忆或感知标签。
- facial_expression: 机器人自己的表情，取值 angry|disgust|fear|happy|neutral|sad|surprise
- robot_state: 机器人此刻的互动动作状态，**只能取虚拟形象状态机 9 态之一**：idle(待机)|gazing(注视)|listening(聆听)|thinking(思考)|happy(高兴)|confused(困惑)|sleepy(困倦)|sleeping(睡眠)|waking(苏醒)。不要使用此列表以外的任何值。
- voice.tone: 温柔|平静|急躁|兴奋|低落|撒娇|疑问|冷淡
- voice.intonation: 平稳|上扬|下沉|起伏大
- voice.speed: 慢|正常|快|极快
- gesture/posture: 固定 null

表情与状态规则：
- 用户悲伤/恐惧/恼怒且你在安慰：facial_expression 用 sad，robot_state 用 sleepy，voice 温柔+下沉+慢
- 用户开心 / 你也开心：facial_expression 用 happy，robot_state 用 happy
- 平淡闲聊：facial_expression 用 neutral，robot_state 用 idle 或 gazing
- 没听懂 / 意外：robot_state 用 confused
- robot_state 必须与语气一致，且只能是上面 9 个值之一"""
