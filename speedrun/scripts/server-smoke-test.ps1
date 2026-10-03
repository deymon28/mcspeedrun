param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [string]$PluginJar = "",
    [string]$WorkRoot = "",
    [string]$RuntimeSeed = "",
    [int]$PortBase = 0,
    [string[]]$Profiles = @("normal", "casual-safe", "casual-balanced", "casual-aggressive", "casual-locate", "hardcore"),
    [int]$TimeoutSeconds = 180,
    [string]$Java = "java"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot "build"))
$serverPath = (Resolve-Path -LiteralPath $ServerJar).Path
if (-not $PluginJar) {
    $PluginJar = (Get-ChildItem -LiteralPath (Join-Path $buildRoot "libs") -Filter "speedrun-*.jar" |
        Where-Object Name -NotLike "*-plain.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
$pluginPath = (Resolve-Path -LiteralPath $PluginJar).Path
if (-not $WorkRoot) { $WorkRoot = Join-Path $buildRoot ("smoke-" + [guid]::NewGuid().ToString("N")) }
$workPath = [IO.Path]::GetFullPath($WorkRoot)
if (-not $workPath.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Smoke work directory must be inside this project's build directory."
}
if (Test-Path -LiteralPath $workPath) { throw "Smoke work directory already exists; choose a new path." }
New-Item -ItemType Directory -Path $workPath -Force | Out-Null

function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Read-ServerLog([string]$Path) {
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $reader = [IO.StreamReader]::new($stream)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } catch [IO.IOException] { return "" }
}

function Wait-ServerExit([Diagnostics.Process]$Process, [int]$Seconds) {
    $wait = [Diagnostics.Stopwatch]::StartNew()
    do {
        $Process.Refresh()
        if ($Process.HasExited) { return $true }
        Start-Sleep -Milliseconds 100
    } while ($wait.Elapsed.TotalSeconds -lt $Seconds)
    return $false
}

function Set-YamlValue([string]$Text, [string]$Path, [string]$Value) {
    $lines = $Text -split "\r?\n"
    $parents = [Collections.Generic.List[object]]::new()
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -notmatch '^( *)([A-Za-z0-9_-]+):(?:\s.*)?$') { continue }
        $indent = $Matches[1]
        $key = $Matches[2]
        while ($parents.Count -gt 0 -and $parents[$parents.Count - 1].Indent -ge $indent.Length) {
            $parents.RemoveAt($parents.Count - 1)
        }
        $keys = @($parents | ForEach-Object Key) + @($key)
        if (($keys -join '.') -eq $Path) {
            $lines[$i] = "${indent}${key}: $Value"
            return $lines -join "`n"
        }
        $parents.Add(@{ Key = $key; Indent = $indent.Length })
    }
    throw "Configuration path not found: $Path"
}

$sourceConfig = Get-Content -LiteralPath (Join-Path $projectRoot "src/main/resources/config.yml") -Raw
$results = @()
foreach ($profile in $Profiles) {
    if ($profile -notmatch '^(normal|hardcore|casual-(safe|balanced|aggressive|locate))$') {
        throw "Unknown smoke profile: $profile"
    }
    $scenario = Join-Path $workPath $profile
    $dataDir = Join-Path $scenario "plugins/Speedrun"
    New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
    Copy-Item -LiteralPath $serverPath -Destination (Join-Path $scenario "server.jar")
    Copy-Item -LiteralPath $pluginPath -Destination (Join-Path $scenario "plugins/Speedrun.jar")
    if ($RuntimeSeed) {
        foreach ($cache in @("libraries", "versions", "cache")) {
            $sourceCache = Join-Path $RuntimeSeed $cache
            if (Test-Path -LiteralPath $sourceCache) {
                Copy-Item -LiteralPath $sourceCache -Destination $scenario -Recurse
            }
        }
    }
    # The operator running this test must already have accepted the Minecraft EULA.
    Set-Content -LiteralPath (Join-Path $scenario "eula.txt") -Value "eula=true" -Encoding ascii
    $serverPort = if ($PortBase -gt 0) { $PortBase++ } else { Get-FreePort }
    $webPort = Get-FreePort
    Set-Content -LiteralPath (Join-Path $scenario "server.properties") -Encoding ascii -Value @(
        "server-ip=127.0.0.1", "server-port=$serverPort", "online-mode=false", "enable-rcon=false",
        "enable-query=false", "enable-command-block=false", "view-distance=2", "simulation-distance=2",
        "level-type=minecraft:normal", "generate-structures=false", "max-players=2", "spawn-protection=0"
    )
    $mode = if ($profile.StartsWith("casual-")) { "CASUAL" } else { $profile.ToUpperInvariant() }
    $config = Set-YamlValue $sourceConfig "settings.gamemode" $mode
    $config = Set-YamlValue $config "web-config.port" $webPort
    $config = Set-YamlValue $config "casual.start-pre-scan.enabled" $(if ($mode -eq "CASUAL") { "true" } else { "false" })
    if ($mode -eq "CASUAL") { $config = Set-YamlValue $config "casual.start-pre-scan.mode" $profile.Substring(7).ToUpperInvariant() }
    [IO.File]::WriteAllText((Join-Path $dataDir "config.yml"), $config, [Text.UTF8Encoding]::new($false))

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Java
    $startInfo.Arguments = "-Xms256M -Xmx1024M -jar server.jar nogui"
    $startInfo.WorkingDirectory = $scenario
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $watch = [Diagnostics.Stopwatch]::StartNew()
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $logFile = Join-Path $scenario "logs/latest.log"
    try {
        $ready = $false
        while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds -and -not $process.HasExited) {
            if (Test-Path -LiteralPath $logFile) {
                $log = Read-ServerLog $logFile
                if ($log -match 'Error occurred while (enabling|disabling) Speedrun|Could not (load|enable).*Speedrun') {
                    throw "Plugin startup failed in $profile. Inspect $logFile."
                }
                if ($log.Contains('Speedrun plugin has been enabled.') -and $log -match 'Done \([0-9.,]+s\)') { $ready = $true; break }
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $ready) { throw "Server did not become ready in $profile. Inspect $scenario." }
        $startupSeconds = [Math]::Round($watch.Elapsed.TotalSeconds, 2)
        try {
            Invoke-WebRequest -Uri "http://127.0.0.1:$webPort/api/status" -UseBasicParsing -TimeoutSec 10 | Out-Null
            throw "Unauthenticated web API unexpectedly succeeded."
        } catch {
            if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 403) { throw }
        }
        $page = Invoke-WebRequest -Uri "http://127.0.0.1:$webPort/" -UseBasicParsing -TimeoutSec 10
        if ($page.StatusCode -ne 200 -or -not $page.Content.Contains('Speedrun')) { throw "Web editor failed to load." }
        $process.StandardInput.WriteLine("stop")
        $process.StandardInput.Flush()
        if (-not (Wait-ServerExit $process 45)) { throw "Server did not stop gracefully in $profile." }
        if ($process.ExitCode -ne 0) { throw "Server exited with code $($process.ExitCode) in $profile." }
        $finalLog = Read-ServerLog $logFile
        if (-not $finalLog.Contains('Speedrun plugin has been disabled.')) { throw "Plugin shutdown marker missing in $profile." }
        if ($finalLog -match 'Could not pass event.*Speedrun|Error occurred while (enabling|disabling) Speedrun|\[Speedrun\].*(ERROR|SEVERE)|Exception in thread') {
            throw "Plugin runtime error in $profile. Inspect $logFile."
        }
        $results += [pscustomobject]@{ Profile = $profile; StartupSeconds = $startupSeconds; ExitCode = $process.ExitCode; Result = "PASS" }
        Write-Output "PASS ${profile}: startup, web authentication, static UI, graceful shutdown ($startupSeconds s)"
    } finally {
        if (-not $process.HasExited) {
            try { $process.StandardInput.WriteLine("stop"); $process.StandardInput.Flush() } catch {}
            if (-not (Wait-ServerExit $process 15)) { $process.Kill(); [void](Wait-ServerExit $process 5) }
        }
        # Do not echo raw process output: administrator access links may be sensitive.
        $safeOutput = ($stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()) -replace '(?i)(token=)[^\s"<>]+', '$1[redacted]'
        [IO.File]::WriteAllText((Join-Path $scenario "process-output.txt"), $safeOutput)
        $process.Dispose()
    }
}
$results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $workPath "results.json") -Encoding utf8
Write-Output "Smoke results: $workPath"
