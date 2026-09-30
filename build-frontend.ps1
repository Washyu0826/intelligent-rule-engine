# 重建前端並打包進 Spring Boot static —— 之後 `mvn package` 產出的單一 jar 啟動即服務完整 UI。
# 用法（在 repo 根目錄）：  .\build-frontend.ps1
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$fe   = Join-Path $root 'frontend-src/frontend'
$static = Join-Path $root 'src/main/resources/static'

Write-Host '== 1/3 安裝前端相依 ==' -ForegroundColor Cyan
Push-Location $fe
npm install
Write-Host '== 2/3 建置前端 (tsc + vite) ==' -ForegroundColor Cyan
npm run build
Pop-Location

Write-Host '== 3/3 複製 dist -> Spring static（保留 demo.html）==' -ForegroundColor Cyan
Remove-Item (Join-Path $static 'assets') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $static 'index.html'),(Join-Path $static 'vite.svg'),(Join-Path $static 'logo.svg') -Force -ErrorAction SilentlyContinue
Copy-Item (Join-Path $fe 'dist/*') $static -Recurse -Force

Write-Host "完成：前端已打包進 $static" -ForegroundColor Green
Write-Host '接著執行：  mvn -DskipTests package   然後  java -jar target\rules-mcp-server-*.jar' -ForegroundColor Yellow
