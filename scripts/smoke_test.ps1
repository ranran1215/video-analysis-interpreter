param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$VideoPath,
    [switch]$LlmTest,
    [switch]$SubtitleTranslateTest,
    [switch]$DifyTest,
    [string]$VideoUrl,
    [string]$VideoUrlCacheTest
)

$ErrorActionPreference = "Stop"
try {
    if ($Host.Name -eq "ConsoleHost") {
        chcp 65001 | Out-Null
    }
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # Older hosts may not allow changing console encoding; continue anyway.
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
        $safeContent = $content -replace 'sk-[A-Za-z0-9_\-]+', 'sk-***REDACTED***'
        throw "HTTP $statusCode`: $safeContent"
    }

    if ($statusCode -lt 200 -or $statusCode -ge 300) {
        $safeContent = $content -replace 'sk-[A-Za-z0-9_\-]+', 'sk-***REDACTED***'
        throw "HTTP $statusCode`: $safeContent"
    }

    if ([string]::IsNullOrWhiteSpace($content)) {
        return $null
    }
    return $content | ConvertFrom-Json
}

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host "==== $Title ===="
}

function Write-CheckResult {
    param($Check)
    $prefix = if ($Check.ok) { "[OK]" } else { "[FAIL]" }
    Write-Host "$prefix $($Check.name): $($Check.message)"
}

function Write-DependencyHint {
    param($Health)
    if (-not $Health -or -not $Health.checks) {
        return
    }

    $missing = @()
    foreach ($check in $Health.checks) {
        if (-not $check.ok -and ($check.name -eq "import-whisperx" -or $check.name -eq "yt-dlp")) {
            $missing += $check.name
        }
    }

    if ($missing.Count -gt 0) {
        Write-Host ""
        Write-Host "==== Dependency Hint ===="
        Write-Host "检测到依赖缺失: $($missing -join ', ')"
        Write-Host "请先运行 .\scripts\check_env.ps1 查看本机环境，或运行 .\scripts\setup_env.ps1 -InstallAll 安装 whisperx/yt-dlp。"
        Write-Host "smoke_test.ps1 不会自动安装依赖。"
    }
}

function Wait-VideoTask {
    param(
        [long]$VideoId,
        [int]$TimeoutSec = 1800
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $analysis = Invoke-JsonRequest -Method Get -Url "$apiBase/api/video/analysis/$VideoId"
        Write-Host ("status: {0}, stage: {1}" -f $analysis.status, $analysis.stage)

        if ($analysis.status -eq "completed" -or $analysis.status -eq "failed") {
            return $analysis
        }

        Start-Sleep -Seconds 3
    }

    throw "等待视频任务超时: videoId=$VideoId"
}

function Invoke-UploadUrl {
    param([string]$Url)

    return Invoke-JsonRequest -Method Post -Url "$apiBase/api/video/upload-url" -Body @{
        url = $Url
        language = "auto"
    }
}

function Get-ElapsedMs {
    param([datetime]$StartTime)
    return [int64]((Get-Date) - $StartTime).TotalMilliseconds
}

function Format-SummaryValue {
    param($Value)

    if ($null -eq $Value) {
        return "[SKIP]"
    }
    if ($Value) {
        return "[OK]"
    }
    return "[FAIL]"
}

function Write-SmokeSummary {
    param($Summary)

    Write-Host ""
    Write-Host "==== Summary ===="
    Write-Host "$(Format-SummaryValue $Summary.backend) backend ok"
    Write-Host "$(Format-SummaryValue $Summary.health) health ok"
    Write-Host "$(Format-SummaryValue $Summary.llm) llm ok"
    Write-Host "$(Format-SummaryValue $Summary.subtitleTranslate) subtitle translate ok"
    Write-Host "$(Format-SummaryValue $Summary.subtitleScript) subtitle script ok"
    Write-Host "$(Format-SummaryValue $Summary.downloader) downloader ok"
    Write-Host "$(Format-SummaryValue $Summary.urlCache) url cache ok"
}

$apiBase = $BaseUrl.TrimEnd("/")
$summary = [ordered]@{
    backend = $null
    health = $null
    llm = $null
    subtitleTranslate = $null
    subtitleScript = $null
    downloader = $null
    urlCache = $null
}

try {
    Write-Section "Ping"
    $ping = Invoke-RestMethod -Method Get -Uri "$apiBase/api/test/ping" -TimeoutSec 30
    Write-Host "ping: $ping"
    $summary.backend = ($ping -eq "pong")

    Write-Section "Full Health"
    $health = Invoke-JsonRequest -Method Get -Url "$apiBase/api/test/health/full"
    Write-Host "overall ok: $($health.ok)"
    $summary.health = [bool]$health.ok
    foreach ($check in $health.checks) {
        Write-CheckResult $check
    }

    if ($VideoPath) {
        Write-Section "Subtitle Script"
        $subtitle = Invoke-JsonRequest -Method Post -Url "$apiBase/api/test/subtitle-script" -Body @{
            videoPath = $VideoPath
            language = "auto"
        }
        Write-Host "ok: $($subtitle.ok)"
        Write-Host "language: $($subtitle.language)"
        Write-Host "aligned: $($subtitle.aligned)"
        Write-Host "segmentCount: $($subtitle.segmentCount)"
        Write-Host "alignDurationMs: $($subtitle.alignDurationMs)"
        Write-Host "subtitleDurationMs: $($subtitle.subtitleDurationMs)"
        if (-not $subtitle.ok) {
            Write-Host "errorMessage: $($subtitle.errorMessage)"
        } elseif ($subtitle.firstSegments) {
            Write-Host "firstSegments:"
            foreach ($segment in $subtitle.firstSegments) {
                Write-Host ("  [{0}-{1}] {2}" -f $segment.start, $segment.end, $segment.text)
            }
        }
        $summary.subtitleScript = [bool]$subtitle.ok
    }

    if ($DifyTest) {
        Write-Section "Dify (Legacy)"
        $dify = Invoke-JsonRequest -Method Post -Url "$apiBase/api/test/dify" -Body @{
            text = "这是一段 smoke test 字幕，用于验证 legacy Dify 工作流是否可调用。"
            language = "zh"
        }
        Write-Host "ok: $($dify.ok)"
        Write-Host "durationMs: $($dify.durationMs)"
        if (-not $dify.ok) {
            Write-Host "errorMessage: $($dify.errorMessage)"
        } else {
            Write-Host "rawResult: $($dify.rawResult)"
        }
    }

    if ($LlmTest) {
        Write-Section "LLM"
        $llm = Invoke-JsonRequest -Method Post -Url "$apiBase/api/test/llm" -Body @{
            text = "这是一段 smoke test 字幕，用于验证 OpenAI-compatible LLM 是否可调用。请生成简短总结和高光。"
            language = "zh"
        }
        Write-Host "ok: $($llm.ok)"
        Write-Host "durationMs: $($llm.durationMs)"
        if (-not $llm.ok) {
            Write-Host "errorMessage: $($llm.errorMessage)"
        } else {
            Write-Host "rawResult: $($llm.rawResult)"
        }
        $summary.llm = [bool]$llm.ok
    }

    if ($SubtitleTranslateTest) {
        Write-Section "Subtitle Translate"
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
                    text = "Today we are talking about artificial intelligence."
                },
                @{
                    start = 5.0
                    end = 8.0
                    text = "Keep the translation short enough for subtitles."
                }
            )
        }
        Write-Host "ok: $($translation.ok)"
        Write-Host "durationMs: $($translation.durationMs)"
        Write-Host "segmentCount: $($translation.segmentCount)"
        if (-not $translation.ok) {
            Write-Host "errorMessage: $($translation.errorMessage)"
        } elseif ($translation.segments) {
            Write-Host "segments:"
            foreach ($segment in $translation.segments) {
                Write-Host ("  [{0}-{1}] {2} => {3}" -f $segment.start, $segment.end, $segment.sourceText, $segment.translatedText)
            }
        }
        $summary.subtitleTranslate = [bool]$translation.ok
    }

    if ($VideoUrl) {
        Write-Section "Downloader"
        $download = Invoke-JsonRequest -Method Post -Url "$apiBase/api/test/downloader" -Body @{
            url = $VideoUrl
        }
        Write-Host "ok: $($download.ok)"
        Write-Host "durationMs: $($download.durationMs)"
        if (-not $download.ok) {
            Write-Host "errorMessage: $($download.errorMessage)"
        } else {
            Write-Host "filePath: $($download.filePath)"
            Write-Host "fileSize: $($download.fileSize)"
        }
        $summary.downloader = [bool]$download.ok
    }

    if ($VideoUrlCacheTest) {
        Write-Section "URL Cache"

        Write-Host "first submit: upload-url + wait terminal status"
        $firstStart = Get-Date
        $first = Invoke-UploadUrl -Url $VideoUrlCacheTest
        Write-Host "first videoId: $($first.videoId)"
        Write-Host "first initial status: $($first.status)"
        Write-Host "first initial stage: $($first.stage)"
        if ($first.status -eq "completed" -or $first.status -eq "failed") {
            $firstDone = $first
        } else {
            $firstDone = Wait-VideoTask -VideoId $first.videoId
        }
        $firstDurationMs = Get-ElapsedMs -StartTime $firstStart
        Write-Host "first final status: $($firstDone.status)"
        Write-Host "first final stage: $($firstDone.stage)"
        Write-Host "first elapsedMs: $firstDurationMs"

        if ($firstDone.status -ne "completed") {
            Write-Host "第一次任务未 completed，跳过第二次 URL 缓存验证。"
            if ($firstDone.errorMessage) {
                Write-Host "errorMessage: $($firstDone.errorMessage)"
            }
            $summary.urlCache = $false
        } else {
            Write-Host ""
            Write-Host "second submit: expect URL cache hit"
            $secondStart = Get-Date
            $second = Invoke-UploadUrl -Url $VideoUrlCacheTest
            if ($second.status -eq "completed" -or $second.status -eq "failed") {
                $secondDone = $second
            } else {
                $secondDone = Wait-VideoTask -VideoId $second.videoId -TimeoutSec 120
            }
            $secondDurationMs = Get-ElapsedMs -StartTime $secondStart
            Write-Host "second videoId: $($secondDone.videoId)"
            Write-Host "second status: $($secondDone.status)"
            Write-Host "second stage: $($secondDone.stage)"
            Write-Host "second elapsedMs: $secondDurationMs"

            if ($secondDone.status -eq "completed" -and $secondDone.stage -eq "命中链接缓存") {
                Write-Host "[OK] URL cache hit: second submit skipped downloader/subtitle/LLM."
                $summary.urlCache = $true
            } else {
                Write-Host "[WARN] 第二次没有明确命中链接缓存，请检查后端日志中的 [CACHE] 记录。"
                $summary.urlCache = $false
            }
        }
    }

    Write-SmokeSummary $summary
    Write-DependencyHint $health
} catch {
    Write-Host ""
    Write-Host "[ERROR] Smoke test failed: $($_.Exception.Message)"
    Write-SmokeSummary $summary
    exit 1
}
