# 验证"我的改动到底进游戏了没有"
#
# 为什么需要：dev 客户端加载的是 .gradle/loom-cache/remapped_mods/remapped/**/<mod>-*.jar，
# 而 compileJava / jar / :module:build 都**不会刷新**它。今天已经因此白干过两次
# （源码修好、编译成功、游戏里还是旧代码，同一个 NPE 崩了两轮）。
#
# 所以：改完代码 → 跑 refresh-dev.ps1 → 用本脚本**反汇编那个缓存 jar**确认符号真的在里面。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File verify-ingame.ps1
#   powershell -ExecutionPolicy Bypass -File verify-ingame.ps1 -Symbols "com.example.chasm.tetra.TetraModules:variant","api.chasm.data.ChasmDataRegistry:find"

param(
    [string[]] $Symbols = @(
        'com.example.chasm.tetra.TetraModules:variant',
        'api.chasm.data.ChasmDataRegistry:find'
    )
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javap = Join-Path (Split-Path (Get-Command java).Source) 'javap.exe'
if (-not (Test-Path $javap)) { $javap = 'javap' }
$remapped = "$root\.gradle\loom-cache\remapped_mods\remapped"

if (-not (Test-Path $remapped)) {
    Write-Host "找不到 remapped 缓存目录：$remapped（先跑一次 runClient 或 refresh-dev.ps1）" -ForegroundColor Red
    exit 1
}

$jars = Get-ChildItem -Recurse -File -Path $remapped -Filter '*.jar'
$fail = 0

foreach ($spec in ($Symbols -split ",")) {
    $parts = $spec.Split(':')
    $class = $parts[0]
    $member = if ($parts.Count -gt 1) { $parts[1] } else { '' }

    # 用类的包名去匹配可能的 jar（我们的模块：api.chasm.* → chasm-core，com.example.chasm.* → 各端口）
    $candidates = $jars | Where-Object {
        ($class -like 'api.chasm.*' -and $_.Name -like 'chasm-core*') -or
        ($class -like 'com.example.chasm.tetra.*' -and $_.Name -like 'tetra-port*') -or
        ($class -like 'com.example.chasm.apoth.*' -and $_.Name -like 'apoth-port*')
    }
    if (-not $candidates) {
        Write-Host ("[跳过] {0} —— 缓存里没有对应模块 jar" -f $class) -ForegroundColor Yellow
        continue
    }

    foreach ($jar in $candidates) {
        $out = & $javap -p -c -classpath $jar.FullName $class 2>&1 | Out-String
        if ($out -match 'Error|not found') {
            Write-Host ("[跳过] {0} 在 {1} 里没有" -f $class, $jar.Name) -ForegroundColor Yellow
            continue
        }
        if ($member -eq '' -or $out -match [regex]::Escape($member)) {
            Write-Host ("[PASS] {0}:{1}  于 {2}（{3}）" -f $class, $member, $jar.Name, $jar.LastWriteTime) -ForegroundColor Green
        } else {
            Write-Host ("[FAIL] {0}:{1} 不在 {2} 里 —— 游戏加载的是旧代码，跑 refresh-dev.ps1" -f $class, $member, $jar.Name) -ForegroundColor Red
            $fail++
        }
    }
}

if ($fail -gt 0) {
    Write-Host "有 $fail 个符号没进游戏：先 refresh-dev.ps1，再重跑本脚本" -ForegroundColor Red
    exit 1
}
Write-Host "全部符号都在游戏会加载的 jar 里。" -ForegroundColor Green
