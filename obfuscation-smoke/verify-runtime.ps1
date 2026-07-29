param(
    [ValidateSet("simple", "medium", "heavy")]
    [string[]]$Levels = @("simple", "medium", "heavy"),
    [string]$Java = "C:\Program Files\Java\jdk-17\bin\java.exe"
)

$ErrorActionPreference = "Stop"

$projectDir = $PSScriptRoot
$runtimeDir = Join-Path $projectDir "runtime\paper-1.20.1"
$pluginsDir = Join-Path $runtimeDir "plugins"
$pluginDataDir = Join-Path $pluginsDir "BlinkObfuscationSmoke"
$fixture = Join-Path $projectDir "runtime-fixtures\smoke.yml"
$folderFixture = Join-Path $projectDir "runtime-fixtures\entries\sub\alpha.yml"

if (-not (Test-Path -LiteralPath (Join-Path $runtimeDir "paper.jar"))) {
    throw "Paper runtime is missing: $runtimeDir"
}

foreach ($level in $Levels) {
    $sourceJar = Join-Path $projectDir (
        "build\libs\blink-obfuscation-smoke-1.0.0-all-$level.jar"
    )
    if (-not (Test-Path -LiteralPath $sourceJar)) {
        throw "Artifact is missing: $sourceJar"
    }

    # Keep Blink's downloaded runtime libraries between strength levels. Clearing the
    # whole data directory made an otherwise valid offline rerun fail before Proteus
    # bytecode was exercised.
    if (-not (Test-Path -LiteralPath $pluginDataDir)) {
        New-Item -ItemType Directory -Path $pluginDataDir -Force | Out-Null
    }
    Get-ChildItem -LiteralPath $pluginDataDir -Force |
        Where-Object { $_.Name -ne "libs" } |
        Remove-Item -Recurse -Force
    Copy-Item -LiteralPath $sourceJar `
        -Destination (Join-Path $pluginsDir "BlinkObfuscationSmoke.jar") -Force
    Copy-Item -LiteralPath $fixture `
        -Destination (Join-Path $pluginDataDir "smoke.yml") -Force
    $folderTarget = Join-Path $pluginDataDir "entries\sub"
    New-Item -ItemType Directory -Path $folderTarget -Force | Out-Null
    Copy-Item -LiteralPath $folderFixture `
        -Destination (Join-Path $folderTarget "alpha.yml") -Force

    $port = Get-Random -Minimum 26000 -Maximum 32000
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Java
    $startInfo.Arguments =
        "-Xms256M -Xmx1G -jar paper.jar --nogui --port $port"
    $startInfo.WorkingDirectory = $runtimeDir
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $output = New-Object System.Text.StringBuilder
    $handler = {
        if ($null -ne $EventArgs.Data) {
            [void]$Event.MessageData.AppendLine($EventArgs.Data)
        }
    }
    $stdoutEvent = Register-ObjectEvent -InputObject $process `
        -EventName OutputDataReceived -Action $handler -MessageData $output
    $stderrEvent = Register-ObjectEvent -InputObject $process `
        -EventName ErrorDataReceived -Action $handler -MessageData $output

    try {
        [void]$process.Start()
        $process.BeginOutputReadLine()
        $process.BeginErrorReadLine()

        $deadline = (Get-Date).AddSeconds(180)
        $started = $false
        while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
            Start-Sleep -Milliseconds 500
            if ($output.ToString() -match "Done \([0-9.]+s\)! For help") {
                $started = $true
                break
            }
        }

        if ($started) {
            $process.StandardInput.WriteLine("plugins")
            $process.StandardInput.WriteLine("obfsmoke ping")
            $process.StandardInput.WriteLine("os ping")
            $process.StandardInput.WriteLine("obfsmoke args alpha beta gamma")
            $process.StandardInput.WriteLine("obfsmoke console")
            $process.StandardInput.WriteLine("obfsmoke admin reflect reflected")
            $process.StandardInput.WriteLine("obfsmoke verify")
            $process.StandardInput.Flush()
            Start-Sleep -Seconds 4
        }

        if (-not $process.HasExited) {
            $process.StandardInput.WriteLine("stop")
            $process.StandardInput.Flush()
        }
        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
        }
        $process.WaitForExit()
        Start-Sleep -Milliseconds 500

        # PowerShell event actions can lag behind process exit; Paper's own latest.log
        # is the ordered, flushed source of truth for command and disable assertions.
        $latestLog = Join-Path $runtimeDir "logs\latest.log"
        $text = Get-Content -LiteralPath $latestLog -Raw -Encoding utf8
        $required = @(
            "OBF_SMOKE_LOAD",
            "OBF_SMOKE_ENABLE",
            "OBF_SMOKE_EVENT type=STARTUP",
            "OBF_SMOKE_ACTIVE",
            "OBF_SMOKE_PONG",
            "OBF_SMOKE_ARGS_OK",
            "OBF_SMOKE_CONSOLE_OK",
            "OBF_SMOKE_GROUP_OK",
            "OBF_SMOKE_BLINK_LOG",
            "OBF_SMOKE_VERIFY_OK",
            "OBF_SMOKE_DISABLE"
        )
        $missing = $required | Where-Object { -not $text.Contains($_) }
        $fatalPattern =
            "AbstractMethodError|BootstrapMethodError|VerifyError|" +
            "Could not load|Could not pass event"
        $fatal = $text -match $fatalPattern

        "=== ${level}: started=$started exit=$($process.ExitCode) ==="
        ($text -split "`r?`n") | Where-Object {
            $_ -match "BlinkObfuscationSmoke|OBF_SMOKE|Server Plugins"
        }

        if (-not $started -or $process.ExitCode -ne 0 -or
            $missing.Count -gt 0 -or $fatal) {
            throw "$level failed: missing=$($missing -join ', ') fatal=$fatal"
        }
    }
    finally {
        Unregister-Event -SubscriptionId $stdoutEvent.Id -ErrorAction SilentlyContinue
        Unregister-Event -SubscriptionId $stderrEvent.Id -ErrorAction SilentlyContinue
        $process.Dispose()
    }
}

"ALL_RUNTIME_SMOKE_TESTS_PASSED"
