<#
.SYNOPSIS
  阶段 1（国内离线版）：清掉卡住的 wsl 下载 + 仅启用 WSL2 所需 Windows 功能（不联网、不下发行版）。
  需以管理员身份运行；完成后请重启电脑。发行版与 Docker 在阶段 2 从国内镜像装到 E 盘。
#>
$ErrorActionPreference = "Continue"

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Write-Host "[ERROR] 请右键 PowerShell → 以管理员身份运行，再执行本脚本。" -ForegroundColor Red
  exit 1
}

Write-Host "[INFO] 1) 结束卡住的 wsl 安装/下载进程 ..."
taskkill /IM wsl.exe /F /T 2>$null | Out-Null
taskkill /IM wslinstaller.exe /F /T 2>$null | Out-Null
taskkill /IM WslInstall.exe /F /T 2>$null | Out-Null

Write-Host "[INFO] 2) 启用 Windows 功能（离线，不联网）..."
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

Write-Host ""
Write-Host "===== 阶段 1 完成 =====" -ForegroundColor Green
Write-Host "请现在重启： Restart-Computer"
Write-Host "重启后回来告诉我，我用清华/中科大镜像把 Ubuntu 装到 E:\WSL，再装 Docker、起服务（全程不走微软 CDN）。"
