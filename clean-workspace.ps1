# 清理可再生的建置產物，把工作區瘦回原始碼大小（~12MB）。
# 用法（在 repo 根目錄）：  .\clean-workspace.ps1
# 還原方式：
#   後端 jar    →  mvn -DskipTests package
#   前端相依    →  cd frontend-src\frontend; npm install
$ErrorActionPreference = 'SilentlyContinue'
$root = $PSScriptRoot

$targets = @(
    (Join-Path $root 'target'),                                  # Maven 建置產物（含 boot jar ~62MB）
    (Join-Path $root 'frontend-src\frontend\node_modules'),      # npm 相依（~147MB）
    (Join-Path $root 'frontend-src\frontend\dist')               # Vite 產物（已複製進 static，可再生）
)

foreach ($t in $targets) {
    if (Test-Path $t) {
        $mb = [math]::Round(((Get-ChildItem $t -Recurse -File -Force | Measure-Object Length -Sum).Sum) / 1MB, 1)
        Remove-Item $t -Recurse -Force
        Write-Host "已清除 $t（$mb MB）" -ForegroundColor Green
    }
}
Write-Host '完成。重建：mvn -DskipTests package；前端：npm install + .\build-frontend.ps1' -ForegroundColor Yellow
