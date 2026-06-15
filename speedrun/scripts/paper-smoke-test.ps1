param(
    [string]$PaperJar = "..\mcserver\paper-26.1.2-69.jar",
    [string]$PluginJar = "",
    [string]$WorkRoot = "",
    [int]$PortBase = 0,
    [int]$TimeoutSeconds = 90,
    [string]$Java = "java"
)

$ErrorActionPreference = "Stop"

function Resolve-ExistingPath([string]$PathValue, [string]$Name) {
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction SilentlyContinue
    if (-not $resolved) {
        throw "$Name not found: $PathValue"
    }
    return $resolved.Path
}

function Set-YamlScalar([string]$Text, [string]$Key, [string]$Value) {
    return [regex]::Replace($Text, "(?m)^(\s*$([regex]::Escape($Key)):\s*).*$", "`$1$Value", 1)
}

function Set-StartPreScanEnabled([string]$Text, [string]$Value) {
    return [regex]::Replace($Text, "(?ms)(^\s{2}start-pre-scan:\s.*?^\s{4}enabled:\s*).*$", "`$1$Value", 1)
}

function Set-StartPreScanMode([string]$Text, [string]$Value) {
    return [regex]::Replace($Text, "(?ms)(^\s{2}start-pre-scan:\s.*?^\s{4}mode:\s*).*$", "`$1`"$Value`"", 1)
}

function Set-CoordinateDisplayMode([string]$Text, [string]$Value) {
    return [regex]::Replace($Text, '(?ms)(^\s{2}coordinate-display:\s.*?^\s{4}mode:\s*).*$',
            "`$1`"$Value`"", 1)
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return $listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Stop-SmokeServer([int]$Port, [int]$ProcessId) {
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    Wait-Process -Id $ProcessId -Timeout 10 -ErrorAction SilentlyContinue

    try {
        Get-CimInstance Win32_Process -Filter "name = 'java.exe'" |
                Where-Object { $_.CommandLine -like "*paper.jar --port $Port*" } |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    } catch {
        Write-Warning "Could not inspect Java processes for cleanup: $($_.Exception.Message)"
    }
}

if ([string]::IsNullOrWhiteSpace($PluginJar)) {
    $latestJar = Get-ChildItem -LiteralPath "build\libs" -Filter "speedrun-*.jar" |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if (-not $latestJar) {
        throw "No built speedrun jar found under build\libs. Run .\gradlew.bat build first."
    }
    $PluginJar = $latestJar.FullName
}

if ([string]::IsNullOrWhiteSpace($WorkRoot)) {
    $WorkRoot = Join-Path $env:TEMP ("mcspeedrun-paper-smoke-" + (Get-Date -Format "yyyyMMddHHmmss"))
}

$paperJarPath = Resolve-ExistingPath $PaperJar "Paper jar"
$pluginJarPath = Resolve-ExistingPath $PluginJar "Plugin jar"
$sourceConfig = Resolve-ExistingPath "src\main\resources\config.yml" "Source config"
$workRootPath = [System.IO.Path]::GetFullPath($WorkRoot)

if (Test-Path -LiteralPath $workRootPath) {
    Remove-Item -LiteralPath $workRootPath -Recurse -Force
}
New-Item -ItemType Directory -Path $workRootPath | Out-Null

$scenarios = @(
    @{ Name = "normal"; GameMode = "NORMAL"; PreScan = "false"; PreScanMode = "SAFE"; Coordinate = "CONDITIONAL"; Task = "ACTIVE_STAGE" },
    @{ Name = "casual-safe"; GameMode = "CASUAL"; PreScan = "true"; PreScanMode = "SAFE"; Coordinate = "CONDITIONAL"; Task = "ACTIVE_STAGE" },
    @{ Name = "casual-balanced"; GameMode = "CASUAL"; PreScan = "true"; PreScanMode = "BALANCED"; Coordinate = "UNIFIED"; Task = "ALL_STAGES" },
    @{ Name = "casual-aggressive"; GameMode = "CASUAL"; PreScan = "true"; PreScanMode = "AGGRESSIVE"; Coordinate = "SEPARATE"; Task = "ALL_GAME_STAGES" },
    @{ Name = "hardcore"; GameMode = "HARDCORE"; PreScan = "false"; PreScanMode = "SAFE"; Coordinate = "CONDITIONAL"; Task = "ACTIVE_STAGE" }
)

foreach ($scenario in $scenarios) {
    $scenarioDir = Join-Path $workRootPath $scenario.Name
    $pluginsDir = Join-Path $scenarioDir "plugins"
    $pluginDataDir = Join-Path $pluginsDir "Speedrun"
    New-Item -ItemType Directory -Path $pluginsDir | Out-Null
    New-Item -ItemType Directory -Path $pluginDataDir | Out-Null

    Copy-Item -LiteralPath $paperJarPath -Destination (Join-Path $scenarioDir "paper.jar")
    Copy-Item -LiteralPath $pluginJarPath -Destination (Join-Path $pluginsDir ([System.IO.Path]::GetFileName($pluginJarPath)))
    Set-Content -LiteralPath (Join-Path $scenarioDir "eula.txt") -Value "eula=true" -Encoding ASCII
    $port = if ($PortBase -gt 0) { $PortBase++ } else { Get-FreePort }
    Set-Content -LiteralPath (Join-Path $scenarioDir "server.properties") -Value @(
        "online-mode=false",
        "server-port=$port",
        "enable-command-block=false",
        "view-distance=2",
        "simulation-distance=2"
    ) -Encoding ASCII

    $configText = Get-Content -Raw -LiteralPath $sourceConfig
    $configText = Set-YamlScalar $configText "gamemode" "`"$($scenario.GameMode)`""
    $configText = Set-StartPreScanEnabled $configText $scenario.PreScan
    $configText = Set-StartPreScanMode $configText $scenario.PreScanMode
    $configText = Set-YamlScalar $configText "task-display-mode" "`"$($scenario.Task)`""
    $configText = Set-CoordinateDisplayMode $configText $scenario.Coordinate
    Set-Content -LiteralPath (Join-Path $pluginDataDir "config.yml") -Value $configText -Encoding UTF8

    $proc = Start-Process -FilePath $Java -ArgumentList @("-jar", "paper.jar", "--port", "$port", "nogui") `
            -WorkingDirectory $scenarioDir -PassThru -WindowStyle Hidden
    try {
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $logPath = Join-Path $scenarioDir "logs\latest.log"
        $enabled = $false
        while ((Get-Date) -lt $deadline) {
            if (Test-Path -LiteralPath $logPath) {
                $rawLog = Get-Content -Raw -LiteralPath $logPath
                $log = if ($null -eq $rawLog) { "" } else { [string]$rawLog }
                if ($log.Contains("Speedrun plugin has been enabled.")) {
                    $enabled = $true
                    break
                }
                if ($log.Contains("Could not load") -or $log.Contains("Could not enable") -or $log.Contains("Error occurred while enabling Speedrun")) {
                    throw "Speedrun failed during scenario '$($scenario.Name)'. See $logPath"
                }
                if ($log.Contains("FAILED TO BIND TO PORT")) {
                    throw "Paper failed to bind port $port during scenario '$($scenario.Name)'. See $logPath"
                }
            }
            Start-Sleep -Milliseconds 500
            if ($proc.HasExited) {
                break
            }
        }
        if (-not $enabled) {
            throw "Timed out waiting for Speedrun enable in scenario '$($scenario.Name)'. See $logPath"
        }

        $rawFinalLog = Get-Content -Raw -LiteralPath $logPath
        $finalLog = if ($null -eq $rawFinalLog) { "" } else { [string]$rawFinalLog }
        if ($finalLog.Contains("Could not pass event") -or $finalLog.Contains("[Speedrun] Error")) {
            throw "Speedrun runtime error detected in scenario '$($scenario.Name)'. See $logPath"
        }
        Write-Host "PASS $($scenario.Name) on port $port"
    } finally {
        Stop-SmokeServer -Port $port -ProcessId $proc.Id
    }
}

Write-Host "Paper smoke scenarios passed. Work root: $workRootPath"
