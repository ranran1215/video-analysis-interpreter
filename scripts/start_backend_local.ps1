param(
    [string]$LlmApiUrl = $(if ($env:LLM_API_URL) { $env:LLM_API_URL } else { "https://fast.smartaipro.cn/v1/chat/completions" }),
    [string]$LlmModel = $(if ($env:LLM_MODEL) { $env:LLM_MODEL } else { "gpt-4o-mini" }),
    [string]$WhisperModel = $(if ($env:WHISPER_MODEL) { $env:WHISPER_MODEL } else { "tiny" }),
    [string]$WhisperDevice = $(if ($env:WHISPER_DEVICE) { $env:WHISPER_DEVICE } else { "cuda" }),
    [string]$WhisperComputeType = $(if ($env:WHISPER_COMPUTE_TYPE) { $env:WHISPER_COMPUTE_TYPE } else { "float16" }),
    [int]$WhisperBatchSize = $(if ($env:WHISPER_BATCH_SIZE) { [int]$env:WHISPER_BATCH_SIZE } else { 16 }),
    [string]$DownloaderCookiesFromBrowser = $(if ($env:DOWNLOADER_COOKIES_FROM_BROWSER) { $env:DOWNLOADER_COOKIES_FROM_BROWSER } else { "" }),
    [string]$DownloaderCookiesFile = $(if ($env:DOWNLOADER_COOKIES_FILE) { $env:DOWNLOADER_COOKIES_FILE } else { "" }),
    [string]$DownloaderJsRuntimes = $(if ($env:DOWNLOADER_JS_RUNTIMES) { $env:DOWNLOADER_JS_RUNTIMES } elseif (Get-Command node -ErrorAction SilentlyContinue) { "node" } else { "" }),
    [switch]$EnableAlign,
    [switch]$NoPromptKey,
    [switch]$NoStopExisting
)

$ErrorActionPreference = "Stop"

try {
    if ($Host.Name -eq "ConsoleHost") {
        chcp 65001 | Out-Null
    }
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # Continue even if the host does not allow changing encoding.
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$springBootDir = Join-Path $projectRoot "spring_boot"
$pythonExe = Join-Path $env:USERPROFILE ".conda\envs\whisperx_env\python.exe"
$ytDlpExe = Join-Path $env:USERPROFILE ".conda\envs\whisperx_env\Scripts\yt-dlp.exe"
$ffmpegExe = Join-Path $env:USERPROFILE ".conda\envs\whisperx_env\Library\bin\ffmpeg.exe"

function Stop-ExistingBackend {
    $escapedSpringDir = [WildcardPattern]::Escape($springBootDir)
    $targets = Get-CimInstance Win32_Process | Where-Object {
        ($_.Name -in @("java.exe", "cmd.exe")) -and
        ($_.CommandLine -like "*$escapedSpringDir*") -and
        (($_.CommandLine -like "*spring-boot:run*") -or ($_.CommandLine -like "*VideoAnalysisApplication*"))
    }

    foreach ($proc in $targets) {
        Write-Host "Stopping existing backend process PID=$($proc.ProcessId) ..."
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    }

    if ($targets.Count -gt 0) {
        Start-Sleep -Seconds 3
    }
}

function Read-LlmApiKeyIfNeeded {
    if ($env:LLM_API_KEY -or $NoPromptKey) {
        return
    }

    Write-Host "LLM_API_KEY is not set. Paste the key when prompted; input will not be displayed."
    $secureKey = Read-Host "LLM_API_KEY" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }

    if ([string]::IsNullOrWhiteSpace($plainKey)) {
        Write-Warning "LLM_API_KEY is still empty. LLM smoke test will fail until a key is configured."
    } else {
        $env:LLM_API_KEY = $plainKey
    }
}

if (-not (Test-Path -LiteralPath $pythonExe)) {
    throw "Python not found: $pythonExe"
}

if (-not (Test-Path -LiteralPath $ytDlpExe)) {
    throw "yt-dlp not found: $ytDlpExe"
}

if (-not $NoStopExisting) {
    Stop-ExistingBackend
}

Read-LlmApiKeyIfNeeded

$env:VIDEO_CONDA_ENV = ""
$env:VIDEO_PYTHON_COMMAND = $pythonExe
$env:WHISPER_MODEL = $WhisperModel
$env:WHISPER_DEVICE = $WhisperDevice
$env:WHISPER_COMPUTE_TYPE = $WhisperComputeType
$env:WHISPER_BATCH_SIZE = "$WhisperBatchSize"
$env:WHISPER_ENABLE_ALIGN = if ($EnableAlign) { "true" } else { "false" }
$env:WHISPER_ALIGN_FALLBACK = "true"
$env:YTDLP_PATH = $ytDlpExe
if (Test-Path -LiteralPath $ffmpegExe) {
    $env:FFMPEG_PATH = $ffmpegExe
} else {
    $env:FFMPEG_PATH = ""
}
$env:DOWNLOADER_COOKIES_FROM_BROWSER = $DownloaderCookiesFromBrowser
$env:DOWNLOADER_COOKIES_FILE = $DownloaderCookiesFile
$env:DOWNLOADER_JS_RUNTIMES = $DownloaderJsRuntimes
$env:HF_HOME = Join-Path $projectRoot ".cache\huggingface"
$env:HUGGINGFACE_HUB_CACHE = Join-Path $projectRoot ".cache\huggingface\hub"
$env:TORCH_HOME = Join-Path $projectRoot ".cache\torch"
$env:XDG_CACHE_HOME = Join-Path $projectRoot ".cache"
$env:LLM_API_URL = $LlmApiUrl
$env:LLM_MODEL = $LlmModel

Write-Host ""
Write-Host "==== Backend Environment ===="
Write-Host "VIDEO_PYTHON_COMMAND=$env:VIDEO_PYTHON_COMMAND"
Write-Host "YTDLP_PATH=$env:YTDLP_PATH"
Write-Host "FFMPEG_PATH=$env:FFMPEG_PATH"
Write-Host "DOWNLOADER_JS_RUNTIMES=$env:DOWNLOADER_JS_RUNTIMES"
Write-Host "DOWNLOADER_COOKIES_FROM_BROWSER configured=$(-not [string]::IsNullOrWhiteSpace($env:DOWNLOADER_COOKIES_FROM_BROWSER))"
Write-Host "DOWNLOADER_COOKIES_FILE configured=$(-not [string]::IsNullOrWhiteSpace($env:DOWNLOADER_COOKIES_FILE))"
Write-Host "WHISPER_MODEL=$env:WHISPER_MODEL"
Write-Host "WHISPER_DEVICE=$env:WHISPER_DEVICE"
Write-Host "WHISPER_COMPUTE_TYPE=$env:WHISPER_COMPUTE_TYPE"
Write-Host "WHISPER_ENABLE_ALIGN=$env:WHISPER_ENABLE_ALIGN"
Write-Host "LLM_API_URL=$env:LLM_API_URL"
Write-Host "LLM_MODEL=$env:LLM_MODEL"
Write-Host "LLM_API_KEY configured=$(-not [string]::IsNullOrWhiteSpace($env:LLM_API_KEY))"
Write-Host ""
Write-Host "Starting Spring Boot. Keep this window open while testing."
Write-Host ""

Set-Location -LiteralPath $springBootDir
mvn spring-boot:run
