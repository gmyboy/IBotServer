#!/usr/bin/env bash
# 将 server-java/.env 中的业务密钥写入 DATA_ROOT/config.yaml。
# 映射：LLM_API_KEY → llm.api_key
#       DASHSCOPE_API_KEY → speech.tts.api_key
#       ADMIN_TOKEN → server.admin_token
set -euo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
[[ "$SCRIPT_SOURCE" = /* ]] || SCRIPT_SOURCE="$(pwd)/$SCRIPT_SOURCE"
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_SOURCE")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ENV_FILE="${PROJECT_DIR}/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[ERROR] 未找到 ${ENV_FILE}"
  exit 1
fi

export ENV_FILE
python3 <<'PY'
import os
import re
from pathlib import Path

env_path = Path(os.environ["ENV_FILE"])

def load_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()
    return env

env = load_env(env_path)
data_root = env.get("DATA_ROOT", str(Path.home() / "PophieData")).replace("~", str(Path.home()))
config_path = Path(data_root) / "config.yaml"
if not config_path.is_file():
    print(f"[ERROR] 未找到 {config_path}，请先运行 deploy/macos-deploy.sh 或复制 config.example.yaml")
    raise SystemExit(1)

MAPPINGS = [
    (["llm"], "api_key", "LLM_API_KEY"),
    (["server"], "admin_token", "ADMIN_TOKEN"),
    (["speech", "tts"], "api_key", "DASHSCOPE_API_KEY"),
]

lines = config_path.read_text(encoding="utf-8").splitlines(keepends=True)
path_stack: list[tuple[int, str]] = []
out: list[str] = []
updated: list[str] = []

for line in lines:
    m = re.match(r"^(\s*)([A-Za-z0-9_]+):\s*(.*)$", line.rstrip("\n"))
    if not m:
        out.append(line)
        continue

    indent, key, rest = m.groups()
    level = len(indent) // 2
    while path_stack and path_stack[-1][0] >= level:
        path_stack.pop()
    parent_path = [k for _, k in path_stack]

    for section, field, env_key in MAPPINGS:
        if parent_path == section and key == field:
            val = env.get(env_key, "").strip()
            if val:
                comment = ""
                if "#" in rest:
                    comment = "   " + rest[rest.index("#") :].rstrip()
                line = f"{indent}{key}: {val}{comment}\n"
                updated.append(f"{'.'.join(section)}.{field} ← {env_key}")
            break

    out.append(line)

    if rest.strip() == "":
        path_stack.append((level, key))

config_path.write_text("".join(out), encoding="utf-8")
if updated:
    for item in updated:
        print(f"[INFO] {item}")
    print(f"[INFO] 已写入 {config_path}")
else:
    print("[INFO] .env 中 LLM_API_KEY / DASHSCOPE_API_KEY / ADMIN_TOKEN 均为空，跳过写入")
PY
