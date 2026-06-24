"""LLM 客户端：OpenAI 兼容协议（适配 OpenAI / DeepSeek / Moonshot / Ollama 等）。"""
from __future__ import annotations
import json
import logging
import re
import time

import httpx
from .config import CHAT_CFG, LLM_CFG

log = logging.getLogger("pophie.llm")


class LLMError(Exception):
    pass


def _apply_provider_extras(payload: dict) -> None:
    """合并厂商扩展参数（如 DeepSeek thinking 模式）。"""
    thinking = LLM_CFG.get("thinking")
    if isinstance(thinking, dict) and thinking.get("type"):
        payload["thinking"] = thinking


def _chat_max_tokens() -> int | None:
    raw = CHAT_CFG.get("max_tokens", LLM_CFG.get("max_tokens"))
    if raw is None:
        return None
    try:
        val = int(raw)
    except (TypeError, ValueError):
        return None
    return val if val > 0 else None


def _llm_api_key() -> str:
    return (LLM_CFG.get("api_key") or "").strip()


def _require_llm_api_key() -> str:
    key = _llm_api_key()
    if not key:
        raise LLMError(
            "LLM API Key 未配置，请在 config.yaml 填写 llm.api_key "
            "或设置环境变量 LLM_API_KEY"
        )
    return key


def chat(messages, *, temperature=None, response_format=None,
         user_facing: bool = True) -> str:
    """同步调用 chat completions，返回 assistant 文本内容。"""
    url = LLM_CFG["base_url"].rstrip("/") + "/chat/completions"
    headers = {
        "Authorization": f"Bearer {_require_llm_api_key()}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": LLM_CFG["model"],
        "messages": messages,
        "temperature": LLM_CFG.get("temperature", 0.7) if temperature is None else temperature,
    }
    if response_format:
        payload["response_format"] = response_format
    max_tokens = _chat_max_tokens()
    if max_tokens is not None:
        payload["max_tokens"] = max_tokens
    _apply_provider_extras(payload)

    thinking_type = (LLM_CFG.get("thinking") or {}).get("type", "enabled")
    total_chars = sum(len(m.get("content", "")) for m in messages)
    log.info("[llm] -> %s model=%s msgs=%d chars=%d temp=%s json=%s thinking=%s",
             url, payload["model"], len(messages), total_chars,
             payload["temperature"], bool(response_format), thinking_type)
    for i, m in enumerate(messages):
        c = m.get("content", "") or ""
        preview = c if len(c) <= 200 else c[:200] + f"... <{len(c)} chars>"
        log.debug("  msg[%d] %s: %s", i, m.get("role"), preview)

    t0 = time.time()
    try:
        with httpx.Client(timeout=LLM_CFG.get("timeout", 60)) as client:
            resp = client.post(url, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
            choice = data["choices"][0]
            msg = choice.get("message") or {}
            content = (msg.get("content") or "").strip()
            reasoning = (msg.get("reasoning_content") or "").strip()
            if not content:
                finish = choice.get("finish_reason")
                log.warning("[llm] content 为空 finish_reason=%s msg_keys=%s usage=%s",
                            finish, list(msg.keys()), data.get("usage"))
            if user_facing:
                if not content and reasoning:
                    log.warning("[llm] 模型仅返回 reasoning_content（%d chars），"
                                "不展示给用户", len(reasoning))
                content = content or ""
            elif not content:
                content = reasoning or choice.get("text") or ""
            usage = data.get("usage", {})
            log.info("[llm] <- ok in %.0fms usage=%s reply_chars=%d",
                     (time.time() - t0) * 1000, usage, len(content or ""))
            return content
    except Exception as e:
        log.error("[llm] <- 失败 in %.0fms: %s", (time.time() - t0) * 1000, e)
        raise LLMError(f"LLM 调用失败：{e}") from e


def _parse_json_text(text: str) -> dict:
    """从模型输出中尽可能解析 JSON。"""
    text = (text or "").strip()
    if not text:
        raise ValueError("empty")
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\n?", "", text)
        text = re.sub(r"\n?```\s*$", "", text).strip()
    try:
        return json.loads(text)
    except Exception:
        pass
    m = re.search(r"\{[\s\S]*\}", text)
    if m:
        blob = m.group(0)
        try:
            return json.loads(blob)
        except Exception:
            # 尝试补全被截断的 JSON
            if not blob.rstrip().endswith("}"):
                for suffix in ('"}', '"}', 'null}', 'null}}', '}}'):
                    try:
                        return json.loads(blob + suffix)
                    except Exception:
                        continue
    raise ValueError("invalid json")


def ensure_dialogue_text(text: str) -> str:
    """丢弃内心独白式回复（不再二次调用 LLM 改写）。"""
    from .schemas import looks_like_internal_monologue

    t = (text or "").strip()
    if not t:
        return t
    if looks_like_internal_monologue(t):
        log.warning("[llm] 内心独白式回复已丢弃 (%d chars)", len(t))
        return ""
    return t


def chat_json(messages, *, temperature=None, user_facing: bool = True) -> dict:
    """单次 LLM 调用，解析 JSON；若模型回纯文本则本地包装为结构化结果。"""
    if temperature is None:
        temperature = LLM_CFG.get("chat_temperature", 0.3)
    response_format = (
        {"type": "json_object"} if LLM_CFG.get("json_mode") else None
    )
    text = chat(
        messages,
        temperature=temperature,
        response_format=response_format,
        user_facing=user_facing,
    ).strip()
    if not text:
        raise LLMError("模型返回空内容")

    try:
        data = _parse_json_text(text)
        if user_facing and isinstance(data.get("text"), str):
            data["text"] = ensure_dialogue_text(data["text"])
        return data
    except Exception:
        pass

    if not text.startswith("{"):
        plain = ensure_dialogue_text(text) if user_facing else text
        if plain:
            log.info("[llm] 模型返回纯文本，本地包装为结构化回复 (%d chars)", len(plain))
            return {
                "text": plain,
                "facial_expression": "neutral",
                "voice": None,
                "gesture": None,
                "posture": None,
            }

    preview = text[:200]
    log.error("[llm] 无法解析模型输出：%r", preview)
    raise LLMError(f"模型输出无法解析：{preview}")


def iter_chat_stream(messages, *, temperature=None, user_facing: bool = True):
    """流式调用 chat completions，逐块 yield content delta。"""
    url = LLM_CFG["base_url"].rstrip("/") + "/chat/completions"
    headers = {
        "Authorization": f"Bearer {_require_llm_api_key()}",
        "Content-Type": "application/json",
    }
    if temperature is None:
        temperature = LLM_CFG.get("chat_temperature", 0.3)
    payload = {
        "model": LLM_CFG["model"],
        "messages": messages,
        "temperature": temperature,
        "stream": True,
    }
    max_tokens = _chat_max_tokens()
    if max_tokens is not None:
        payload["max_tokens"] = max_tokens
    _apply_provider_extras(payload)

    log.info("[llm] -> stream %s model=%s msgs=%d temp=%s",
             url, payload["model"], len(messages), temperature)
    t0 = time.time()
    full_chars = 0
    try:
        with httpx.Client(timeout=LLM_CFG.get("timeout", 60)) as client:
            with client.stream("POST", url, json=payload, headers=headers) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines():
                    if not line or not line.startswith("data: "):
                        continue
                    data = line[6:].strip()
                    if data == "[DONE]":
                        break
                    try:
                        chunk = json.loads(data)
                    except json.JSONDecodeError:
                        continue
                    choice = (chunk.get("choices") or [{}])[0]
                    delta = choice.get("delta") or {}
                    content = delta.get("content") or ""
                    if content:
                        full_chars += len(content)
                        yield content
        log.info("[llm] <- stream ok in %.0fms reply_chars=%d",
                 (time.time() - t0) * 1000, full_chars)
    except Exception as e:
        log.error("[llm] <- stream 失败 in %.0fms: %s", (time.time() - t0) * 1000, e)
        raise LLMError(f"LLM 流式调用失败：{e}") from e


def parse_chat_json(text: str, *, user_facing: bool = True) -> dict:
    """解析 LLM 输出的 JSON 结构化回复。"""
    text = (text or "").strip()
    if not text:
        raise LLMError("模型返回空内容")
    try:
        data = _parse_json_text(text)
        if user_facing and isinstance(data.get("text"), str):
            data["text"] = ensure_dialogue_text(data["text"])
        return data
    except Exception:
        pass
    if not text.startswith("{"):
        plain = ensure_dialogue_text(text) if user_facing else text
        if plain:
            return {
                "text": plain,
                "facial_expression": "neutral",
                "voice": None,
                "gesture": None,
                "posture": None,
            }
    preview = text[:200]
    raise LLMError(f"模型输出无法解析：{preview}")


def chat_json_from_stream(messages, *, temperature=None, user_facing: bool = True) -> dict:
    """流式收集完整回复后解析 JSON。"""
    text = "".join(
        iter_chat_stream(messages, temperature=temperature, user_facing=user_facing)
    ).strip()
    return parse_chat_json(text, user_facing=user_facing)
