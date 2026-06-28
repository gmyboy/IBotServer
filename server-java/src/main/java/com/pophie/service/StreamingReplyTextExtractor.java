package com.pophie.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从流式 JSON 回复中提取 text 字段，按句切分供提前 TTS。
 * 逐字符等价移植 streaming_text.py 的 StreamingReplyTextExtractor。
 */
public class StreamingReplyTextExtractor {

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?…]");

    private final StringBuilder raw = new StringBuilder();
    private int emitted = 0;

    public List<String> feed(String delta) {
        raw.append(delta);
        String text = currentTextValue();
        if (text == null) return new ArrayList<>();
        String pending = text.substring(emitted);
        if (pending.isEmpty()) return new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        while (!pending.isEmpty()) {
            Matcher m = SENTENCE_END.matcher(pending);
            if (!m.find()) break;
            int end = m.end();
            String chunk = pending.substring(0, end);
            chunks.add(chunk);
            emitted += chunk.length();
            pending = pending.substring(end);
        }
        if (textFieldComplete()) {
            String rem = text.substring(emitted).strip();
            if (!rem.isEmpty()) {
                chunks.add(rem);
                emitted = text.length();
            }
        }
        return chunks;
    }

    /** 文本字段已闭合时，返回尚未朗读的尾部；否则 null。 */
    public String flush() {
        String text = currentTextValue();
        if (text == null) return null;
        String rem = text.substring(emitted).strip();
        if (rem.isEmpty()) return null;
        emitted = text.length();
        return rem;
    }

    private String currentTextValue() {
        String s = raw.toString();
        String key = "\"text\"";
        int idx = s.indexOf(key);
        if (idx < 0) return null;
        int i = idx + key.length();
        while (i < s.length() && isWs(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != ':') return null;
        i++;
        while (i < s.length() && isWs(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != '"') return null;
        i++;
        StringBuilder chars = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 >= s.length()) return chars.toString();
                char nc = s.charAt(i + 1);
                chars.append(unescape(nc));
                i += 2;
            } else if (c == '"') {
                break;
            } else {
                chars.append(c);
                i++;
            }
        }
        return chars.toString();
    }

    private boolean textFieldComplete() {
        String s = raw.toString();
        String key = "\"text\"";
        int idx = s.indexOf(key);
        if (idx < 0) return false;
        int i = idx + key.length();
        while (i < s.length() && isWs(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != ':') return false;
        i++;
        while (i < s.length() && isWs(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != '"') return false;
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 >= s.length()) return false;
                i += 2;
            } else if (c == '"') {
                return true;
            } else {
                i++;
            }
        }
        return false;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static char unescape(char nc) {
        switch (nc) {
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case '"': return '"';
            case '\\': return '\\';
            default: return nc;
        }
    }
}
