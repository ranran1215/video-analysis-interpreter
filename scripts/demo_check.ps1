param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DemoVideoPath = $(Join-Path (Split-Path -Parent $PSScriptRoot) "video\demo_english_30s.mp4")
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

function Read-HttpResponseContent {
    param($Response)

    if ($null -eq $Response) {
        return ""
    }

    try {
        $stream = $Response.GetResponseStream()
        if ($null -eq $stream) {
            return ""
        }
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Close()
        }
    } finally {
        $Response.Close()
    }
}

function Invoke-JsonRequest {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null
    )

    $request = [System.Net.WebRequest]::Create($Url)
    $request.Method = $Method
    $request.Timeout = 900000
    $request.ReadWriteTimeout = 900000

    if ($null -ne $Body) {
        $jsonBody = $Body | ConvertTo-Json -Depth 20
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($jsonBody)
        $request.ContentType = "application/json; charset=utf-8"
        $request.ContentLength = $bytes.Length
        $requestStream = $request.GetRequestStream()
        try {
            $requestStream.Write($bytes, 0, $bytes.Length)
        } finally {
            $requestStream.Close()
        }
    }

    try {
        $response = $request.GetResponse()
        $statusCode = [int]$response.StatusCode
        $content = Read-HttpResponseContent -Response $response
    } catch [System.Net.WebException] {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) {
            throw
        }

        $statusCode = [int]$webResponse.StatusCode
        $content = Read-HttpResponseContent -Response $webResponse
        $safeContent = Remove-SecretText $content
        throw "HTTP $statusCode`: $safeContent"
    }

    if ($statusCode -lt 200 -or $statusCode -ge 300) {
        $safeContent = Remove-SecretText $content
        throw "HTTP $statusCode`: $safeContent"
    }

    if ([string]::IsNullOrWhiteSpace($content)) {
        return $null
    }
    return $content | ConvertFrom-Json
}

function Remove-SecretText {
    param([string]$Value)

    if ($null -eq $Value) {
        return ""
    }

    return $Value `
        -replace 'sk-[A-Za-z0-9_\-]+', 'sk-***REDACTED***' `
        -replace '(?i)(password=)[^&;,\s]+', '$1***' `
        -replace '(?i)(pwd=)[^&;,\s]+', '$1***'
}

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host "==== $Title ===="
}

function Add-Result {
    param(
        [string]$Name,
        [bool]$Ok,
        [string]$Message
    )

    $script:Results[$Name] = [PSCustomObject]@{
        Ok = $Ok
        Message = $Message
    }
    $prefix = if ($Ok) { "[OK]" } else { "[FAIL]" }
    Write-Host "$prefix ${Name}: $Message"
}

function Get-ResultOk {
    param([string]$Name)

    return $script:Results.Contains($Name) -and $script:Results[$Name].Ok
}

$apiBase = $BaseUrl.TrimEnd("/")
$Results = [ordered]@{}

Write-Section "Backend"
try {
    $ping = Invoke-RestMethod -Method Get -Uri "$apiBase/api/test/ping" -TimeoutSec 30
    Add-Result -Name "backend" -Ok ($ping -eq "pong") -Message "ping=$ping"
} catch {
    Add-Result -Name "backend" -Ok $false -Message (Remove-SecretText $_.Exception.Message)
}

Write-Section "Health"
try {
    $health = Invoke-JsonRequest -Method Get -Url "$apiBase/api/test/health/full"
    Add-Result -Name "health" -Ok ([bool]$health.ok) -Message "overall=$($health.ok)"

    foreach ($name in @("spring-profiles", "database", "LLM_API_KEY", "import-whisperx", "yt-dlp", "ffmpeg")) {
        $check = $health.checks | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if ($check) {
            $prefix = if ($check.ok) { "[OK]" } else { "[FAIL]" }
            Write-Host "$prefix $($check.name): $($check.message)"
        }
    }
} catch {
    Add-Result -Name "health" -Ok $false -Message (Remove-SecretText $_.Exception.Message)
}

Write-Section "LLM"
try {
    $llm = Invoke-JsonRequest -Method Post -Url "$apiBase/api/test/llm" -Body @{
        text = "这是一段 demo check 字幕，用于验证 LLM 是否可以生成总结和高光。"
        language = "zh"
    }
    $message = if ($llm.ok) { "durationMs=$($llm.durationMs)" } else { $llm.errorMessage }
    Add-Result -Name "llm" -Ok ([bool]$llm.ok) -Message (Remove-SecretText $message)
} catch {
    Add-Result -Name "llm" -Ok $false -Message (Remove-SecretText $_.Exception.Message)
}

Write-Section "Subtitle Translate"
try {
    $translation = Invoke-JsonRequest -Method Post -Url "$apiBase/api/test/translate-subtitles" -Body @{
        targetLanguage = "zh"
        segments = @(
            @{
                start = 0.0
                end = 2.5
                text = "Hello everyone, welcome back."
            },
            @{
                start = 2.5
                end = 5.0
                text = "Today we are testing bilingual subtitles."
            }
        )
    }
    $message = if ($translation.ok) {
        "segmentCount=$($translation.segmentCount), durationMs=$($translation.durationMs)"
    } else {
        $translation.errorMessage
    }
    Add-Result -Name "subtitle translate" -Ok ([bool]$translation.ok) -Message (Remove-SecretText $message)
} catch {
    Add-Result -Name "subtitle translate" -Ok $false -Message (Remove-SecretText $_.Exception.Message)
}

Write-Section "Demo Video"
if (Test-Path -LiteralPath $DemoVideoPath) {
    Add-Result -Name "demo video exists" -Ok $true -Message $DemoVideoPath
    try {
        $subtitle = Invoke-JsonRequest -Method Post -Url "$apiBase/api/test/subtitle-script" -Body @{
            videoPath = $DemoVideoPath
            language = "auto"
        }
        $message = if ($subtitle.ok) {
            "language=$($subtitle.language), segmentCount=$($subtitle.segmentCount), aligned=$($subtitle.aligned)"
        } else {
            $subtitle.errorMessage
        }
        Add-Result -Name "subtitle script" -Ok ([bool]$subtitle.ok) -Message (Remove-SecretText $message)
    } catch {
        Add-Result -Name "subtitle script" -Ok $false -Message (Remove-SecretText $_.Exception.Message)
    }
} else {
    Add-Result -Name "demo video exists" -Ok $false -Message "missing: $DemoVideoPath"
}

Write-Section "Demo Readiness"
$ready = (Get-ResultOk "backend") `
    -and (Get-ResultOk "health") `
    -and (Get-ResultOk "llm") `
    -and (Get-ResultOk "subtitle translate") `
    -and (Get-ResultOk "demo video exists") `
    -and (Get-ResultOk "subtitle script")

if ($ready) {
    Write-Host "[READY] 可以打开 test_frontend.html 上传 demo_english_30s.mp4 演示。"
} else {
    Write-Host "[NOT READY] Demo 尚未完全 ready。优先检查 LLM_API_KEY、字幕翻译测试和 demo 视频文件。"
}

if (-not (Get-ResultOk "backend")) {
    exit 1
}
