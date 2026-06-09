@echo off
chcp 65001 >nul
cd /d %~dp0

if not exist config.yaml (
  if exist config.example.yaml (
    copy config.example.yaml config.yaml >nul
    echo [setup] created config.yaml from config.example.yaml
    echo [setup] edit config.yaml or copy .env.example to .env and set API keys
  ) else (
    echo [error] config.yaml not found
    pause
    exit /b 1
  )
)

set PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
set PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn

if not exist .venv (
  echo [setup] creating venv...
  python -m venv .venv
)
call .venv\Scripts\activate.bat

python -m pip install --upgrade pip -q
pip install -q -r requirements.txt

python scripts\check_speech_deps.py
if errorlevel 1 (
  pip show dashscope >nul 2>&1
  if errorlevel 1 (
    echo [warn] speech.enabled=true but dashscope not installed
    echo [info] installing dashscope from official PyPI...
    pip install dashscope -i https://pypi.org/simple -q
    if errorlevel 1 (
      echo [warn] dashscope install failed, server will start in text-only mode
    )
  )
) else (
  echo [info] speech.enabled=false, text-only mode
)

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8000" ^| findstr "LISTENING"') do (
  echo [error] port 8000 is already in use by PID %%a
  echo         stop it with: taskkill /PID %%a /F
  echo         or change server.port in config.yaml
  pause
  exit /b 1
)

echo [info] server listening on 0.0.0.0:8000
python -m backend.main
