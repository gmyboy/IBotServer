<#
.SYNOPSIS
  Windows 本机一键部署 Pophie（Docker Desktop），所有数据落到 -DataRoot（默认 E 盘），不占 C 盘。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File deploy\windows-deploy.ps1
  powershell -ExecutionPolicy Bypass -File deploy\windows-deploy.ps1 -DataRoot "E:\PophieData"
.NOTES
  前置：安装 Docker Desktop。若要让镜像/构建缓存也不在 C 盘，在 Docker Desktop:
  Settings → Resources → Advanced → "Disk image location" 改到 E 盘后 Apply & Restart。
#>
param(
  [string]$DataRoot = "E:\PophieData"
)

$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path $PSScriptRoot -Parent
Set-Location $ProjectDir
Write-Host "[INFO] 项目目录: $ProjectDir"
Write-Host "[INFO] 数据目录(DataRoot): $DataRoot"

# ---- 1. 检查 Docker ----
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
  Write-Host "[ERROR] 未检测到 docker。请先安装 Docker Desktop:" -ForegroundColor Red
  Write-Host "        https://www.docker.com/products/docker-desktop/"
  Write-Host "        安装后建议把 Settings → Resources → Disk image location 改到 E 盘，再重跑本脚本。"
  exit 1
}
try { docker info | Out-Null } catch {
  Write-Host "[ERROR] Docker 引擎未运行。请启动 Docker Desktop 后重试。" -ForegroundColor Red
  exit 1
}

# ---- 2. 创建 E 盘数据目录 ----
foreach ($d in @($DataRoot, "$DataRoot\mysql", "$DataRoot\redis", "$DataRoot\logs")) {
  if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force $d | Out-Null }
}

# ---- 3. config.yaml（挂载到容器，落 E 盘） ----
$cfgPath = Join-Path $DataRoot "config.yaml"
if (-not (Test-Path $cfgPath)) {
  Copy-Item (Join-Path $ProjectDir "config.example.yaml") $cfgPath -Force
  Write-Host "[INFO] 已生成 $cfgPath（密钥可由 .env 覆盖，或直接编辑此文件）"
} else {
  Write-Host "[INFO] $cfgPath 已存在，跳过"
}

# ---- 4. 生成 .env（含随机密码 + DATA_ROOT） ----
function New-Secret([int]$len) {
  $b = [byte[]]::new(48)
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  $s = ([Convert]::ToBase64String($b)) -replace '[^a-zA-Z0-9]', ''
  return $s.Substring(0, $len)
}
function New-AesKey() {
  $b = [byte[]]::new(32)
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  return [Convert]::ToBase64String($b)
}

$dataRootFwd = $DataRoot -replace '\\', '/'
$envPath = Join-Path $ProjectDir ".env"
if (-not (Test-Path $envPath)) {
  $lines = @(
    "DB_ROOT_PASSWORD=$(New-Secret 24)",
    "DB_NAME=pophie",
    "DB_USER=pophie_user",
    "DB_PASSWORD=$(New-Secret 24)",
    "REDIS_PASSWORD=$(New-Secret 24)",
    "AES_KEY=$(New-AesKey)",
    "JPA_DDL_AUTO=update",
    "DATA_ROOT=$dataRootFwd",
    "LLM_API_KEY=",
    "DASHSCOPE_API_KEY=",
    "ADMIN_TOKEN="
  )
  Set-Content -Path $envPath -Value $lines -Encoding utf8
  Write-Host "[INFO] 已生成 .env（随机 DB/Redis 密码与 AES_KEY）。"
  Write-Host "[ACTION] 如需 LLM/语音/管理后台，请在 .env 填写 LLM_API_KEY / DASHSCOPE_API_KEY / ADMIN_TOKEN 后重跑本脚本。" -ForegroundColor Yellow
} else {
  Write-Host "[INFO] .env 已存在，跳过生成"
  if (-not (Select-String -Path $envPath -Pattern '^DATA_ROOT=' -Quiet)) {
    Add-Content -Path $envPath -Value "DATA_ROOT=$dataRootFwd"
    Write-Host "[INFO] 已补写 DATA_ROOT=$dataRootFwd 到 .env"
  }
}

# ---- 5. 构建并启动 ----
Write-Host "[INFO] 构建并启动容器（数据落 $DataRoot）..."
docker compose -f docker-compose.windows.yml up -d --build
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] docker compose 启动失败" -ForegroundColor Red; exit 1 }

# ---- 6. 等待健康 ----
Write-Host "[INFO] 等待 app 健康检查 ..."
for ($i = 1; $i -le 30; $i++) {
  Start-Sleep -Seconds 3
  $status = (docker inspect --format '{{.State.Health.Status}}' pophie-app 2>$null)
  if (-not $status) { $status = "starting" }
  Write-Host "  [$i/30] app health: $status"
  if ($status -eq "healthy") { break }
}

Write-Host ""
Write-Host "===== 部署完成 =====" -ForegroundColor Green
docker compose -f docker-compose.windows.yml ps
Write-Host ""
Write-Host "  - 健康： curl http://localhost:8000/api/health"
Write-Host "  - 前端： http://localhost:8000/   后台： http://localhost:8000/admin"
Write-Host "  - 日志： docker compose -f docker-compose.windows.yml logs -f app"
Write-Host "  - 停止： docker compose -f docker-compose.windows.yml down  (数据保留在 $DataRoot)"
