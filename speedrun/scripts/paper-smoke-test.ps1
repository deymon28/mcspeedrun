param(
    [string]$PaperJar = "../mcserver/paper-26.1.2-69.jar",
    [string]$PluginJar = "",
    [string]$WorkRoot = "",
    [int]$PortBase = 0,
    [int]$TimeoutSeconds = 180,
    [string]$Java = "java",
    [string]$RuntimeSeed = ""
)

# Preserve the Paper entry point while sharing safe lifecycle checks across cores.
$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "server-smoke-test.ps1") -ServerJar $PaperJar `
    -PluginJar $PluginJar -WorkRoot $WorkRoot -PortBase $PortBase `
    -TimeoutSeconds $TimeoutSeconds -Java $Java -RuntimeSeed $RuntimeSeed
