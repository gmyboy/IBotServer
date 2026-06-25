"""语音模块：STT + TTS（阿里云 DashScope 在线 API）。"""
from __future__ import annotations

import base64
import io
import logging
import os
import queue
import re
import threading
import time
import wave
from typing import Any, Dict, Iterator, Optional, Tuple

from .config import SPEECH_CFG
from .schemas import AudioPayload, SttResult, VoiceProsody

log = logging.getLogger("pophie.speech")

_stt_lock = threading.Lock()
_tts_lock = threading.Lock()

DASHSCOPE_TTS_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"

# voice_id -> (DashScope API voice, 显示名)
# cosyvoice-v3-flash 音色，见 https://help.aliyun.com/zh/model-studio/cosyvoice-voice-list
TTS_VOICES: dict[str, tuple[str, str]] = {
    # 标杆音色（支持 Instruct 情感/情境控制）
    "sunny_boy": ("longanyang", "阳光大男孩"),
    "energetic_girl": ("longanhuan", "欢脱元气女"),
    # 社交陪伴
    "gentle_female": ("longxiaochun_v3", "知性女声"),
    "lively_female": ("longxiaoxia_v3", "沉稳女声"),
    "natural_female": ("longwan_v3", "柔声女声"),
    "warm_female": ("longyue_v3", "温暖磁性女"),
    "elegant_female": ("longanwen_v3", "优雅知性女"),
    "cozy_female": ("longanqin_v3", "亲和活泼女"),
    "graceful_female": ("longanya_v3", "高雅气质女"),
    "clever_female": ("longanling_v3", "思维灵动女"),
    "soft_female": ("longanrou_v3", "温柔闺蜜女"),
    "sweet_female": ("longfeifei_v3", "甜美娇气女"),
    "youthful_female": ("longhua_v3", "元气甜美女"),
    "gentle_male": ("longanyun_v3", "居家暖男"),
    "calm_male": ("longshu_v3", "沉稳男声"),
    "sunny_male": ("longshuo_v3", "干练男声"),
    "deep_male": ("longfei_v3", "磁性男声"),
    "clear_male": ("longanlang_v3", "清爽利落男"),
    "wise_male": ("longanzhi_v3", "睿智男声"),
    "warm_male": ("longze_v3", "温暖元气男"),
    "magnetic_male": ("longtian_v3", "磁性理智男"),
    "refreshing_male": ("longanshuo_v3", "干净清爽男"),
    "yumi_female": ("longyumi_v3", "正经青年女"),
    # 童声 / 特色
    "child_girl": ("longhuhu_v3", "童声女童"),
    "child_boy": ("longniuniu_v3", "童声男童"),
    "cartoon_male": ("longjielidou_v3", "顽皮童声"),
    "child_bubble": ("longpaopao_v3", "泡泡童声"),
    "robot_voice": ("longjiqi_v3", "呆萌机器人"),
    "monkey_voice": ("longhouge_v3", "经典猴哥"),
    # 有声书 / 朗读
    "cute_female": ("longmiao_v3", "抑扬女声"),
    "storyteller_male": ("longsanshu_v3", "沉稳质感男"),
    "healing_female": ("longyuan_v3", "温暖治愈女"),
    # 方言
    "dialect_northeast": ("longlaotie_v3", "东北男声"),
    "dialect_shaanxi": ("longshange_v3", "陕西男声"),
    "dialect_cantonese_m": ("longanyue_v3", "粤语男声"),
    "dialect_cantonese_f": ("longjiaxin_v3", "粤语女声"),
    "dialect_minnan": ("longanmin_v3", "闽南女声"),
    "dialect_taiwan": ("longantai_v3", "台湾女声"),
    # 其他
    "professional_female": ("loongbella_v3", "干练女声"),
}

DEFAULT_VOICE_ID = "gentle_female"

# 仅下列 DashScope 音色支持 instruction 参数（见 CosyVoice 音色列表）
TTS_INSTRUCT_VOICES: frozenset[str] = frozenset({
    "longanyang", "longanhuan", "longhuhu_v3",
})

# Qwen3-ASR / SenseVoice 情感标签 → VoiceProsody.tone
_EMOTION_TO_TONE = {
    "HAPPY": "兴奋", "SAD": "低落", "ANGRY": "急躁", "NEUTRAL": "平静",
    "FEARFUL": "疑问", "DISGUSTED": "冷淡", "SURPRISED": "惊讶",
    "happy": "兴奋", "sad": "低落", "angry": "急躁", "neutral": "平静",
    "fearful": "疑问", "disgusted": "冷淡", "surprised": "惊讶",
    "开心": "兴奋", "悲伤": "低落", "愤怒": "急躁", "恼怒": "急躁",
    "中性": "平静", "恐惧": "疑问", "厌恶": "冷淡", "惊讶": "惊讶",
}

# VoiceProsody.tone → CosyVoice Instruct 情感值（见音色列表文档）
_TONE_TO_COSYVOICE_EMOTION = {
    "兴奋": "happy",
    "惊讶": "surprised",
    "低落": "sad",
    "急躁": "angry",
    "平静": "neutral",
    "温柔": "neutral",
    "疑问": "fearful",
    "冷淡": "disgusted",
}


def _stt_engine() -> str:
    return "dashscope"


def tts_engine() -> str:
    return "dashscope-realtime"


def _dashscope_api_key() -> str:
    stt_key = (SPEECH_CFG.get("stt", {}).get("api_key") or "").strip()
    tts_key = (SPEECH_CFG.get("tts", {}).get("api_key") or "").strip()
    return stt_key or tts_key or (os.environ.get("DASHSCOPE_API_KEY") or "").strip()


def is_enabled() -> bool:
    if not bool(SPEECH_CFG.get("enabled", False)):
        return False
    if not _dashscope_api_key():
        log.warning(
            "speech.enabled=true 但未配置 DashScope API Key "
            "(speech.tts.api_key / speech.stt.api_key / DASHSCOPE_API_KEY)"
        )
        return False
    try:
        import dashscope  # noqa: F401
        return True
    except ImportError:
        log.warning(
            "speech.enabled=true 但未安装 dashscope；"
            "请执行: pip install dashscope"
        )
        return False


def list_tts_voices() -> list:
    default = default_voice_id()
    return [
        {
            "id": vid,
            "label": label,
            "default": vid == default,
            "instruct": api_voice in TTS_INSTRUCT_VOICES,
        }
        for vid, (api_voice, label) in TTS_VOICES.items()
    ]


def default_voice_id() -> str:
    cfg_id = SPEECH_CFG.get("tts", {}).get("default_voice")
    if cfg_id and cfg_id in TTS_VOICES:
        return cfg_id
    return DEFAULT_VOICE_ID


def resolve_voice_id(voice_id: Optional[str] = None) -> str:
    if voice_id and voice_id in TTS_VOICES:
        return voice_id
    return default_voice_id()


def _dashscope_voice(voice_id: Optional[str] = None) -> str:
    return TTS_VOICES[resolve_voice_id(voice_id)][0]


def tts_output_format() -> str:
    return str(SPEECH_CFG.get("tts", {}).get("format", "mp3")).lower()


def tts_stream_format() -> str:
    return str(SPEECH_CFG.get("tts", {}).get("stream_format", "pcm")).lower()


def tts_sample_rate() -> int:
    return int(SPEECH_CFG.get("tts", {}).get("sample_rate", 22050))


def _decode_audio(audio: AudioPayload) -> bytes:
    if audio.encoding != "base64":
        raise ValueError(f"不支持的音频编码: {audio.encoding}")
    return base64.b64decode(audio.data)


def _wav_to_pcm16(wav_bytes: bytes) -> Tuple[bytes, int]:
    with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
        channels = wf.getnchannels()
        sample_rate = wf.getframerate()
        sampwidth = wf.getsampwidth()
        if channels != 1:
            raise ValueError("STT 需要单声道音频")
        if sampwidth != 2:
            raise ValueError("STT 需要 16-bit PCM WAV")
        pcm = wf.readframes(wf.getnframes())
    return pcm, sample_rate


def _strip_emotion_tags(text: str) -> Tuple[str, Optional[str]]:
    """解析 SenseVoice 风格嵌入标签，如 <|HAPPY|> 或末尾 |HAPPY|。"""
    raw = text or ""
    emotion = None
    emo_match = re.search(r"<\|([A-Za-z_]+)\|>", raw)
    if emo_match:
        emotion = emo_match.group(1)
    clean = re.sub(r"<\|[^|]+\|>", "", raw).strip()
    tail = re.search(r"\|([A-Z]+)\|$", clean)
    if tail:
        emotion = emotion or tail.group(1)
        clean = clean[: tail.start()].strip()
    return clean, emotion


def _voice_from_emotion(emotion: Optional[str]) -> Optional[VoiceProsody]:
    if not emotion:
        return None
    tone = (
        _EMOTION_TO_TONE.get(emotion)
        or _EMOTION_TO_TONE.get(emotion.upper())
        or _EMOTION_TO_TONE.get(emotion.lower())
    )
    if not tone:
        return None
    return VoiceProsody(tone=tone, intonation="平稳", speed="正常")


def _transcribe_dashscope(wav_bytes: bytes) -> SttResult:
    import dashscope
    from dashscope.audio.qwen_omni import MultiModality, OmniRealtimeCallback, OmniRealtimeConversation
    from dashscope.audio.qwen_omni.omni_realtime import TranscriptionParams

    stt_cfg = SPEECH_CFG.get("stt", {})
    api_key = _dashscope_api_key()
    if not api_key:
        raise RuntimeError("DashScope STT 未配置 API Key")

    pcm, sample_rate = _wav_to_pcm16(wav_bytes)
    expected_sr = int(stt_cfg.get("sample_rate", 16000))
    if sample_rate != expected_sr:
        raise ValueError(
            f"STT 需要 {expected_sr}Hz 采样率，当前为 {sample_rate}Hz"
        )

    model = stt_cfg.get("model", "qwen3-asr-flash-realtime")
    language = stt_cfg.get("language", "zh")
    chunk_bytes = int(stt_cfg.get("chunk_bytes", 3200))
    timeout = float(stt_cfg.get("timeout", 30))
    max_retries = int(stt_cfg.get("max_retries", 3))
    retry_delay = float(stt_cfg.get("retry_delay_sec", 1.0))
    ws_url = stt_cfg.get("base_url")  # 默认北京地域 wss endpoint

    class _QwenAsrCallback(OmniRealtimeCallback):
        def __init__(self) -> None:
            self.text = ""
            self.emotion: Optional[str] = None
            self.error: Optional[str] = None
            self._done = threading.Event()

        def on_event(self, message: Dict[str, Any]) -> None:
            if not isinstance(message, dict):
                return
            evt = message.get("type")
            if evt == "conversation.item.input_audio_transcription.completed":
                self.text = (message.get("transcript") or "").strip()
                self.emotion = message.get("emotion") or self.emotion
                self._done.set()
            elif evt == "conversation.item.input_audio_transcription.text":
                partial = f"{message.get('text') or ''}{message.get('stash') or ''}"
                if partial.strip():
                    self.text = partial.strip()
                if message.get("emotion"):
                    self.emotion = message.get("emotion")
            elif evt == "error":
                self.error = message.get("message") or str(message)
                self._done.set()

        def wait(self, wait_timeout: float) -> None:
            if not self._done.wait(wait_timeout):
                raise RuntimeError("STT 识别超时")

    dashscope.api_key = api_key
    last_err: Optional[Exception] = None
    for attempt in range(max_retries):
        t0 = time.time()
        callback = _QwenAsrCallback()
        conversation = OmniRealtimeConversation(
            model=model,
            callback=callback,
            api_key=api_key,
            url=ws_url,
            headers={"OpenAI-Beta": "realtime=v1"},
        )
        try:
            with _stt_lock:
                conversation.connect()
                conversation.update_session(
                    output_modalities=[MultiModality.TEXT],
                    enable_input_audio_transcription=True,
                    enable_turn_detection=False,
                    transcription_params=TranscriptionParams(
                        language=language,
                        sample_rate=sample_rate,
                        input_audio_format="pcm",
                    ),
                )
                time.sleep(float(stt_cfg.get("connect_delay_sec", 0.1)))
                for i in range(0, len(pcm), chunk_bytes):
                    chunk = pcm[i : i + chunk_bytes]
                    conversation.append_audio(
                        base64.b64encode(chunk).decode("ascii"),
                    )
                conversation.commit()
                conversation.end_session(timeout=int(timeout))
                callback.wait(timeout)
            if callback.error:
                raise RuntimeError(callback.error)

            text, tagged_emotion = _strip_emotion_tags(callback.text)
            emotion = callback.emotion or tagged_emotion
            voice = _voice_from_emotion(emotion)
            log.info(
                "[speech] STT ok in %.0fms text=%r emotion=%s voice=%s model=%s",
                (time.time() - t0) * 1000, text, emotion, voice, model,
            )
            return SttResult(text=text, voice=voice)
        except Exception as e:
            last_err = e
            log.warning(
                "[speech] STT 失败 attempt=%d/%d: %s",
                attempt + 1, max_retries, e,
            )
            if attempt + 1 < max_retries:
                time.sleep(retry_delay)
        finally:
            try:
                conversation.close()
            except Exception:
                pass

    raise RuntimeError(f"STT 识别失败: {last_err}") from last_err


def stt_stream_turn_detection() -> bool:
    return bool(SPEECH_CFG.get("stt", {}).get("turn_detection", True))


def stt_silence_commit_ms() -> int:
    return int(SPEECH_CFG.get("stt", {}).get("silence_commit_ms", 600))


def stt_stream_conversation_idle_sec() -> float:
    """对话模式空闲上限：该时长内无 partial / 有效 final 则服务端主动结束会话。"""
    return float(SPEECH_CFG.get("stt", {}).get("stream_conversation_idle_sec", 20))


class SttStreamSession:
    """DashScope 实时 STT 长会话：边收 PCM 边推 partial/final，对话模式内可多轮。"""

    def __init__(
        self,
        *,
        turn_detection: Optional[bool] = None,
        sample_rate: Optional[int] = None,
        language: Optional[str] = None,
    ) -> None:
        stt_cfg = SPEECH_CFG.get("stt", {})
        self.turn_detection = (
            turn_detection
            if turn_detection is not None
            else stt_stream_turn_detection()
        )
        self.sample_rate = int(sample_rate or stt_cfg.get("sample_rate", 16000))
        self.language = language or stt_cfg.get("language", "zh")
        self._stt_cfg = stt_cfg
        self._event_queue: queue.Queue = queue.Queue()
        self._conversation: Any = None
        self._closed = False
        self._connected = False
        self._ready_at: float = 0.0
        self._last_partial_at: float = 0.0
        self._last_final_text_at: float = 0.0

    def conversation_idle_sec(self) -> float:
        return stt_stream_conversation_idle_sec()

    def conversation_idle_expired(self) -> bool:
        """自 ready / 最近 partial / 最近有效 final 起，超过空闲阈值。"""
        if not self._connected or self._ready_at <= 0:
            return False
        ref = max(self._ready_at, self._last_partial_at, self._last_final_text_at)
        return (time.time() - ref) >= self.conversation_idle_sec()

    def conversation_idle_remaining_sec(self) -> float:
        if not self._connected or self._ready_at <= 0:
            return self.conversation_idle_sec()
        ref = max(self._ready_at, self._last_partial_at, self._last_final_text_at)
        return max(0.0, self.conversation_idle_sec() - (time.time() - ref))

    @property
    def is_connected(self) -> bool:
        return self._connected and not self._closed

    def start(self) -> None:
        if not is_enabled():
            raise RuntimeError("语音功能未启用 (speech.enabled=false)")

        import dashscope
        from dashscope.audio.qwen_omni import (
            MultiModality, OmniRealtimeCallback, OmniRealtimeConversation,
        )
        from dashscope.audio.qwen_omni.omni_realtime import TranscriptionParams

        api_key = _dashscope_api_key()
        if not api_key:
            raise RuntimeError("DashScope STT 未配置 API Key")

        model = self._stt_cfg.get("model", "qwen3-asr-flash-realtime")
        ws_url = self._stt_cfg.get("base_url")
        outer = self

        class _QwenAsrStreamCallback(OmniRealtimeCallback):
            def on_event(self, message: Dict[str, Any]) -> None:
                if not isinstance(message, dict) or outer._closed:
                    return
                evt = message.get("type")
                if evt == "conversation.item.input_audio_transcription.text":
                    partial = f"{message.get('text') or ''}{message.get('stash') or ''}"
                    if partial.strip():
                        outer._last_partial_at = time.time()
                        outer._event_queue.put({
                            "type": "partial",
                            "text": partial.strip(),
                        })
                elif evt == "conversation.item.input_audio_transcription.completed":
                    raw = (message.get("transcript") or "").strip()
                    text, tagged_emotion = _strip_emotion_tags(raw)
                    emotion = message.get("emotion") or tagged_emotion
                    voice = _voice_from_emotion(emotion)
                    if text:
                        outer._last_final_text_at = time.time()
                    outer._event_queue.put({
                        "type": "final",
                        "text": text,
                        "voice": voice.model_dump() if voice else None,
                    })
                elif evt == "error":
                    outer._event_queue.put({
                        "type": "error",
                        "message": message.get("message") or str(message),
                    })

        dashscope.api_key = api_key
        callback = _QwenAsrStreamCallback()
        conversation = OmniRealtimeConversation(
            model=model,
            callback=callback,
            api_key=api_key,
            url=ws_url,
            headers={"OpenAI-Beta": "realtime=v1"},
        )
        conversation.connect()
        conversation.update_session(
            output_modalities=[MultiModality.TEXT],
            enable_input_audio_transcription=True,
            enable_turn_detection=self.turn_detection,
            transcription_params=TranscriptionParams(
                language=self.language,
                sample_rate=self.sample_rate,
                input_audio_format="pcm",
            ),
        )
        time.sleep(float(self._stt_cfg.get("connect_delay_sec", 0.1)))
        self._conversation = conversation
        self._connected = True
        self._ready_at = time.time()
        idle_sec = self.conversation_idle_sec()
        self._event_queue.put({
            "type": "meta",
            "sample_rate": self.sample_rate,
            "language": self.language,
            "turn_detection": self.turn_detection,
            "silence_commit_ms": stt_silence_commit_ms(),
            "conversation_idle_sec": idle_sec,
            "encoding": "pcm16",
        })
        self._event_queue.put({"type": "ready"})
        log.info(
            "[speech] STT stream session started model=%s turn_detection=%s",
            model, self.turn_detection,
        )

    def append_audio(self, pcm: bytes) -> None:
        if self._closed or not self._connected or not pcm:
            return
        if self._conversation is None:
            raise RuntimeError("STT 会话未启动")
        self._conversation.append_audio(
            base64.b64encode(pcm).decode("ascii"),
        )

    def commit(self) -> None:
        if self._closed or not self._connected or self._conversation is None:
            return
        self._conversation.commit()

    def get_event(self, timeout: float = 0.25) -> Optional[dict]:
        try:
            return self._event_queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        conv = self._conversation
        self._conversation = None
        self._connected = False
        if conv is not None:
            try:
                conv.end_session(timeout=int(self._stt_cfg.get("timeout", 30)))
            except Exception:
                pass
            try:
                conv.close()
            except Exception:
                pass
        log.info("[speech] STT stream session closed")


def transcribe(audio: AudioPayload) -> SttResult:
    if not is_enabled():
        raise RuntimeError("语音功能未启用 (speech.enabled=false)")
    wav_bytes = _decode_audio(audio)
    return _transcribe_dashscope(wav_bytes)


def _build_instruct(voice: Optional[VoiceProsody]) -> str:
    """生成 CosyVoice Instruct 文本，格式见音色列表文档。"""
    v = voice or VoiceProsody()
    emotion = _TONE_TO_COSYVOICE_EMOTION.get(v.tone or "平静", "neutral")
    return f"你说话的情感是{emotion}。"


def _sanitize_tts_text(text: str) -> str:
    """去掉不宜朗读的符号。"""
    t = text.strip()
    if not t:
        return ""
    t = re.sub(r"```[\s\S]*?```", "", t)
    t = re.sub(r"`([^`]+)`", r"\1", t)
    t = re.sub(r"[\U00010000-\U0010ffff]", "", t)
    t = re.sub(r"\s+", " ", t).strip()
    if len(t) > 800:
        t = t[:800]
    return t


def _tts_rate_pitch(voice: Optional[VoiceProsody]) -> Tuple[float, float]:
    rate_map = {"慢": 0.85, "正常": 1.0, "快": 1.15, "极快": 1.3}
    pitch_map = {"下沉": 0.9, "平稳": 1.0, "上扬": 1.1, "起伏大": 1.15}
    spd = (voice.speed if voice else None) or "正常"
    pit = (voice.intonation if voice else None) or "平稳"
    return rate_map.get(spd, 1.0), pitch_map.get(pit, 1.0)


def _configure_dashscope_ws() -> None:
    import dashscope

    dashscope.api_key = _dashscope_api_key()
    ws_url = (SPEECH_CFG.get("tts", {}).get("base_url") or "").strip()
    if ws_url:
        dashscope.base_websocket_api_url = ws_url
    else:
        dashscope.base_websocket_api_url = DASHSCOPE_TTS_WS_URL


def _resolve_audio_format(fmt: str, sample_rate: int):
    from dashscope.audio.tts_v2 import AudioFormat

    fmt = (fmt or "mp3").lower()
    candidates = [
        f"{fmt.upper()}_{sample_rate}HZ_MONO_256KBPS",
        f"{fmt.upper()}_{sample_rate}HZ_MONO_16BIT",
        f"{fmt.upper()}_{sample_rate}HZ_MONO",
    ]
    for name in candidates:
        value = getattr(AudioFormat, name, None)
        if value is not None:
            return value
    if fmt == "pcm":
        return AudioFormat.PCM_22050HZ_MONO_16BIT
    return AudioFormat.MP3_22050HZ_MONO_256KBPS


def _tts_wait_limits(text: str, tts_cfg: dict) -> Tuple[float, float]:
    """按文本长度计算 TTS 完成等待：(空闲超时, 总时长上限)。"""
    base = float(tts_cfg.get("timeout", 30))
    per_char = float(tts_cfg.get("timeout_per_char", 0.2))
    idle = float(tts_cfg.get("idle_timeout", base))
    max_total = base + len(text) * per_char
    cap = float(tts_cfg.get("max_timeout", 300))
    return idle, min(max_total, cap)


class _TtsStreamCallback:
    """CosyVoice WebSocket 单向流式回调：收集音频分片。"""

    def __init__(self, chunk_queue: Optional[queue.Queue] = None) -> None:
        from dashscope.audio.tts_v2 import ResultCallback

        self._chunk_queue = chunk_queue
        self.chunks: list[bytes] = []
        self.error: Optional[str] = None
        self._done = threading.Event()
        self._first_packet_ms: Optional[int] = None
        self._last_activity = time.time()
        self._ResultCallback = ResultCallback

    def build(self):
        outer = self

        class _Cb(self._ResultCallback):
            def on_data(self, data: bytes) -> None:
                if not data:
                    return
                outer._last_activity = time.time()
                outer.chunks.append(data)
                if outer._chunk_queue is not None:
                    outer._chunk_queue.put(data)

            def on_complete(self) -> None:
                outer._last_activity = time.time()
                if outer._chunk_queue is not None:
                    outer._chunk_queue.put(None)
                outer._done.set()

            def on_error(self, message: str) -> None:
                outer._last_activity = time.time()
                outer.error = message or "TTS 合成失败"
                if outer._chunk_queue is not None:
                    outer._chunk_queue.put(None)
                outer._done.set()

            def on_close(self) -> None:
                outer._last_activity = time.time()
                if not outer._done.is_set():
                    if outer._chunk_queue is not None:
                        outer._chunk_queue.put(None)
                    outer._done.set()

        return _Cb()

    def wait(self, idle_timeout: float, max_timeout: Optional[float] = None) -> None:
        """空闲超时：连续无分片/完成信号则失败；总时长上限兜底长文本。"""
        self._last_activity = time.time()
        started = self._last_activity
        while not self._done.is_set():
            now = time.time()
            if max_timeout is not None and now - started >= max_timeout:
                raise RuntimeError("TTS 合成超时")
            if now - self._last_activity >= idle_timeout:
                raise RuntimeError("TTS 合成超时")
            self._done.wait(timeout=0.5)

    def set_first_packet_ms(self, value: Optional[int]) -> None:
        self._first_packet_ms = value

    @property
    def first_packet_ms(self) -> Optional[int]:
        return self._first_packet_ms


def _tts_instruction(
    timbre: str,
    voice: Optional[VoiceProsody],
) -> Optional[str]:
    if (
        timbre in TTS_INSTRUCT_VOICES
        and voice
        and (voice.tone or voice.intonation or voice.speed)
    ):
        return _build_instruct(voice)
    return None


def _run_tts_ws(
    text: str,
    voice: Optional[VoiceProsody],
    voice_id: Optional[str],
    *,
    output_format: str,
    sample_rate: int,
    chunk_queue: Optional[queue.Queue] = None,
) -> Tuple[list[bytes], Optional[int]]:
    from dashscope.audio.tts_v2 import SpeechSynthesizer

    tts_cfg = SPEECH_CFG.get("tts", {})
    api_key = _dashscope_api_key()
    if not api_key:
        raise RuntimeError("DashScope TTS 未配置 api_key（或环境变量 DASHSCOPE_API_KEY）")

    model = tts_cfg.get("model", "cosyvoice-v3-flash")
    idle_timeout, max_timeout = _tts_wait_limits(text, tts_cfg)
    max_retries = int(tts_cfg.get("max_retries", 3))
    retry_delay = float(tts_cfg.get("retry_delay_sec", 1.0))
    vid = resolve_voice_id(voice_id)
    timbre = _dashscope_voice(voice_id)
    rate, pitch = _tts_rate_pitch(voice)
    audio_format = _resolve_audio_format(output_format, sample_rate)
    instruction = _tts_instruction(timbre, voice)

    extra: dict = {}
    if instruction:
        extra["instruction"] = instruction

    last_err: Optional[Exception] = None
    for attempt in range(max_retries):
        _configure_dashscope_ws()
        callback = _TtsStreamCallback(chunk_queue=chunk_queue)
        synth_kwargs: dict = {
            "model": model,
            "voice": timbre,
            "format": audio_format,
            "speech_rate": rate,
            "pitch_rate": pitch,
            "callback": callback.build(),
        }
        if extra:
            synth_kwargs["additional_params"] = extra
        synthesizer = SpeechSynthesizer(**synth_kwargs)
        t0 = time.time()
        try:
            log.info(
                "[speech] TTS ws voice_id=%s len=%d model=%s fmt=%s idle=%.0fs max=%.0fs",
                vid, len(text), model, output_format, idle_timeout, max_timeout,
            )
            synthesizer.call(text)
            callback.wait(idle_timeout, max_timeout)
            if callback.error:
                raise RuntimeError(callback.error)
            first_ms = synthesizer.get_first_package_delay()
            callback.set_first_packet_ms(first_ms)
            total_ms = (time.time() - t0) * 1000
            nbytes = sum(len(c) for c in callback.chunks)
            log.info(
                "[speech] TTS ws ok in %.0fms first=%sms bytes=%d",
                total_ms, first_ms, nbytes,
            )
            try:
                synthesizer.get_duplex_api().close(1000, "bye")
            except Exception:
                pass
            return callback.chunks, first_ms
        except Exception as e:
            last_err = e
            log.warning(
                "[speech] TTS ws 失败 attempt=%d/%d: %s",
                attempt + 1, max_retries, e,
            )
            if attempt + 1 < max_retries:
                time.sleep(retry_delay)
            try:
                synthesizer.get_duplex_api().close(1000, "bye")
            except Exception:
                pass

    raise RuntimeError(f"TTS 合成失败: {last_err}") from last_err


def _synthesize_dashscope(
    text: str,
    voice: Optional[VoiceProsody],
    sample_rate: int,
    voice_id: Optional[str] = None,
) -> bytes:
    fmt = tts_output_format()
    with _tts_lock:
        chunks, _ = _run_tts_ws(
            text, voice, voice_id,
            output_format=fmt,
            sample_rate=sample_rate,
        )
    if not chunks:
        return b""
    return b"".join(chunks)


def synthesize(
    text: str,
    voice: Optional[VoiceProsody] = None,
    voice_id: Optional[str] = None,
) -> Tuple[bytes, int]:
    if not is_enabled():
        raise RuntimeError("语音功能未启用 (speech.enabled=false)")
    text = _sanitize_tts_text(text)
    if not text:
        return b"", tts_sample_rate()

    sample_rate = tts_sample_rate()
    return _synthesize_dashscope(text, voice, sample_rate, voice_id), sample_rate


def iter_tts_chunks(
    text: str,
    voice: Optional[VoiceProsody] = None,
    voice_id: Optional[str] = None,
    *,
    metrics: Optional[dict] = None,
) -> Iterator[bytes]:
    """WebSocket 流式 TTS，逐块 yield 音频字节（格式见 speech.tts.stream_format）。"""
    if not is_enabled():
        raise RuntimeError("语音功能未启用 (speech.enabled=false)")
    text = _sanitize_tts_text(text)
    if not text:
        return

    fmt = tts_stream_format()
    sample_rate = tts_sample_rate()
    chunk_queue: queue.Queue = queue.Queue()
    state: dict = {"error": None}

    def _worker() -> None:
        try:
            with _tts_lock:
                _, first_ms = _run_tts_ws(
                    text, voice, voice_id,
                    output_format=fmt,
                    sample_rate=sample_rate,
                    chunk_queue=chunk_queue,
                )
            if metrics is not None:
                metrics["first_packet_ms"] = first_ms
        except Exception as e:
            state["error"] = e
            if metrics is not None:
                metrics["error"] = str(e)
        finally:
            chunk_queue.put(None)

    threading.Thread(target=_worker, daemon=True).start()
    while True:
        item = chunk_queue.get()
        if item is None:
            break
        yield item

    if state.get("error"):
        raise state["error"]


def synthesize_payload(
    text: str,
    voice: Optional[VoiceProsody] = None,
    voice_id: Optional[str] = None,
) -> AudioPayload:
    audio_bytes, sr = synthesize(text, voice, voice_id=voice_id)
    if not audio_bytes:
        raise RuntimeError("TTS 合成结果为空")
    fmt = tts_output_format()
    if fmt == "pcm":
        fmt = "pcm"
    elif audio_bytes[:3] == b"ID3" or (len(audio_bytes) > 2 and audio_bytes[0] == 0xFF):
        fmt = "mp3"
    elif audio_bytes[:4] == b"RIFF":
        fmt = "wav"
    return AudioPayload(
        format=fmt, encoding="base64", sample_rate=sr,
        data=base64.b64encode(audio_bytes).decode("ascii"),
    )
