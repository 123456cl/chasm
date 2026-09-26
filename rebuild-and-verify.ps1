# 三路修复收口：一次跑完构建 + 刷新游戏 jar + 双重核对
#
# 为什么需要：dev 客户端运行时会锁住 .gradle/loom-cache 里的 remapped jar，
# 导致 :remapJar 显示 UP-TO-DATE（其实没刷新）、下游编译读到旧缓存报"找不到符号"。
# 所以脚本第一步会先检查客户端是否还开着（开着就拒绝跑，避免半成品状态）。
#
# 用法（先关掉游戏！）：powershell -ExecutionPolicy Bypass -File rebuild-and-verify.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Step($msg) { Write-Host "== $msg" -ForegroundColor Cyan }

# 0) 拒绝在客户端运行时跑
$clients = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'dli.config|runClient' }
if ($clients) {
    Write-Host "游戏客户端还在运行（PID: $($clients.ProcessId -join ', ')）—— 请先关掉游戏再跑本脚本。" -ForegroundColor Red
    exit 1
}

Step '1/6 停守护进程'
& "$root\gradlew.bat" --stop 2>$null | Out-Null
Start-Sleep -Seconds 4

Step '2/6 删掉过期的 remapped 自家 jar（Loom 不会自动刷新）'
$rm = "$root\.gradle\loom-cache\remapped_mods\remapped"
Get-ChildItem -Recurse -File $rm -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'chasm-core*' -or $_.Name -like 'tetra-port*' } |
    ForEach-Object { Remove-Item -Force $_.FullName -ErrorAction SilentlyContinue }

Step '3/6 重建 core（新类必须先进 jar，下游才看得到）'
& "$root\gradlew.bat" :chasm-core:build --console=plain

Step '4/6 构建 port + example（含全部测试）'
& "$root\gradlew.bat" :tetra-port:build :chasm-example:build --console=plain

Step '5/6 清 remapped 后重建下游 → 触发重新 remap'
Get-ChildItem -Recurse -File $rm -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'chasm-core*' -or $_.Name -like 'tetra-port*' } |
    ForEach-Object { Remove-Item -Force $_.FullName -ErrorAction SilentlyContinue }
& "$root\gradlew.bat" :chasm-example:build --console=plain

Step '6/6 双重核对：新类在不在 + 世界生成数据在不在'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$core = Get-ChildItem -Recurse -File $rm -Filter 'chasm-core*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
$port = Get-ChildItem -Recurse -File $rm -Filter 'tetra-port*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
foreach ($j in @($core, $port)) {
    if (-not $j) { Write-Host "  缺 jar：$($j.Name)" -ForegroundColor Red; continue }
    $z = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
    $wg = ($z.Entries | Where-Object { $_.FullName -like 'data/tetra/worldgen/*' } | Measure-Object).Count
    $pr = ($z.Entries | Where-Object { $_.FullName -like '*TetraStructureProcessors*' } | Measure-Object).Count
    $geo = ($z.Entries | Where-Object { $_.FullName -like '*ChasmItemGeometry*' } | Measure-Object).Count
    $z.Dispose()
    Write-Host ("  {0}  {1}  worldgen={2} processors={3} ChasmItemGeometry={4}" -f $j.Name, $j.LastWriteTime, $wg, $pr, $geo)
}
Write-Host '完成。现在可以： .\gradlew.bat :chasm-example:runClient' -ForegroundColor Green
