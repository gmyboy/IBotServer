<#
  下载 sherpa-onnx 预编译 Android AAR 到本目录（voice/libs/sherpa-onnx.aar）。
  用法：powershell -ExecutionPolicy Bypass -File download-sherpa-onnx.ps1 [-Version 1.13.3]
  说明：官方在 GitHub release 直接发布 sherpa-onnx-<ver>.aar（jitpack 也是下这同一个文件）。
#>
param(
  [string]$Version = "1.13.3"
)
$ErrorActionPreference = "Stop"
$dst = Join-Path $PSScriptRoot "sherpa-onnx.aar"
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$Version/sherpa-onnx-$Version.aar"
Write-Host "下载 $url -> $dst"
& curl.exe -L --fail --retry 3 -o $dst $url
if ($LASTEXITCODE -ne 0) { throw "下载失败，请确认版本号或网络" }
Write-Host ("完成：{0:N1} MB" -f ((Get-Item $dst).Length/1MB))
