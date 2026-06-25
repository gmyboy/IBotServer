"""配置加载：从 config.yaml 读取 LLM/记忆/服务器配置；密钥可由环境变量覆盖。"""
from __future__ import annotations
import copy
import os
from pathlib import Path
from typing import Any
import yaml

ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = ROOT / "config.yaml"
CONFIG_EXAMPLE_PATH = ROOT / "config.example.yaml"
ENV_PATH = ROOT / ".env"
SECRET_MASK = "***"


def _get_nested(data: dict, path: list[str], default: Any = None) -> Any:
    cur: Any = data
    for key in path:
        if not isinstance(cur, dict):
            return default
        cur = cur.get(key)
        if cur is None:
            return default
    return cur


def _set_nested(data: dict, path: list[str], value: Any) -> None:
    cur = data
    for key in path[:-1]:
        nxt = cur.get(key)
        if not isinstance(nxt, dict):
            nxt = {}
            cur[key] = nxt
        cur = nxt
    cur[path[-1]] = value


def _load_dotenv() -> None:
    if not ENV_PATH.is_file():
        return
    for raw in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip("'\"")
        if key and key not in os.environ:
            os.environ[key] = value


def _apply_env_secrets(cfg: dict) -> None:
    llm_key = (os.environ.get("LLM_API_KEY") or "").strip()
    if llm_key:
        _set_nested(cfg, ["llm", "api_key"], llm_key)
    dashscope_key = (os.environ.get("DASHSCOPE_API_KEY") or "").strip()
    if dashscope_key:
        _set_nested(cfg, ["speech", "tts", "api_key"], dashscope_key)
        _set_nested(cfg, ["speech", "stt", "api_key"], dashscope_key)
    admin_token = (os.environ.get("ADMIN_TOKEN") or "").strip()
    if admin_token:
        _set_nested(cfg, ["server", "admin_token"], admin_token)


def load_config() -> dict:
    _load_dotenv()
    if not CONFIG_PATH.is_file():
        hint = (
            f"未找到 {CONFIG_PATH.name}。"
            f"请复制 {CONFIG_EXAMPLE_PATH.name} 为 config.yaml，"
            "或在项目根目录创建 .env 填入密钥。"
        )
        raise FileNotFoundError(hint)
    with CONFIG_PATH.open("r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f) or {}
    _apply_env_secrets(cfg)
    return cfg


CONFIG = load_config()
LLM_CFG = CONFIG["llm"]
MEM_CFG = CONFIG["memory"]
SERVER_CFG = CONFIG["server"]
SPEECH_CFG = CONFIG.get("speech", {"enabled": False})
CHAT_CFG = CONFIG.get("chat", {})
DB_PATH = ROOT / "memory.db"

ADMIN_CONFIG_SCHEMA: list[dict] = [
    {
        "section": "llm",
        "title": "LLM 模型",
        "fields": [
            {"path": ["base_url"], "label": "API 地址", "type": "text"},
            {"path": ["api_key"], "label": "API Key", "type": "secret"},
            {"path": ["model"], "label": "模型", "type": "text"},
            {"path": ["timeout"], "label": "超时（秒）", "type": "number"},
            {"path": ["temperature"], "label": "温度", "type": "number", "step": 0.1},
            {"path": ["chat_temperature"], "label": "JSON 对话温度", "type": "number", "step": 0.1},
            {"path": ["json_mode"], "label": "JSON 模式", "type": "boolean"},
            {"path": ["thinking", "type"], "label": "思考模式", "type": "select",
             "options": ["disabled", "enabled"]},
        ],
    },
    {
        "section": "memory",
        "title": "记忆系统",
        "fields": [
            {"path": ["l2_session_cap"], "label": "L2 会话上限", "type": "number"},
            {"path": ["l2_to_l3_importance"], "label": "L2→L3 阈值", "type": "number", "step": 0.01},
            {"path": ["l3_to_l4_importance"], "label": "L3→L4 阈值", "type": "number", "step": 0.01},
            {"path": ["l3_to_l4_reinforce_count"], "label": "L3 巩固次数", "type": "number"},
            {"path": ["direct_l4_importance"], "label": "直跃 L4 阈值", "type": "number", "step": 0.01},
            {"path": ["importance_weight"], "label": "重要性权重", "type": "number", "step": 0.1},
            {"path": ["emotion_weight"], "label": "情感权重", "type": "number", "step": 0.1},
            {"path": ["emotion_direct_l4_intensity"], "label": "情感直跃 L4", "type": "number", "step": 0.01},
            {"path": ["recall_emotion_weight"], "label": "召回情感权重", "type": "number", "step": 0.1},
        ],
    },
    {
        "section": "server",
        "title": "服务器",
        "fields": [
            {"path": ["host"], "label": "监听地址", "type": "text",
             "hint": "修改后需重启服务"},
            {"path": ["port"], "label": "端口", "type": "number",
             "hint": "修改后需重启服务"},
            {"path": ["default_robot"], "label": "默认 robot_id", "type": "text"},
            {"path": ["admin_token"], "label": "Admin Token", "type": "secret"},
        ],
    },
    {
        "section": "chat",
        "title": "聊天",
        "fields": [
            {"path": ["defer_side_tasks"], "label": "后台处理记忆/提醒", "type": "boolean"},
            {"path": ["inline_tts"], "label": "内联 TTS", "type": "boolean"},
            {"path": ["max_tokens"], "label": "回复 token 上限", "type": "number",
             "hint": "限制回复长度以加快 LLM 与 TTS"},
            {"path": ["recent_turns"], "label": "近期对话轮数", "type": "number"},
            {"path": ["recall_top_k"], "label": "记忆召回条数", "type": "number"},
        ],
    },
    {
        "section": "speech",
        "title": "语音",
        "fields": [
            {"path": ["enabled"], "label": "启用语音", "type": "boolean"},
            {"path": ["stt", "model"], "label": "STT 模型", "type": "text"},
            {"path": ["stt", "language"], "label": "STT 语种", "type": "text"},
            {"path": ["stt", "sample_rate"], "label": "STT 采样率", "type": "number"},
            {"path": ["stt", "turn_detection"], "label": "STT 流式自动断句", "type": "boolean"},
            {"path": ["stt", "silence_commit_ms"], "label": "STT 端侧静音 commit（毫秒）", "type": "number"},
            {"path": ["stt", "stream_conversation_idle_sec"], "label": "STT 对话空闲超时（秒）", "type": "number",
             "hint": "无 partial/有效 final 后服务端主动关闭 WS 会话"},
            {"path": ["stt", "timeout"], "label": "STT 超时（秒）", "type": "number"},
            {"path": ["stt", "max_retries"], "label": "STT 重试次数", "type": "number"},
            {"path": ["stt", "retry_delay_sec"], "label": "STT 重试间隔（秒）", "type": "number", "step": 0.1},
            {"path": ["tts", "api_key"], "label": "DashScope API Key", "type": "secret"},
            {"path": ["tts", "base_url"], "label": "TTS WebSocket 地址", "type": "text"},
            {"path": ["tts", "model"], "label": "TTS 模型", "type": "text"},
            {"path": ["tts", "format"], "label": "批量音频格式（/api/tts）", "type": "text"},
            {"path": ["tts", "stream_format"], "label": "流式音频格式（/api/tts/stream）", "type": "text"},
            {"path": ["tts", "timeout"], "label": "TTS 超时（秒）", "type": "number"},
            {"path": ["tts", "default_voice"], "label": "默认音色", "type": "voice_select",
             "hint": "切换后立即生效，无需重启"},
            {"path": ["tts", "sample_rate"], "label": "采样率", "type": "number"},
            {"path": ["tts", "max_retries"], "label": "TTS 重试次数", "type": "number"},
            {"path": ["tts", "retry_delay_sec"], "label": "重试间隔（秒）", "type": "number", "step": 0.1},
        ],
    },
]


def _mask_secrets(config: dict) -> dict:
    masked = copy.deepcopy(config)
    api_key = _get_nested(masked, ["llm", "api_key"])
    if api_key:
        _set_nested(masked, ["llm", "api_key"], SECRET_MASK)
    token = _get_nested(masked, ["server", "admin_token"])
    if token:
        _set_nested(masked, ["server", "admin_token"], SECRET_MASK)
    tts_key = _get_nested(masked, ["speech", "tts", "api_key"])
    if tts_key:
        _set_nested(masked, ["speech", "tts", "api_key"], SECRET_MASK)
    return masked


def _is_masked(value: Any) -> bool:
    return value in (None, "", SECRET_MASK)


def get_config_for_admin() -> dict:
    return _mask_secrets(load_config())


def _deep_merge(base: dict, patch: dict) -> dict:
    merged = copy.deepcopy(base)
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def _preserve_secrets(new_cfg: dict, old_cfg: dict) -> None:
    new_key = _get_nested(new_cfg, ["llm", "api_key"])
    if _is_masked(new_key):
        old_key = _get_nested(old_cfg, ["llm", "api_key"])
        if old_key:
            _set_nested(new_cfg, ["llm", "api_key"], old_key)
    new_token = _get_nested(new_cfg, ["server", "admin_token"])
    if _is_masked(new_token):
        old_token = _get_nested(old_cfg, ["server", "admin_token"])
        if old_token:
            _set_nested(new_cfg, ["server", "admin_token"], old_token)
    new_tts_key = _get_nested(new_cfg, ["speech", "tts", "api_key"])
    if _is_masked(new_tts_key):
        old_tts_key = _get_nested(old_cfg, ["speech", "tts", "api_key"])
        if old_tts_key:
            _set_nested(new_cfg, ["speech", "tts", "api_key"], old_tts_key)


def save_config(data: dict) -> dict:
    """写入 config.yaml 并热更新进程内配置。"""
    current = load_config()
    merged = _deep_merge(current, data)
    _preserve_secrets(merged, current)
    with CONFIG_PATH.open("w", encoding="utf-8") as f:
        yaml.safe_dump(
            merged, f, allow_unicode=True, sort_keys=False, default_flow_style=False,
        )
    reload_runtime_config()
    return _mask_secrets(merged)


def reload_runtime_config() -> None:
    """重新加载模块级配置引用。"""
    global CONFIG, LLM_CFG, MEM_CFG, SERVER_CFG, SPEECH_CFG, CHAT_CFG
    CONFIG = load_config()
    LLM_CFG = CONFIG["llm"]
    MEM_CFG = CONFIG["memory"]
    SERVER_CFG = CONFIG["server"]
    SPEECH_CFG = CONFIG.get("speech", {"enabled": False})
    CHAT_CFG = CONFIG.get("chat", {})


def config_secret_issues(cfg: dict | None = None) -> list[str]:
    """返回未配置密钥的提示列表（空列表表示关键项已就绪）。"""
    cfg = cfg or CONFIG
    issues: list[str] = []
    if not (_get_nested(cfg, ["llm", "api_key"]) or "").strip():
        issues.append("llm.api_key 未配置（config.yaml 或环境变量 LLM_API_KEY）")
    if _get_nested(cfg, ["speech", "enabled"]):
        tts_key = (_get_nested(cfg, ["speech", "tts", "api_key"]) or "").strip()
        stt_key = (_get_nested(cfg, ["speech", "stt", "api_key"]) or "").strip()
        if not (tts_key or stt_key):
            issues.append(
                "speech 已启用但 DashScope Key 未配置"
                "（speech.tts.api_key 或环境变量 DASHSCOPE_API_KEY）"
            )
    return issues
