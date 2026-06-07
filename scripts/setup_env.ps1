param(
    [switch]$CreateCondaEnv,
    [string]$CondaEnvName = "whisperx_env",
    [string]$PythonVersion = "3.10",
    [switch]$InstallWhisperX,
    [switch]$InstallYtDlp,
    [switch]$InstallAll,
    [switch]$UseCurrentPython,
    [switch]$ForceTorchInstall,
    [switch]$Yes
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

if ($InstallAll) {
    $InstallWhisperX = $true
    $InstallYtDlp = $true
}

function Confirm-Step {
    param([string]$Message)
    if ($Yes) {
        return $true
    }
    $answer = Read-Host "$Message 输入 Y 继续"
    return $answer -eq "Y" -or $answer -eq "y"
}

function Invoke-CommandStep {
    param(
        [string]$Description,
        [string[]]$Command
    )

    Write-Host ""
    Write-Host "==== $Description ===="
    Write-Host "将执行: $($Command -join ' ')"
    if (-not (Confirm-Step "确认执行？")) {
        Write-Host "已跳过: $Description"
        return $false
    }

    & $Command[0] @($Command | Select-Object -Skip 1)
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
        throw "命令执行失败，退出码 $LASTEXITCODE`: $($Command -join ' ')"
    }
    return $true
}

function Test-CommandExists {
    param([string]$Command)
    return $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
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

Write-Host "==== Setup Environment ===="
if ($UseCurrentPython) {
    Write-Host "将使用当前 PATH 下的 python/pip。"
} else {
    Write-Host "默认使用当前 PATH 下的 python/pip。推荐先手动 activate conda 环境后再运行本脚本。"
}

if ($CreateCondaEnv) {
    if (-not (Test-CommandExists "conda")) {
        throw "conda 不可用，无法创建环境。可以先安装 Anaconda/Miniconda，或使用 -UseCurrentPython 在当前 Python 中安装。"
    }

    Invoke-CommandStep `
        -Description "创建 conda 环境" `
        -Command @("conda", "create", "-n", $CondaEnvName, "python=$PythonVersion", "-y")

    Write-Host ""
    Write-Host "Conda 环境创建命令已执行。请在新的 PowerShell 中运行："
    Write-Host "conda activate $CondaEnvName"
    Write-Host "然后再运行："
    Write-Host ".\scripts\setup_env.ps1 -InstallAll"
}

if ($InstallWhisperX) {
    if (-not (Test-CommandExists "python")) {
        throw "python 不可用，无法安装 whisperx。"
    }
    if (-not (Test-CommandExists "pip")) {
        throw "pip 不可用，无法安装 whisperx。"
    }

    Write-Host ""
    Write-Host "==== 检查 torch/CUDA ===="
    $torch = Invoke-Capture @("python", "-c", "import torch; print(torch.__version__); print(torch.cuda.is_available())")
    if ($torch.Ok) {
        $lines = $torch.Output -split "`r?`n"
        $torchVersion = if ($lines.Count -ge 1) { $lines[0] } else { "unknown" }
        $cuda = if ($lines.Count -ge 2) { $lines[1] } else { "unknown" }
        Write-Host "[OK] torch: $torchVersion, cuda=$cuda"
        if ($cuda -eq "True" -or $cuda -eq "true") {
            Write-Host "检测到 torch CUDA 可用，默认不会重新安装 torch。"
        }
    } else {
        Write-Host "[WARN] torch 不可导入：$($torch.Output)"
        if (-not $ForceTorchInstall) {
            Write-Host "不会自动安装 torch。请按本机 CUDA 从 https://pytorch.org/get-started/locally/ 选择安装命令。"
        }
    }

    if ($ForceTorchInstall) {
        Write-Host ""
        Write-Host "[WARN] 已指定 -ForceTorchInstall，但脚本不会硬编码 CUDA 版本的 torch 安装命令。"
        Write-Host "请根据本机 CUDA 在 https://pytorch.org/get-started/locally/ 选择命令并手动执行。"
        if (-not (Confirm-Step "确认你已理解 torch 需要手动选择 CUDA 版本？")) {
            throw "用户取消 ForceTorchInstall 确认。"
        }
    }

    Invoke-CommandStep `
        -Description "安装或升级 whisperx" `
        -Command @("pip", "install", "-U", "whisperx")
}

if ($InstallYtDlp) {
    if (-not (Test-CommandExists "pip")) {
        throw "pip 不可用，无法安装 yt-dlp。"
    }

    try {
        Invoke-CommandStep `
            -Description "安装或升级 yt-dlp" `
            -Command @("pip", "install", "-U", "yt-dlp")
    } catch {
        Write-Host "[WARN] yt-dlp 安装命令失败：$($_.Exception.Message)"
        Write-Host "[WARN] 将继续尝试验证现有 yt-dlp 或 python -m yt_dlp。"
    }

    Write-Host ""
    Write-Host "==== 验证 yt-dlp ===="
    if (Test-CommandExists "yt-dlp") {
        & yt-dlp --version
    } elseif (Test-CommandExists "python") {
        Write-Host "[WARN] yt-dlp 命令不可用，尝试 python -m yt_dlp --version"
        & python -m yt_dlp --version
    } else {
        Write-Host "[FAIL] yt-dlp 安装后仍无法验证。"
    }
}

Write-Host ""
Write-Host "==== 安装后检查 ===="
$checkScript = Join-Path $PSScriptRoot "check_env.ps1"
if (Test-Path $checkScript) {
    powershell -ExecutionPolicy Bypass -File $checkScript
} else {
    Write-Host "未找到 check_env.ps1，跳过检查。"
}
