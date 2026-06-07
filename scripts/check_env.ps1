param()

$ErrorActionPreference = "Continue"
try {
    if ($Host.Name -eq "ConsoleHost") {
        chcp 65001 | Out-Null
    }
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # Continue even if the host does not allow changing encoding.
}

$script:MissingWhisperX = $false
$script:MissingYtDlp = $false
$script:MissingConda = $false

function Write-Ok {
    param([string]$Name, [string]$Message)
    Write-Host "[OK] $Name`: $Message"
}

function Write-Fail {
    param([string]$Name, [string]$Message)
    Write-Host "[FAIL] $Name`: $Message"
}

function Write-Warn {
    param([string]$Name, [string]$Message)
    Write-Host "[WARN] $Name`: $Message"
}

function Get-CommandPath {
    param([string]$Command)
    $cmd = Get-Command $Command -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Invoke-Capture {
    param([string[]]$Command)
    try {
        $output = & $Command[0] @($Command | Select-Object -Skip 1) 2>&1
        return @{
            Ok = ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE)
            Output = ($output | Out-String).Trim()
            ExitCode = $LASTEXITCODE
        }
    } catch {
        return @{
            Ok = $false
            Output = $_.Exception.Message
            ExitCode = -1
        }
    }
}

Write-Host "==== Local Environment Check ===="
Write-Host "PowerShell: $($PSVersionTable.PSVersion)"

$pythonPath = Get-CommandPath "python"
if ($pythonPath) {
    $pythonVersion = Invoke-Capture @("python", "--version")
    Write-Ok "python" "$pythonPath, $($pythonVersion.Output)"
} else {
    Write-Fail "python" "command not found"
}

$pipPath = Get-CommandPath "pip"
if ($pipPath) {
    $pipVersion = Invoke-Capture @("pip", "--version")
    Write-Ok "pip" "$pipPath, $($pipVersion.Output)"
} else {
    Write-Fail "pip" "command not found"
}

if ($pythonPath) {
    $torch = Invoke-Capture @("python", "-c", "import torch; print(torch.__version__); print(torch.cuda.is_available())")
    if ($torch.Ok) {
        $lines = $torch.Output -split "`r?`n"
        $torchVersion = if ($lines.Count -ge 1) { $lines[0] } else { "unknown" }
        $cuda = if ($lines.Count -ge 2) { $lines[1] } else { "unknown" }
        Write-Ok "torch" "$torchVersion, cuda=$cuda"
    } else {
        Write-Fail "torch" "not importable: $($torch.Output)"
    }

    $whisperx = Invoke-Capture @("python", "-c", "import whisperx; print('whisperx ok')")
    if ($whisperx.Ok) {
        Write-Ok "whisperx" "import ok"
    } else {
        $script:MissingWhisperX = $true
        Write-Fail "whisperx" "not installed"
    }
}

$ytDlpPath = Get-CommandPath "yt-dlp"
if ($ytDlpPath) {
    $ytDlpVersion = Invoke-Capture @("yt-dlp", "--version")
    Write-Ok "yt-dlp" "$ytDlpPath, $($ytDlpVersion.Output)"
} elseif ($pythonPath) {
    $ytDlpModule = Invoke-Capture @("python", "-m", "yt_dlp", "--version")
    if ($ytDlpModule.Ok) {
        Write-Warn "yt-dlp" "command not found, but python -m yt_dlp works: $($ytDlpModule.Output)"
    } else {
        $script:MissingYtDlp = $true
        Write-Fail "yt-dlp" "command not found"
    }
} else {
    $script:MissingYtDlp = $true
    Write-Fail "yt-dlp" "command not found"
}

$ffmpegPath = Get-CommandPath "ffmpeg"
if ($ffmpegPath) {
    $ffmpegVersion = Invoke-Capture @("ffmpeg", "-version")
    $firstLine = ($ffmpegVersion.Output -split "`r?`n")[0]
    Write-Ok "ffmpeg" "$ffmpegPath, $firstLine"
} else {
    Write-Fail "ffmpeg" "command not found"
}

$condaPath = Get-CommandPath "conda"
if ($condaPath) {
    $condaVersion = Invoke-Capture @("conda", "--version")
    Write-Ok "conda" "$condaPath, $($condaVersion.Output)"
} else {
    $script:MissingConda = $true
    Write-Warn "conda" "command not found"
}

Write-Host ""
Write-Host "==== Environment Variables ===="
foreach ($name in @(
    "VIDEO_CONDA_ENV",
    "VIDEO_PYTHON_COMMAND",
    "YTDLP_PATH",
    "FFMPEG_PATH",
    "DOWNLOADER_JS_RUNTIMES",
    "DOWNLOADER_SLEEP_REQUESTS_SEC",
    "LLM_API_URL",
    "LLM_MODEL"
)) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    if ([string]::IsNullOrWhiteSpace($value)) {
        Write-Warn $name "not configured"
    } else {
        Write-Ok $name $value
    }
}

foreach ($name in @("DOWNLOADER_COOKIES_FROM_BROWSER", "DOWNLOADER_COOKIES_FILE")) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    if ([string]::IsNullOrWhiteSpace($value)) {
        Write-Warn $name "configured=false"
    } else {
        Write-Ok $name "configured=true"
    }
}

$llmKey = [Environment]::GetEnvironmentVariable("LLM_API_KEY", "Process")
if ([string]::IsNullOrWhiteSpace($llmKey)) {
    Write-Warn "LLM_API_KEY" "configured=false"
} else {
    Write-Ok "LLM_API_KEY" "configured=true"
}

$difyKey = [Environment]::GetEnvironmentVariable("DIFY_API_KEY", "Process")
if ([string]::IsNullOrWhiteSpace($difyKey)) {
    Write-Warn "DIFY_API_KEY_LEGACY" "configured=false"
} else {
    Write-Ok "DIFY_API_KEY_LEGACY" "configured=true"
}

$videoCondaEnv = [Environment]::GetEnvironmentVariable("VIDEO_CONDA_ENV", "Process")
if (-not [string]::IsNullOrWhiteSpace($videoCondaEnv)) {
    Write-Host ""
    Write-Host "==== VIDEO_CONDA_ENV Check ===="
    $condaPython = Join-Path $env:USERPROFILE ".conda\envs\$videoCondaEnv\python.exe"
    if (Test-Path $condaPython) {
        $condaVersion = Invoke-Capture @($condaPython, "--version")
        Write-Ok "conda-python" "$condaPython, $($condaVersion.Output)"

        $condaTorch = Invoke-Capture @($condaPython, "-c", "import torch; print(torch.__version__); print(torch.cuda.is_available())")
        if ($condaTorch.Ok) {
            $lines = $condaTorch.Output -split "`r?`n"
            $torchVersion = if ($lines.Count -ge 1) { $lines[0] } else { "unknown" }
            $cuda = if ($lines.Count -ge 2) { $lines[1] } else { "unknown" }
            Write-Ok "conda-torch" "$torchVersion, cuda=$cuda"
        } else {
            Write-Fail "conda-torch" "not importable: $($condaTorch.Output)"
        }

        $condaWhisperX = Invoke-Capture @($condaPython, "-c", "import whisperx; print('whisperx ok')")
        if ($condaWhisperX.Ok) {
            $script:MissingWhisperX = $false
            Write-Ok "conda-whisperx" "import ok"
        } else {
            Write-Fail "conda-whisperx" "not importable: $($condaWhisperX.Output)"
        }
    } else {
        Write-Fail "conda-python" "not found: $condaPython"
    }
}

Write-Host ""
Write-Host "==== Next Steps ===="
if ($script:MissingWhisperX) {
    Write-Host "- whisperx 缺失：建议运行 .\scripts\setup_env.ps1 -InstallWhisperX"
}
if ($script:MissingYtDlp) {
    Write-Host "- yt-dlp 缺失：建议运行 .\scripts\setup_env.ps1 -InstallYtDlp"
}
if ($script:MissingConda) {
    Write-Host "- conda 不存在：可以使用当前 Python 安装，但推荐使用 conda 环境隔离依赖。"
}
if (-not $script:MissingWhisperX -and -not $script:MissingYtDlp) {
    Write-Host "- 基础依赖看起来可用，可以启动后端并运行 .\scripts\smoke_test.ps1。"
}
