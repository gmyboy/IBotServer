#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

PYPI="https://pypi.org/simple"

if [[ ! -f config.yaml ]]; then
  if [[ -f config.example.yaml ]]; then
    cp config.example.yaml config.yaml
    echo "[setup] created config.yaml from config.example.yaml"
    echo "[setup] edit config.yaml or copy .env.example to .env and set API keys"
  else
    echo "[error] config.yaml not found" >&2
    exit 1
  fi
fi

pick_python() {
  if [[ -n "${PYTHON:-}" ]]; then
    echo "$PYTHON"
    return
  fi
  local cmd
  for cmd in python3.13 python3.12 python3.11 python3.10; do
    if command -v "$cmd" >/dev/null 2>&1; then
      echo "$cmd"
      return
    fi
  done
  echo "[error] Python 3.10+ required. Install with: brew install python@3.12" >&2
  exit 1
}

PYTHON="$(pick_python)"
echo "[info] using $PYTHON ($("$PYTHON" --version 2>&1))"

if [[ -d .venv && ! -f .venv/bin/activate ]]; then
  echo "[setup] removing broken .venv..."
  rm -rf .venv
fi

if [[ -f .venv/bin/activate ]] && ! .venv/bin/python -c "import fastapi" >/dev/null 2>&1; then
  echo "[setup] venv incomplete, recreating..."
  rm -rf .venv
fi

if [[ ! -f .venv/bin/activate ]]; then
  echo "[setup] creating venv..."
  "$PYTHON" -m venv .venv
fi

# shellcheck disable=SC1091
source .venv/bin/activate

echo "[setup] upgrading pip..."
python -m pip install --upgrade pip -i "$PYPI"

echo "[setup] installing requirements..."
pip install -r requirements.txt -i "$PYPI"

if python scripts/check_speech_deps.py; then
  echo "[info] speech.enabled=false, text-only mode"
else
  if ! python -c "import dashscope" >/dev/null 2>&1; then
    echo "[setup] installing speech dependencies..."
    pip install dashscope 'cryptography>=42,<44' -i "$PYPI"
  fi
  if ! python -c "import dashscope" >/dev/null 2>&1; then
    echo "[error] dashscope import failed; speech features will be unavailable" >&2
    echo "        try: pip install --force-reinstall 'cryptography>=42,<44' dashscope" >&2
  else
    echo "[info] speech dependencies ok"
  fi
fi

read -r PORT HOST < <(
  python -c "
import yaml
from pathlib import Path

cfg = yaml.safe_load(Path('config.yaml').read_text(encoding='utf-8'))
server = cfg.get('server', {})
print(server.get('port', 8000), server.get('host', '0.0.0.0'))
"
)

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  PID="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t | head -1)"
  echo "[error] port $PORT is already in use by PID $PID"
  echo "        stop it with: kill $PID"
  echo "        or change server.port in config.yaml"
  exit 1
fi

echo "[info] server listening on $HOST:$PORT"
python -m backend.main
