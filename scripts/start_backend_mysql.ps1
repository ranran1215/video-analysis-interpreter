param(
    [string]$MysqlUrl = $(if ($env:MYSQL_URL) { $env:MYSQL_URL } else { "jdbc:mysql://localhost:3306/video_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" }),
    [string]$MysqlUsername = $(if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" }),
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
    [switch]$NoPromptMysqlPassword,
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
    # Continue if the host does not allow changing encoding.
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

function Read-SecretToEnvIfNeeded {
    param(
        [string]$EnvName,
        [string]$Prompt,
        [switch]$SkipPrompt
    )

    if ((Get-Item "Env:$EnvName" -ErrorAction SilentlyContinue) -or $SkipPrompt) {
        return
    }

    Write-Host "$EnvName is not set. Paste the value when prompted; input will not be displayed."
    $secureValue = Read-Host $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        $plainValue = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }

    if (-not [string]::IsNullOrWhiteSpace($plainValue)) {
        Set-Item "Env:$EnvName" $plainValue
    }
}

function Format-SafeJdbcUrl {
    param([string]$JdbcUrl)

    if ([string]::IsNullOrWhiteSpace($JdbcUrl)) {
        return ""
    }

    return $JdbcUrl `
        -replace '(?i)(password=)[^&;]+', '$1***' `
        -replace '(?i)(pwd=)[^&;]+', '$1***'
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

Read-SecretToEnvIfNeeded -EnvName "MYSQL_PASSWORD" -Prompt "MYSQL_PASSWORD" -SkipPrompt:$NoPromptMysqlPassword
Read-SecretToEnvIfNeeded -EnvName "LLM_API_KEY" -Prompt "LLM_API_KEY" -SkipPrompt:$NoPromptKey

$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:MYSQL_URL = $MysqlUrl
$env:MYSQL_USERNAME = $MysqlUsername

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
Write-Host "==== Backend Environment (MySQL) ===="
Write-Host "SPRING_PROFILES_ACTIVE=$env:SPRING_PROFILES_ACTIVE"
Write-Host "MYSQL_URL=$(Format-SafeJdbcUrl $env:MYSQL_URL)"
Write-Host "MYSQL_USERNAME=$env:MYSQL_USERNAME"
Write-Host "MYSQL_PASSWORD configured=$(-not [string]::IsNullOrWhiteSpace($env:MYSQL_PASSWORD))"
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
Write-Host "Starting Spring Boot with MySQL profile. Keep this window open while testing."
Write-Host "If startup fails with MySQL Access denied or unknown database, run .\scripts\verify_mysql.ps1 first."
Write-Host ""

Set-Location -LiteralPath $springBootDir
mvn spring-boot:run
$mvnExitCode = $LASTEXITCODE
if ($mvnExitCode -ne 0) {
    Write-Warning "Spring Boot exited with code $mvnExitCode. If the log contains MySQL Access denied, unknown database, or connection errors, run .\scripts\verify_mysql.ps1 to verify credentials and initialize the database."
}
exit $mvnExitCode
