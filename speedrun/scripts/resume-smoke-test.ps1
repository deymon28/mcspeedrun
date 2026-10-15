param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [string]$PluginJar = "",
    [int]$TimeoutSeconds = 180,
    [string]$Java = "java"
)

# Checks that an active run survives a server restart (settings.resume-after-restart):
# boot 1 starts a run and stops the server, boot 2 must restore it, and a final /run stop must
# delete the saved state. Also checks that no temporary-block journal is left after clean stops.

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot "build"))
$serverPath = (Resolve-Path -LiteralPath $ServerJar).Path
if (-not $PluginJar) {
    $PluginJar = (Get-ChildItem -LiteralPath (Join-Path $buildRoot "libs") -Filter "speedrun-*.jar" |
        Where-Object { $_.Name -notlike "*-plain.jar" -and $_.Name -notlike "*-shaded.jar" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
$pluginPath = (Resolve-Path -LiteralPath $PluginJar).Path
$scenario = Join-Path $buildRoot ("resume-smoke-" + [guid]::NewGuid().ToString("N"))
$dataDir = Join-Path $scenario "plugins/Speedrun"
New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
Copy-Item -LiteralPath $serverPath -Destination (Join-Path $scenario "server.jar")
Copy-Item -LiteralPath $pluginPath -Destination (Join-Path $scenario "plugins/Speedrun.jar")
# The operator running this test must already have accepted the Minecraft EULA.
Set-Content -LiteralPath (Join-Path $scenario "eula.txt") -Value "eula=true" -Encoding ascii

function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

Set-Content -LiteralPath (Join-Path $scenario "server.properties") -Encoding ascii -Value @(
    "server-ip=127.0.0.1", "server-port=$(Get-FreePort)", "online-mode=false", "enable-rcon=false",
    "enable-query=false", "view-distance=2", "simulation-distance=2", "generate-structures=false", "max-players=2"
)
$config = [IO.File]::ReadAllText((Join-Path $projectRoot "src/main/resources/config.yml"), [Text.Encoding]::UTF8)
$config = $config.Replace("start-on-first-join: true", "start-on-first-join: false")
$config = $config.Replace("port: 8765", "port: $(Get-FreePort)")
[IO.File]::WriteAllText((Join-Path $dataDir "config.yml"), $config, [Text.UTF8Encoding]::new($false))

function Read-ServerLog([string]$Path) {
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $reader = [IO.StreamReader]::new($stream)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } catch [IO.IOException] { return "" }
}

function Invoke-Boot([string[]]$Commands, [int]$RunSeconds) {
    # The previous boot's latest.log would satisfy the readiness check before the server is up.
    Remove-Item -LiteralPath (Join-Path $scenario "logs/latest.log") -ErrorAction SilentlyContinue
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Java
    $startInfo.Arguments = "-Xms256M -Xmx1024M -jar server.jar nogui"
    $startInfo.WorkingDirectory = $scenario
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($startInfo)
    $process.StandardInput.WriteLine("")
    $process.StandardInput.Flush()
    $null = $process.StandardOutput.ReadToEndAsync()
    $null = $process.StandardError.ReadToEndAsync()
    $logFile = Join-Path $scenario "logs/latest.log"
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            if ($process.HasExited) { throw "Server exited during startup." }
            $log = Read-ServerLog $logFile
            if ($log.Contains('Speedrun plugin has been enabled.') -and $log -match 'Done \([0-9.,]+s\)') { break }
            Start-Sleep -Milliseconds 250
        }
        Start-Sleep -Seconds $RunSeconds
        foreach ($command in $Commands + @("stop")) {
            $process.StandardInput.WriteLine($command)
            $process.StandardInput.Flush()
            Start-Sleep -Milliseconds 500
        }
        if (-not $process.WaitForExit(45000)) { throw "Server did not stop." }
        $log = Read-ServerLog $logFile
        if ($log -match 'Could not pass event.*Speedrun|Error occurred while (enabling|disabling) Speedrun|\[Speedrun\].*(ERROR|SEVERE)|Exception in thread') {
            throw "Plugin runtime error. Inspect $logFile."
        }
        return $log
    } finally {
        if (-not $process.HasExited) { $process.Kill() }
    }
}

$state = Join-Path $dataDir "run-state.yml"
$journal = Join-Path $dataDir "world-changes.yml"

$runStarted = [regex]::Escape("The speedrun has begun!")
$first = Invoke-Boot @("run status") 5
if ($first -notmatch $runStarted) { throw "Boot 1 did not start a run." }
if (-not (Test-Path -LiteralPath $state)) { throw "Boot 1 did not save run-state.yml on shutdown." }
if (Test-Path -LiteralPath $journal) { throw "world-changes.yml remained after a clean shutdown." }
Write-Output "PASS boot 1: run started and was saved on shutdown"

$second = Invoke-Boot @("run status", "run stop") 3
if (-not $second.Contains("Restored the run saved at")) { throw "Boot 2 did not restore the saved run." }
if ($second -match $runStarted) { throw "Boot 2 started a new run instead of restoring." }
if (Test-Path -LiteralPath $state) { throw "run-state.yml was not deleted after /run stop." }
Write-Output "PASS boot 2: run restored, /run stop removed the saved state"
Write-Output "Scenario folder: $scenario"
