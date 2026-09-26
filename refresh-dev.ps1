# 强制刷新开发环境（改了框架/移植代码后，先跑这个再 runClient）
#
# 为什么需要：Fabric Loom 会把**依赖模块**的 jar 先 remap 一份缓存到
# .gradle/loom-cache/remapped_mods/remapped/...，缓存键是「模块 + 版本号」。
# 我们改代码时版本号不变（一直 1.1.2），于是 dev 客户端可能继续加载**旧的 remap 产物** ——
# 表现就是"新物品/新界面/新修复在游戏里完全不出现"，而构建日志一切正常。
#
# ⚠️ 2026-09-18 又踩一次（代价：同一个 NPE 崩溃复现两轮）：
#   `gradlew :tetra-port:compileJava` 和 `gradlew :tetra-port:build` **都不会刷新** remapped_mods 里的 jar。
#   实测：源码修好、class 编译于 13:19:10、`:tetra-port:build` 成功，
#   而 remapped 缓存里的 jar 仍是 13:18:46 的旧产物（javap 一看，方法里根本没有新加的空值保护）。
#   → **判断"修复有没有进游戏"的唯一可靠办法是反汇编那个缓存 jar**，别信 BUILD SUCCESSFUL，也别信时间戳。
#
# 关键细节：
#   1) 构建完成之后必须让 Loom **重新 remap**：删掉我们自己的缓存 jar，再构建一次下游项目
#      （下游 chasm-example 依赖它们，构建时会用新 plain jar 重新 remap）。
#   2) 只删**我们自己模块**的缓存 jar（chasm-core/tetra-port/apoth-port/chasm-example），
#      不要整个删 loom-cache —— 那会连 Minecraft 的准备产物一起丢，下次构建要重做十几分钟。
#   3) 脚本最后会自检：每个模块的缓存 jar 必须比 build/libs 里的 plain jar **新**，否则报错退出。
#
# 用法：powershell -ExecutionPolicy Bypass -File refresh-dev.ps1
# 然后再 .\gradlew.bat :chasm-example:runClient

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$remapped = "$root\.gradle\loom-cache\remapped_mods\remapped"
$modules = @('chasm-core', 'tetra-port', 'apoth-port', 'chasm-example')

function Remove-OwnRemappedJars([string] $why) {
    Write-Host "[$why] 清除我们自己模块的 remap 缓存 jar" -ForegroundColor Cyan
    if (-not (Test-Path $remapped)) { return }
    Get-ChildItem -Recurse -File -Path $remapped -Filter '*.jar' |
        Where-Object { $name = $_.Name; $modules | Where-Object { $name -like "$_*" } } |
        ForEach-Object { Remove-Item -Force $_.FullName }
}

Write-Host "[1/5] 停止 Gradle 守护进程" -ForegroundColor Cyan
& "$root\gradlew.bat" --stop | Out-Null
Start-Sleep -Seconds 2

Write-Host "[2/5] 重建全部模块（含 tetra-port —— 以前这里漏了它）" -ForegroundColor Cyan
& "$root\gradlew.bat" :chasm-core:build :tetra-port:build :apoth-port:build :chasm-example:build
if ($LASTEXITCODE -ne 0) { Write-Host "构建失败，先修编译错误" -ForegroundColor Red; exit 1 }

Remove-OwnRemappedJars "3/5 构建后"

Write-Host "[4/5] 再次构建下游 → 触发 Loom 用新 plain jar 重新 remap" -ForegroundColor Cyan
& "$root\gradlew.bat" :chasm-example:build
if ($LASTEXITCODE -ne 0) { Write-Host "重新 remap 失败" -ForegroundColor Red; exit 1 }

Write-Host "[5/5] 自检：缓存 jar 必须比 plain jar 新" -ForegroundColor Cyan
$bad = 0
foreach ($m in $modules) {
    $plain = Get-ChildItem -File "$root\$m\build\libs" -Filter "$m-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*-sources*' -and $_.Name -notlike '*-dev*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $plain) { continue }
    $cached = Get-ChildItem -Recurse -File -Path $remapped -Filter "$m-*.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $cached) {
        Write-Host ("  {0}: 没有 remap 缓存（首次运行正常，runClient 时会生成）" -f $m) -ForegroundColor Yellow
        continue
    }
    if ($cached.LastWriteTime -lt $plain.LastWriteTime) {
        Write-Host ("  {0}: 缓存 {1} 早于 plain {2} —— 客户端会加载旧代码！" -f $m, $cached.LastWriteTime, $plain.LastWriteTime) -ForegroundColor Red
        $bad++
    } else {
        Write-Host ("  {0}: OK（缓存 {1}）" -f $m, $cached.LastWriteTime) -ForegroundColor Green
    }
}
if ($bad -gt 0) { Write-Host "自检未通过：请手工删掉对应缓存 jar 后重跑本脚本" -ForegroundColor Red; exit 1 }

Write-Host "完成。现在运行： .\gradlew.bat :chasm-example:runClient" -ForegroundColor Green
