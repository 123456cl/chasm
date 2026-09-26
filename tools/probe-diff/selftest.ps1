# probe-diff 自测脚本
#
#   powershell -ExecutionPolicy Bypass -File selftest.ps1
#
# 做四件事：
#   1. 用 fixtures/make_fixtures.py 造两份合成 JSONL
#   2. 跑差分，断言 fixture **覆盖了全部 54 个差异类别**，并核对关键计数
#   3. 跑边界用例（反向传参 / --fail-on / 缺文件 / --no-info / --state-ignore）
#   4. 在 JDK 17 上跑同一份源码与 ASCII 转义副本，断言报告与 JDK 25 逐字节相同
#
# 退出码：0 = 全绿；1 = 有断言失败。
#
# 注意：java 的 -D 参数必须用数组展开（PowerShell 会把未加引号的 -Dxxx.yyy=zzz 拆坏）。
#       报告一律用 .NET 的 UTF8 读取（本机 Windows PowerShell 5.1 的 Get-Content 默认按 ANSI 读，会误判为乱码）。

# 用 Continue 而不是 Stop：原生命令（java -version 等）往 stderr 写东西时，
# Stop 会把它当成终止性错误抛出。本脚本所有失败都靠下面的 Check 显式判定。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'

if ([string]::IsNullOrEmpty($PSScriptRoot)) { $here = (Get-Location).Path } else { $here = $PSScriptRoot }
Set-Location $here
$outDir = Join-Path $here 'out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$script:checks = 0
$script:fails = 0
function Check([string]$name, [bool]$ok, [string]$detail) {
    $script:checks++
    if ($ok) {
        Write-Host ('  [PASS] ' + $name)
    } else {
        Write-Host ('  [FAIL] ' + $name + '    ' + $detail)
        $script:fails++
    }
}
function ReadUtf8([string]$path) {
    if (-not (Test-Path $path)) { throw ('报告文件不存在：' + $path) }
    return [System.IO.File]::ReadAllText((Resolve-Path $path).Path, [System.Text.Encoding]::UTF8)
}
function HashOf([string]$path) {
    if (-not (Test-Path $path)) { return '(missing)' }
    return (Get-FileHash $path -Algorithm SHA256).Hash
}

# ------------------------------------------------------------------ [0] 环境
Write-Host '================================================================'
Write-Host '[0] 环境'
Write-Host '================================================================'
$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if ([string]::IsNullOrEmpty($java)) { Write-Host '找不到 java，退出'; exit 1 }
$javaVer = (& $java -version 2>&1 | Select-Object -First 1)
Write-Host ('  PATH 上的 java : ' + $java)
Write-Host ('  版本           : ' + $javaVer)
$jdk17 = 'C:\Program Files\Java\jdk-17.0.2\bin\java.exe'
Write-Host ('  JDK 17         : ' + (Test-Path $jdk17))
$py = (Get-Command python -ErrorAction SilentlyContinue).Source
Write-Host ('  python         : ' + $py)

# java 参数一律数组展开；-Dfile.encoding 管 JDK 17，-Dstdout.encoding 管 JDK 19+
$JOPTS = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8')
$FIX = @('fixtures\a-real.jsonl', 'fixtures\b-port.jsonl')
$md = Join-Path $outDir 'report.md'
$js = Join-Path $outDir 'report.json'

# ------------------------------------------------------------ [1] 造 fixture
Write-Host ''
Write-Host '================================================================'
Write-Host '[1] 生成合成 fixture'
Write-Host '================================================================'
& python 'fixtures\make_fixtures.py'
Check 'fixture 生成脚本退出码 0' ($LASTEXITCODE -eq 0) ('实际 ' + $LASTEXITCODE)
Check 'fixtures\a-real.jsonl 存在' (Test-Path 'fixtures\a-real.jsonl')
Check 'fixtures\b-port.jsonl 存在' (Test-Path 'fixtures\b-port.jsonl')

# -------------------------------------------------------------- [2] 主用例
Write-Host ''
Write-Host '================================================================'
Write-Host '[2] 主用例：java -Dfile.encoding=UTF-8 ... ProbeDiff.java fixtures\a-real.jsonl fixtures\b-port.jsonl'
Write-Host '================================================================'
& $java ($JOPTS + @('ProbeDiff.java') + $FIX + @('--out-md', $md, '--out-json', $js))
$rc = $LASTEXITCODE
Check '主用例退出码 0' ($rc -eq 0) ('实际 ' + $rc)
Check 'report.md 已生成' (Test-Path $md)
Check 'report.json 已生成' (Test-Path $js)

$rep = ReadUtf8 $js | ConvertFrom-Json
Check 'report.json 是合法 JSON' ($null -ne $rep) ''
Check 'report.json 的 schema 字段正确' ($rep.schema -eq 'chasm.gui.probe/1') ('实际 ' + $rep.schema)
Check 'report.md 是 UTF-8 且含中文标题' ((ReadUtf8 $md).StartsWith('# 端口')) ''

# ------------------------------------------------- [3] 类别覆盖（全类别过一遍）
Write-Host ''
Write-Host '================================================================'
Write-Host '[3] 差异类别覆盖：fixture 必须让每一个类别都出现'
Write-Host '================================================================'
$catalog = $rep.categoryCatalog
$uncovered = @()
foreach ($c in $catalog) { if ([int]$c.count -lt 1) { $uncovered += $c.id } }
# pair_key_collision 只在显式 --pair-by 且配对键过粗时才可能出现，由下面的 [6] 节（fixture2）覆盖
$uncoveredExceptCollision = @($uncovered | Where-Object { $_ -ne 'pair_key_collision' })
Check ('fixture1 覆盖 ' + $catalog.Count + ' 个类别中的 ' + ($catalog.Count - $uncovered.Count) + ' 个（只允许 pair_key_collision 缺席）') ($uncoveredExceptCollision.Count -eq 0) ('未覆盖: ' + ($uncovered -join ', '))
Check 'fixture1 确实覆盖了 54 个类别' (($catalog.Count - $uncovered.Count) -eq 54) ('实际 ' + ($catalog.Count - $uncovered.Count))
Write-Host ''
Write-Host '  实际跑出来的类别计数（严重度 / 类别 id / 中文 / 数量）：'
foreach ($c in $catalog) {
    if ([int]$c.count -gt 0) {
        Write-Host ('    ' + $c.severity.PadRight(9) + $c.id.PadRight(22) + $c.title.PadRight(20) + $c.count)
    }
}

# --------------------------------------------------------- [4] 关键计数断言
Write-Host ''
Write-Host '================================================================'
Write-Host '[4] 关键计数断言'
Write-Host '================================================================'
Check 'summary.diffTotal = 95' ($rep.summary.diffTotal -eq 95) ('实际 ' + $rep.summary.diffTotal)
Check 'summary.pairs = 5' ($rep.summary.pairs -eq 5) ('实际 ' + $rep.summary.pairs)
Check 'summary.lowConfidencePairs = 2' ($rep.summary.lowConfidencePairs -eq 2) ('实际 ' + $rep.summary.lowConfidencePairs)
Check 'summary.unpairedA = 1' ($rep.summary.unpairedA -eq 1) ('实际 ' + $rep.summary.unpairedA)
Check 'summary.unpairedB = 1' ($rep.summary.unpairedB -eq 1) ('实际 ' + $rep.summary.unpairedB)
Check 'recordsA = 6（A 侧 8 行：7 行合法、1 行截断；再按同键去重 1 条）' ($rep.summary.recordsA -eq 6) ('实际 ' + $rep.summary.recordsA)
Check 'recordsB = 6' ($rep.summary.recordsB -eq 6) ('实际 ' + $rep.summary.recordsB)
Check '配对级别 exact = 3' ($rep.summary.pairsByLevel.exact -eq 3) ('实际 ' + $rep.summary.pairsByLevel.exact)
Check '配对级别 state-values-differ = 1' ($rep.summary.pairsByLevel.'state-values-differ' -eq 1) ('实际 ' + $rep.summary.pairsByLevel.'state-values-differ')
Check '配对级别 order-guess = 1' ($rep.summary.pairsByLevel.'order-guess' -eq 1) ('实际 ' + $rep.summary.pairsByLevel.'order-guess')

$expect = [ordered]@{
    'node_missing' = 2; 'node_extra' = 1; 'node_type' = 2; 'node_action' = 2; 'node_enabled' = 1;
    'node_coord' = 5; 'node_size' = 1; 'node_texture' = 2; 'node_uv' = 1; 'node_text' = 1;
    'node_color' = 5; 'node_opacity' = 1; 'node_scaled' = 1; 'node_draw' = 1; 'node_layer' = 1;
    'node_slot' = 3; 'node_handler' = 3; 'node_anim' = 4; 'node_sem' = 2; 'node_textscale' = 1;
    'node_shadow' = 1; 'node_tooltip' = 1; 'node_pair_lowconf' = 3; 'node_key_conflict' = 1;
    'slot_missing' = 1; 'slot_extra' = 1; 'slot_coord' = 1; 'slot_active' = 1; 'slot_item' = 1;
    'slot_empty' = 1; 'slot_count' = 2; 'slot_dynamic' = 1; 'slot_filtered' = 1; 'slot_sem' = 2;
    'ia_missing' = 1; 'ia_extra' = 1; 'ia_action' = 2; 'ia_value' = 2; 'ia_enabled' = 1;
    'ia_rect' = 3; 'ia_handler' = 5;
    'state_keys_only_a' = 1; 'state_keys_only_b' = 2; 'state_value' = 2; 'state_decl' = 3;
    'line_field' = 4; 'line_sem' = 3; 'line_gui' = 1; 'line_nodesCount' = 1;
    'record_only_a' = 1; 'record_only_b' = 1; 'record_dup' = 1; 'parse_error' = 1; 'schema_mismatch' = 1;
}
foreach ($k in $expect.Keys) {
    $c = $catalog | Where-Object { $_.id -eq $k }
    Check ('计数 ' + $k + ' = ' + $expect[$k]) ([int]$c.count -eq $expect[$k]) ('实际 ' + $c.count)
}

# ------------------------------------------------------------- [5] 边界用例
Write-Host ''
Write-Host '================================================================'
Write-Host '[5] 边界用例'
Write-Host '================================================================'

# 5a 反向传参（A/B 互换）
$revMd = Join-Path $outDir 'rev.md'; $revJs = Join-Path $outDir 'rev.json'
& $java ($JOPTS + @('ProbeDiff.java', 'fixtures\b-port.jsonl', 'fixtures\a-real.jsonl', '--out-md', $revMd, '--out-json', $revJs)) | Out-Null
Check 'A/B 互换仍可跑（退出码 0）' ($LASTEXITCODE -eq 0) ('实际 ' + $LASTEXITCODE)
$revRep = ReadUtf8 $revJs | ConvertFrom-Json
Check 'A/B 互换后差异总数相同' ($revRep.summary.diffTotal -eq 95) ('实际 ' + $revRep.summary.diffTotal)

# 5b --fail-on critical → 退出码 2
& $java ($JOPTS + @('ProbeDiff.java') + $FIX + @('--out-md', (Join-Path $outDir 'fo.md'), '--out-json', (Join-Path $outDir 'fo.json'), '--fail-on', 'critical')) | Out-Null
Check '--fail-on critical 退出码 2' ($LASTEXITCODE -eq 2) ('实际 ' + $LASTEXITCODE)
& $java ($JOPTS + @('ProbeDiff.java') + $FIX + @('--out-md', (Join-Path $outDir 'fo2.md'), '--out-json', (Join-Path $outDir 'fo2.json'), '--fail-on', 'none')) | Out-Null
Check '--fail-on none 退出码 0' ($LASTEXITCODE -eq 0) ('实际 ' + $LASTEXITCODE)

# 5c 文件不存在 → 退出码 1 + 明确报错
$errText = (& $java ($JOPTS + @('ProbeDiff.java', 'fixtures\__does_not_exist__.jsonl', 'fixtures\b-port.jsonl')) 2>&1 | Out-String)
$rcMissing = $LASTEXITCODE
Check '缺文件退出码 1' ($rcMissing -eq 1) ('实际 ' + $rcMissing)
Check '缺文件报错写明"不存在"' ($errText -match '不存在') ('stderr: ' + $errText.Trim())

# 5d --no-info：info 级全部消失
$niJs = Join-Path $outDir 'ni.json'
& $java ($JOPTS + @('ProbeDiff.java') + $FIX + @('--out-md', (Join-Path $outDir 'ni.md'), '--out-json', $niJs, '--no-info')) | Out-Null
$niRep = ReadUtf8 $niJs | ConvertFrom-Json
$infoSum = ($niRep.categoryCatalog | Where-Object { $_.severity -eq 'info' } | ForEach-Object { [int]$_.count } | Measure-Object -Sum).Sum
if ($null -eq $infoSum) { $infoSum = 0 }
Check '--no-info 后 info 级计数全为 0' ($infoSum -eq 0) ('实际 ' + $infoSum)
Check '--no-info 后 diffTotal = 89（95 - 6 条 info）' ($niRep.summary.diffTotal -eq 89) ('实际 ' + $niRep.summary.diffTotal)

# 5e --state-ignore special,page → 第 3 条记录不再降级为 order-guess
$siJs = Join-Path $outDir 'si.json'
& $java ($JOPTS + @('ProbeDiff.java') + $FIX + @('--out-md', (Join-Path $outDir 'si.md'), '--out-json', $siJs, '--state-ignore', 'special,page')) | Out-Null
$siRep = ReadUtf8 $siJs | ConvertFrom-Json
Check '--state-ignore 生效：低置信配对 2 -> 1' ($siRep.summary.lowConfidencePairs -eq 1) ('实际 ' + $siRep.summary.lowConfidencePairs)
Check '--state-ignore 生效：order-guess 级别消失' ($null -eq $siRep.summary.pairsByLevel.'order-guess') ('实际 ' + $siRep.summary.pairsByLevel.'order-guess')

# 5f 未知选项 → 退出码 1
& $java ($JOPTS + @('ProbeDiff.java') + $FIX + @('--not-an-option')) 2>&1 | Out-Null
Check '未知选项退出码 1' ($LASTEXITCODE -eq 1) ('实际 ' + $LASTEXITCODE)

# ---------------------- [6] fixture2：--pair-by / --slots-scope / --focus / --ignore
Write-Host ''
Write-Host '================================================================'
Write-Host '[6] fixture2：--pair-by / --slots-scope / --focus / --ignore'
Write-Host '================================================================'
# fixture2 是"真机侧风格 vs 端口侧风格"的一对：真机侧 variant/material_page 恒为 -1（真机没有这两个维度）、
# slots[] 里混着 4 个玩家背包槽（role 也是 material、ci>=9）且两侧数组下标顺序不同；
# 端口侧 material_page 是真值、只有 4 个容器槽。
$FIX2 = @('fixtures\c-real.jsonl', 'fixtures\d-port.jsonl')
& python 'fixtures\make_fixtures2.py'
Check 'fixture2 生成脚本退出码 0' ($LASTEXITCODE -eq 0) ('实际 ' + $LASTEXITCODE)
Check 'fixtures\c-real.jsonl 存在' (Test-Path 'fixtures\c-real.jsonl')
Check 'fixtures\d-port.jsonl 存在' (Test-Path 'fixtures\d-port.jsonl')

# 6a 默认配对：state 全签名必然配不上（真机没有 variant/material_page 维度）
$cDefJs = Join-Path $outDir 'c_def.json'
& $java ($JOPTS + @('ProbeDiff.java') + $FIX2 + @('--out-md', (Join-Path $outDir 'c_def.md'), '--out-json', $cDefJs)) | Out-Null
$cDef = ReadUtf8 $cDefJs | ConvertFrom-Json
Check '6a 默认配对：3 对全是低置信降级（exact 级别缺席）' (($cDef.summary.pairs -eq 3) -and ($cDef.summary.lowConfidencePairs -eq 3) -and ($null -eq $cDef.summary.pairsByLevel.exact)) ('pairs=' + $cDef.summary.pairs + ' low=' + $cDef.summary.lowConfidencePairs)
Check '6a 默认配对：3 对都落在 state-values-differ' ($cDef.summary.pairsByLevel.'state-values-differ' -eq 3) ('实际 ' + $cDef.summary.pairsByLevel.'state-values-differ')
Check '6a 默认配对：真机侧独有状态 1 条（TWEAK）' ($cDef.summary.unpairedA -eq 1) ('实际 ' + $cDef.summary.unpairedA)

# 6b --pair-by gui,page,modules：精确配上、不再降级猜测，并暴露配对键碰撞
$cPbJs = Join-Path $outDir 'c_pb.json'
& $java ($JOPTS + @('ProbeDiff.java') + $FIX2 + @('--pair-by', 'gui,page,modules', '--out-md', (Join-Path $outDir 'c_pb.md'), '--out-json', $cPbJs)) | Out-Null
$cPb = ReadUtf8 $cPbJs | ConvertFrom-Json
Check '6b --pair-by：2 对全部高置信（lowConfidencePairs=0）' (($cPb.summary.pairs -eq 2) -and ($cPb.summary.lowConfidencePairs -eq 0)) ('pairs=' + $cPb.summary.pairs + ' low=' + $cPb.summary.lowConfidencePairs)
Check '6b --pair-by：A/B 各 1 条真·独有状态' (($cPb.summary.unpairedA -eq 1) -and ($cPb.summary.unpairedB -eq 1)) ('uA=' + $cPb.summary.unpairedA + ' uB=' + $cPb.summary.unpairedB)
Check '6b --pair-by：A 侧 4 条→3 条（同配对键被去重）' ($cPb.summary.recordsA -eq 3) ('实际 ' + $cPb.summary.recordsA)
Check '6b 配对键碰撞被报出（pair_key_collision=1）' ($cPb.summary.countsByCategory.pair_key_collision -eq 1) ('实际 ' + $cPb.summary.countsByCategory.pair_key_collision)
$covPb = $cPb.stateCoverage
Check '6b 状态覆盖表 4 行 = 2 paired + 1 only-a + 1 only-b' (($covPb.Count -eq 4) -and (@($covPb | Where-Object { $_.coverage -eq 'paired' }).Count -eq 2) -and (@($covPb | Where-Object { $_.coverage -eq 'only-a' }).Count -eq 1) -and (@($covPb | Where-Object { $_.coverage -eq 'only-b' }).Count -eq 1)) ('rows=' + $covPb.Count)
Check '6b 覆盖表在 A 记录数=2 的那行暴露了碰撞' ($null -ne ($covPb | Where-Object { $_.coverage -eq 'paired' -and $_.aRecords -eq 2 } | Select-Object -First 1)) '没找到 aRecords=2 的 paired 行'

# 6c --slots-scope 三档：real-all / role（默认）/ storage-only
$raJs = Join-Path $outDir 'c_ra.json'
$roJs = Join-Path $outDir 'c_ro.json'
$stJs = Join-Path $outDir 'c_st.json'
& $java ($JOPTS + @('ProbeDiff.java') + $FIX2 + @('--pair-by', 'gui,page,modules', '--slots-scope', 'real-all', '--out-md', (Join-Path $outDir 'c_ra.md'), '--out-json', $raJs)) | Out-Null
& $java ($JOPTS + @('ProbeDiff.java') + $FIX2 + @('--pair-by', 'gui,page,modules', '--slots-scope', 'role', '--out-md', (Join-Path $outDir 'c_ro.md'), '--out-json', $roJs)) | Out-Null
& $java ($JOPTS + @('ProbeDiff.java') + $FIX2 + @('--pair-by', 'gui,page,modules', '--slots-scope', 'storage-only', '--out-md', (Join-Path $outDir 'c_st.md'), '--out-json', $stJs)) | Out-Null
$ra = ReadUtf8 $raJs | ConvertFrom-Json
$ro = ReadUtf8 $roJs | ConvertFrom-Json
$st = ReadUtf8 $stJs | ConvertFrom-Json
Check '6c real-all：按数组下标配对 → 错位噪声最大（slot_sem=24 且 slot_coord=14）' (($ra.summary.countsByCategory.slot_sem -eq 24) -and ($ra.summary.countsByCategory.slot_coord -eq 14)) ('sem=' + $ra.summary.countsByCategory.slot_sem + ' coord=' + $ra.summary.countsByCategory.slot_coord)
Check '6c role（默认）：容器槽按 (role,ci) 对上了，但背包槽仍在比 → slot_missing=10' ($ro.summary.countsByCategory.slot_missing -eq 10) ('实际 ' + $ro.summary.countsByCategory.slot_missing)
Check '6c storage-only：背包槽被排除（A 侧丢弃 8 个）→ slot_missing 降到 2' (($st.summary.countsByCategory.slot_missing -eq 2) -and ($st.slotsScope.perSide.A.dropped -eq 8)) ('missing=' + $st.summary.countsByCategory.slot_missing + ' droppedA=' + $st.slotsScope.perSide.A.dropped)
Check '6c 三档总差异单调下降 77 > 37 > 29' (($ra.summary.diffTotal -eq 77) -and ($ro.summary.diffTotal -eq 37) -and ($st.summary.diffTotal -eq 29)) ('ra=' + $ra.summary.diffTotal + ' ro=' + $ro.summary.diffTotal + ' st=' + $st.summary.diffTotal)
Check '6c role 模式在报告里明说"背包槽仍会被比较"' ((ReadUtf8 (Join-Path $outDir 'c_ro.md')).Contains('仍会被比较')) ''
Check '6c storage-only 模式在报告里解释了 container-max 的理由' ((ReadUtf8 (Join-Path $outDir 'c_st.md')).Contains('能区分')) ''

# 6d --focus：按族汇总 + 下钻到具体节点（两侧 rect/text/action 对照）
$focJs = Join-Path $outDir 'c_focus.json'
& $java ($JOPTS + @('ProbeDiff.java') + $FIX2 + @('--pair-by', 'gui,page,modules', '--slots-scope', 'storage-only', '--focus', '--examples', '12', '--out-md', (Join-Path $outDir 'c_focus.md'), '--out-json', $focJs)) | Out-Null
$foc = ReadUtf8 $focJs | ConvertFrom-Json
$focMd = ReadUtf8 (Join-Path $outDir 'c_focus.md')
$famNames = @($foc.families | ForEach-Object { $_.family })
$wantFam = @('frame:', 'name:', 'attack_damage:', '(structural r)', 'grow:', 'gname:', 'tab:', 'extra:', '(slots)', '(state)', '(line)', '(structural craft_bg)')
$missFam = @($wantFam | Where-Object { $famNames -notcontains $_ })
Check ('6d --focus 族汇总覆盖 ' + $wantFam.Count + ' 个预期族（缺 ' + $missFam.Count + ' 个）') ($missFam.Count -eq 0) ('缺: ' + ($missFam -join ', '))
Check '6d --focus 报告含"族汇总"与"族下钻"两节' ($focMd.Contains('族汇总') -and $focMd.Contains('族下钻')) ''
Check '6d 下钻表给出两侧 rect/text/action 对照表头' ($focMd.Contains('A 侧（rect / text / action）') -and $focMd.Contains('B 侧（rect / text / action）')) ''
Check '6d 下钻能追到具体节点：name:sword/blade 两侧文字并排（剑刃 vs Blade）' (($focMd -match 'name:sword/blade') -and ($focMd -match 'text=剑刃') -and ($focMd -match 'text=Blade')) ''

# 6e --ignore：如实列出被忽略的类别与条数，且不计入总数
$igJs = Join-Path $outDir 'c_ign.json'
& $java ($JOPTS + @('ProbeDiff.java') + $FIX2 + @('--pair-by', 'gui,page,modules', '--slots-scope', 'storage-only', '--ignore', 'line_sem,state_decl,node_coord,slot_sem', '--out-md', (Join-Path $outDir 'c_ign.md'), '--out-json', $igJs)) | Out-Null
$ig = ReadUtf8 $igJs | ConvertFrom-Json
$igMd = ReadUtf8 (Join-Path $outDir 'c_ign.md')
Check '6e ignoredTotal = 10' ($ig.summary.ignoredTotal -eq 10) ('实际 ' + $ig.summary.ignoredTotal)
Check '6e 被忽略项逐类计数正确（slot_sem=6, line_sem=2, node_coord=2）' (($ig.summary.ignoredByCategory.slot_sem -eq 6) -and ($ig.summary.ignoredByCategory.line_sem -eq 2) -and ($ig.summary.ignoredByCategory.node_coord -eq 2)) ('实际 ' + ($ig.summary.ignoredByCategory | ConvertTo-Json -Compress))
Check '6e 被忽略条目不计入总数（29 - 10 = 19）' ($ig.summary.diffTotal -eq 19) ('实际 ' + $ig.summary.diffTotal)
Check '6e 报告如实列出被忽略类别，且标出"请求了但本次 0 命中"的 state_decl' (($igMd.Contains('被 --ignore 忽略的类别')) -and ($igMd.Contains('本次无此类差异'))) ''
& $java ($JOPTS + @('ProbeDiff.java') + $FIX2 + @('--ignore', 'no_such_category')) 2>&1 | Out-Null
Check '6e --ignore 未知类别 id → 退出码 1' ($LASTEXITCODE -eq 1) ('实际 ' + $LASTEXITCODE)

# 6f --list-cats
$catOut = (& $java ($JOPTS + @('ProbeDiff.java', '--list-cats')) 2>&1 | Out-String)
Check '6f --list-cats 列出 55 个类别' ($catOut -match '共 55 个') ''
Check '6f --list-cats 退出码 0' ($LASTEXITCODE -eq 0) ('实际 ' + $LASTEXITCODE)

# --------------------------------------------------- [7] JDK 17 兼容性
Write-Host ''
Write-Host '================================================================'
Write-Host '[7] JDK 17 兼容性'
Write-Host '================================================================'
$baseHash = HashOf $md
Write-Host ('  JDK 25 基准 report.md SHA256 = ' + $baseHash)
if (Test-Path $jdk17) {
    # 6a 同一份 UTF-8 源码 + -Dfile.encoding=UTF-8
    $j17Md = Join-Path $outDir 'report.jdk17.md'
    $j17Js = Join-Path $outDir 'report.jdk17.json'
    # 这轮不展开打印（输出与上面 JDK 25 那轮完全一样）；证据是下面的逐字节哈希比对
    & $jdk17 ($JOPTS + @('ProbeDiff.java') + $FIX + @('--out-md', $j17Md, '--out-json', $j17Js)) | Out-Null
    Check 'JDK 17 跑同一份源码退出码 0' ($LASTEXITCODE -eq 0) ('实际 ' + $LASTEXITCODE)
    Check 'JDK 17 报告与 JDK 25 逐字节相同' ((HashOf $j17Md) -eq $baseHash) ('jdk17=' + (HashOf $j17Md))

    # 6b JDK 17 上"不带 -Dfile.encoding=UTF-8"会把中文源码读成 cp936（证明该参数必需）
    $badMd = Join-Path $outDir 'report.jdk17.nofix.md'
    $badOut = (& $jdk17 (@('ProbeDiff.java') + $FIX + @('--out-md', $badMd, '--out-json', (Join-Path $outDir 'report.jdk17.nofix.json'))) 2>&1 | Out-String)
    $badHash = HashOf $badMd
    $badDiffers = ((-not (Test-Path $badMd)) -or ($badHash -ne $baseHash))
    Check 'JDK 17 不带 -Dfile.encoding=UTF-8 时报告与基准不同（该参数必需）' $badDiffers ('hash=' + $badHash)

    # 6c ASCII 转义副本（备选路线）：与基准也必须逐字节相同
    & python 'escape-src.py' 'ProbeDiff.java' (Join-Path $outDir 'ProbeDiff.ascii.java')
    $asciiSrc = Join-Path $outDir 'ProbeDiff.ascii.java'
    $raw = [System.IO.File]::ReadAllBytes($asciiSrc)
    $maxByte = 0
    foreach ($b in $raw) { if ($b -gt $maxByte) { $maxByte = $b } }
    Check 'escape-src.py 产出纯 ASCII 源码（最大字节 <= 127）' ($maxByte -le 127) ('实际 ' + $maxByte)
    $asciiMd = Join-Path $outDir 'report.ascii.md'
    # ASCII 副本的源码本身在任何编码下都一样；但还是传 JOPTS，让 stdout 上的中文也是 UTF-8
    & $jdk17 ($JOPTS + @($asciiSrc) + $FIX + @('--out-md', $asciiMd, '--out-json', (Join-Path $outDir 'report.ascii.json'))) | Out-Null
    Check 'JDK 17 跑 ASCII 副本退出码 0' ($LASTEXITCODE -eq 0) ('实际 ' + $LASTEXITCODE)
    Check 'JDK 17 ASCII 副本报告与基准逐字节相同' ((HashOf $asciiMd) -eq $baseHash) ('=' + (HashOf $asciiMd))
} else {
    Write-Host '  (跳过：本机没有 C:\Program Files\Java\jdk-17.0.2\bin\java.exe)'
}

# --------------------------------------------------------------- [8] 结论
Write-Host ''
Write-Host '================================================================'
Write-Host '[8] 结论'
Write-Host '================================================================'
Write-Host ('  断言 ' + $script:checks + ' 条，失败 ' + $script:fails + ' 条')
if ($script:fails -eq 0) {
    Write-Host '  结果：全部通过（PASS）'
    exit 0
} else {
    Write-Host '  结果：有失败（FAIL）'
    exit 1
}
