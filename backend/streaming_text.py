"""从流式 JSON 回复中提取 text 字段，按句切分供提前 TTS。"""
from __future__ import annotations

import re

_SENTENCE_END = re.compile(r"[。！？!?…]")


class StreamingReplyTextExtractor:
    """跟踪 LLM 流式 JSON 输出，在完整句子就绪时返回可朗读片段。"""

    def __init__(self) -> None:
        self._raw = ""
        self._emitted = 0

    def feed(self, delta: str) -> list[str]:
        self._raw += delta
        text = self._current_text_value()
        if text is None:
            return []
        pending = text[self._emitted :]
        if not pending:
            return []
        chunks: list[str] = []
        while pending:
            m = _SENTENCE_END.search(pending)
            if not m:
                break
            chunk = pending[: m.end()]
            chunks.append(chunk)
            self._emitted += len(chunk)
            pending = pending[m.end() :]
        if self._text_field_complete():
            rem = text[self._emitted :].strip()
            if rem:
                chunks.append(rem)
                self._emitted = len(text)
        return chunks

    def flush(self) -> str | None:
        """文本字段已闭合时，返回尚未朗读的尾部。"""
        text = self._current_text_value()
        if text is None:
            return None
        rem = text[self._emitted :].strip()
        if not rem:
            return None
        self._emitted = len(text)
        return rem

    def _current_text_value(self) -> str | None:
        key = '"text"'
        idx = self._raw.find(key)
        if idx < 0:
            return None
        i = idx + len(key)
        while i < len(self._raw) and self._raw[i] in " \t\n\r":
            i += 1
        if i >= len(self._raw) or self._raw[i] != ":":
            return None
        i += 1
        while i < len(self._raw) and self._raw[i] in " \t\n\r":
            i += 1
        if i >= len(self._raw) or self._raw[i] != '"':
            return None
        i += 1
        chars: list[str] = []
        while i < len(self._raw):
            c = self._raw[i]
            if c == "\\":
                if i + 1 >= len(self._raw):
                    return "".join(chars)
                nc = self._raw[i + 1]
                chars.append(
                    {"n": "\n", "r": "\r", "t": "\t", '"': '"', "\\": "\\"}.get(nc, nc)
                )
                i += 2
            elif c == '"':
                break
            else:
                chars.append(c)
                i += 1
        return "".join(chars)

    def _text_field_complete(self) -> bool:
        key = '"text"'
        idx = self._raw.find(key)
        if idx < 0:
            return False
        i = idx + len(key)
        while i < len(self._raw) and self._raw[i] in " \t\n\r":
            i += 1
        if i >= len(self._raw) or self._raw[i] != ":":
            return False
        i += 1
        while i < len(self._raw) and self._raw[i] in " \t\n\r":
            i += 1
        if i >= len(self._raw) or self._raw[i] != '"':
            return False
        i += 1
        while i < len(self._raw):
            c = self._raw[i]
            if c == "\\":
                if i + 1 >= len(self._raw):
                    return False
                i += 2
            elif c == '"':
                return True
            else:
                i += 1
        return False
