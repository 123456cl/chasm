# Final verification (ASCII only, safe for Windows PowerShell 5.1)
# Checks: game-loaded jars, new classes present, data entry counts, test summary.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
function Step($m) { Write-Host ('== ' + $m) -ForegroundColor Cyan }

Step '0/5 client running? (must be closed: it locks the loom cache jars)'
$clients = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'dli.config|runClient' }
if ($clients) { Write-Host ('game still running (PID: ' + ($clients.ProcessId -join ', ') + ') - close it first') -ForegroundColor Red; exit 1 }

$remapped = Join-Path $root '.gradle/loom-cache/remapped_mods/remapped'
if (-not (Test-Path $remapped)) { Write-Host ('no remapped cache: ' + $remapped) -ForegroundColor Red; exit 1 }

Step '1/5 jars the game actually loads'
$jars = @{}
foreach ($m in @('chasm-core','tetra-port','chasm-example')) {
    $j = Get-ChildItem -Recurse -File $remapped -Filter ($m + '-*.jar') -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*sources*' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($j) { $jars[$m] = $j; Write-Host ('  ' + $j.Name + '  ' + $j.LastWriteTime + '  ' + [int]($j.Length/1KB) + ' KB') }
    else { Write-Host ('  MISSING cached jar for ' + $m) -ForegroundColor Red }
}

Step '2/5 javap: are the new classes inside those jars'
$javap = Join-Path (Split-Path (Get-Command java).Source) 'javap.exe'
if (-not (Test-Path $javap)) { $javap = 'javap' }
$symbols = @(
    @('chasm-core','api.chasm.registry.ChasmContentHolder'),
    @('chasm-core','api.chasm.registry.ChasmRegistrar'),
    @('chasm-core','api.chasm.memo.ChasmMemo'),
    @('chasm-core','api.chasm.multiblock.ChasmMultiblock'),
    @('chasm-core','api.chasm.effect.ChasmMobEffects'),
    @('chasm-core','api.chasm.particle.ChasmParticles'),
    @('chasm-core','api.chasm.advancement.ChasmAdvancements'),
    @('chasm-core','api.chasm.loot.ChasmLoot'),
    @('chasm-core','api.chasm.item.ChasmProjectileWeaponItem'),
    @('tetra-port','com.example.chasm.tetra.TetraPort'),
    @('tetra-port','com.example.chasm.tetra.TetraEffects'),
    @('tetra-port','com.example.chasm.tetra.TetraWorkbench'),
    @('tetra-port','com.example.chasm.tetra.TetraHoning'),
    @('tetra-port','com.example.chasm.tetra.TetraWorldGen'),
    @('tetra-port','com.example.chasm.tetra.TetraAdvancements'),
    @('tetra-port','com.example.chasm.tetra.TetraConfigActions'),
    @('tetra-port','com.example.chasm.tetra.TetraModularItems'),
    @('tetra-port','com.example.chasm.tetra.ranged.RangedItems'),
    @('tetra-port','com.example.chasm.tetra.TetraToolbeltItems'),
    @('tetra-port','com.example.chasm.tetra.TetraExtraContent')
)
$fail = 0
foreach ($s in $symbols) {
    $mod = $s[0]; $cls = $s[1]
    if (-not $jars.ContainsKey($mod)) { continue }
    $out = & $javap -p -classpath $jars[$mod].FullName $cls 2>&1 | Out-String
    if ($out -match 'Error|not found') { Write-Host ('  [FAIL] ' + $cls + ' not in ' + $mod) -ForegroundColor Red; $fail++ }
    else { Write-Host ('  [PASS] ' + $cls) -ForegroundColor Green }
}

Step '3/5 data entry counts inside the tetra-port jar'
if ($jars.ContainsKey('tetra-port')) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($jars['tetra-port'].FullName)
    $checks = [ordered]@{
        'data/tetra/structure'        = 61
        'data/tetra/worldgen'         = 33
        'data/tetra/recipe'           = 26
        'data/tetra/advancement'      = 78
        'data/tetra/loot_table'       = 127
        'data/tetra/modules'          = 78
        'data/tetra/materials'        = 70
        'data/tetra/schematics'       = 343
        'data/tetra/damage_type'      = 1
        'data/tetra/improvements'     = 147
        'data/tetra/crafting_effects' = 43
    }
    foreach ($k in $checks.Keys) {
        $prefix = $k + '/'
        $n = ($z.Entries | Where-Object { $_.FullName.StartsWith($prefix) }).Count
        $color = if ($n -ge $checks[$k]) { 'Green' } else { 'Red' }
        Write-Host ('  ' + $k.PadRight(30) + ' ' + $n.ToString().PadLeft(4) + '  (expect >= ' + $checks[$k] + ')') -ForegroundColor $color
    }
    $z.Dispose()
}

Step '4/5 test result summary (XML)'
foreach ($mod in @('chasm-core','tetra-port','chasm-example')) {
    $dir = Join-Path $root ($mod + '/build/test-results/test')
    if (-not (Test-Path $dir)) { Write-Host ('  ' + $mod + ': no test results') -ForegroundColor Yellow; continue }
    $t=0;$f=0;$e=0;$s=0;$c=0
    foreach ($x in Get-ChildItem $dir -Filter '*.xml' -ErrorAction SilentlyContinue) {
        $xml = [xml](Get-Content $x.FullName)
        $t += [int]$xml.testsuite.tests; $f += [int]$xml.testsuite.failures; $e += [int]$xml.testsuite.errors; $s += [int]$xml.testsuite.skipped; $c++
    }
    $color = if ($f -eq 0 -and $e -eq 0) { 'Green' } else { 'Red' }
    Write-Host ('  ' + $mod.PadRight(14) + ' classes ' + $c + ' / tests ' + $t + ' / failures ' + $f + ' / errors ' + $e + ' / skipped ' + $s) -ForegroundColor $color
}

Step '5/5 conclusion'
if ($fail -gt 0) { Write-Host ('  ' + $fail + ' symbols missing from the jars the game loads - run refresh-dev.ps1 and re-check') -ForegroundColor Red; exit 1 }
Write-Host 'ALL READY. Launch: .\gradlew.bat :chasm-example:runClient' -ForegroundColor Green