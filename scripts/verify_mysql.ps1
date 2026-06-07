param(
    [string]$MysqlUrl = $(if ($env:MYSQL_URL) { $env:MYSQL_URL } else { "jdbc:mysql://localhost:3306/video_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" }),
    [string]$MysqlUsername = $(if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" }),
    [string]$MysqlPassword = $(if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { "" }),
    [string]$MysqlExe = "",
    [string]$LlmModel = $(if ($env:LLM_MODEL) { $env:LLM_MODEL } else { "gpt-5.5" })
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
$initSql = Join-Path $projectRoot "scripts\init_mysql.sql"

function Read-PasswordIfNeeded {
    param([string]$ExistingPassword)

    if (-not [string]::IsNullOrEmpty($ExistingPassword)) {
        return $ExistingPassword
    }

    Write-Host "MYSQL_PASSWORD is not set. Paste the MySQL password when prompted; input will not be displayed."
    $secureValue = Read-Host "MYSQL_PASSWORD" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Find-MysqlClient {
    param([string]$RequestedPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        if (Test-Path -LiteralPath $RequestedPath) {
            return (Resolve-Path -LiteralPath $RequestedPath).Path
        }
        throw "mysql.exe not found: $RequestedPath"
    }

    $command = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    foreach ($serviceName in @("MySQL", "MySQL80", "MySQL57", "MariaDB")) {
        try {
            $service = Get-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\$serviceName" -ErrorAction SilentlyContinue
            if ($service -and $service.ImagePath) {
                $imagePath = [string]$service.ImagePath
                $mysqldPath = if ($imagePath -match '^"([^"]+)"') {
                    $Matches[1]
                } else {
                    ($imagePath -split '\s+', 2)[0]
                }
                $serviceCandidate = Join-Path (Split-Path -Parent $mysqldPath) "mysql.exe"
                if (Test-Path -LiteralPath $serviceCandidate) {
                    return $serviceCandidate
                }
            }
        } catch {
            # Ignore inaccessible service registry entries and continue with common paths.
        }
    }

    $candidates = @(
        "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe",
        "C:\Program Files\MariaDB 11.0\bin\mysql.exe",
        "C:\Program Files\MariaDB 10.11\bin\mysql.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    return ""
}

function Parse-MysqlJdbcUrl {
    param([string]$JdbcUrl)

    $hostName = "localhost"
    $port = "3306"
    $database = "video_analysis"

    if ($JdbcUrl -match '^jdbc:mysql://(?<host>[^:/?]+)(:(?<port>\d+))?/(?<database>[^?]+)') {
        $hostName = $Matches.host
        if ($Matches.port) {
            $port = $Matches.port
        }
        if ($Matches.database) {
            $database = $Matches.database
        }
    }

    return [PSCustomObject]@{
        Host = $hostName
        Port = $port
        Database = $database
    }
}

function Remove-SecretText {
    param([string]$Value)

    if ($null -eq $Value) {
        return ""
    }

    return $Value `
        -replace '(?i)(password=)[^&;,\s]+', '$1***' `
        -replace '(?i)(pwd=)[^&;,\s]+', '$1***'
}

function Format-ProcessArgument {
    param([string]$Argument)

    if ($Argument -notmatch '[\s"]') {
        return $Argument
    }

    $escaped = $Argument -replace '"', '\"'
    return '"' + $escaped + '"'
}

function Invoke-Mysql {
    param(
        [string]$ClientPath,
        [string[]]$Arguments,
        [string]$Password,
        [string]$InputText = $null
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $ClientPath
    $startInfo.Arguments = (($Arguments | ForEach-Object { Format-ProcessArgument $_ }) -join " ")
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardInput = ($null -ne $InputText)
    $startInfo.CreateNoWindow = $true
    $startInfo.EnvironmentVariables["MYSQL_PWD"] = $Password

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        if ($null -ne $InputText) {
            $process.StandardInput.Write($InputText)
            $process.StandardInput.Close()
        }

        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        if (-not $process.WaitForExit(60000)) {
            $process.Kill()
            return [PSCustomObject]@{
                ExitCode = -1
                Output = "mysql.exe execution timed out."
            }
        }

        $combinedOutput = (($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join [Environment]::NewLine
        return [PSCustomObject]@{
            ExitCode = $process.ExitCode
            Output = $combinedOutput.Trim()
        }
    } finally {
        $process.Dispose()
    }
}

try {
    $clientPath = Find-MysqlClient -RequestedPath $MysqlExe
    if ([string]::IsNullOrWhiteSpace($clientPath)) {
        Write-Host "[FAIL] mysql.exe 不可用。"
        Write-Host "处理方式：安装 MySQL Client，或使用 -MysqlExe 传入完整路径。"
        Write-Host '示例：.\scripts\verify_mysql.ps1 -MysqlExe "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -MysqlUsername "root"'
        exit 1
    }

    if (-not (Test-Path -LiteralPath $initSql)) {
        throw "init SQL not found: $initSql"
    }

    $connection = Parse-MysqlJdbcUrl -JdbcUrl $MysqlUrl
    $plainPassword = Read-PasswordIfNeeded -ExistingPassword $MysqlPassword

    Write-Host "mysql.exe: $clientPath"
    Write-Host "host: $($connection.Host)"
    Write-Host "port: $($connection.Port)"
    Write-Host "database: $($connection.Database)"
    Write-Host "username: $MysqlUsername"
    Write-Host "password configured=$(-not [string]::IsNullOrEmpty($plainPassword))"

    Write-Host ""
    Write-Host "==== MySQL Login ===="
    $baseArgs = @(
        "--protocol=tcp",
        "--host=$($connection.Host)",
        "--port=$($connection.Port)",
        "--user=$MysqlUsername",
        "--default-character-set=utf8mb4"
    )
    $loginResult = Invoke-Mysql -ClientPath $clientPath -Arguments ($baseArgs + @("--execute=SELECT VERSION();")) -Password $plainPassword
    if ($loginResult.ExitCode -ne 0) {
        Write-Host "[FAIL] MySQL 登录失败。"
        Write-Host (Remove-SecretText $loginResult.Output)
        Write-Host "请先确认账号密码可用；如忘记密码，需要重置 MySQL 账号密码后再运行本脚本。"
        exit 1
    }
    Write-Host "[OK] MySQL 登录成功。"
    Write-Host (Remove-SecretText $loginResult.Output)

    Write-Host ""
    Write-Host "==== Init Database ===="
    $sql = Get-Content -Path $initSql -Raw -Encoding UTF8
    $initResult = Invoke-Mysql -ClientPath $clientPath -Arguments $baseArgs -Password $plainPassword -InputText $sql
    if ($initResult.ExitCode -ne 0) {
        Write-Host "[FAIL] init_mysql.sql 执行失败。"
        Write-Host (Remove-SecretText $initResult.Output)
        exit 1
    }
    Write-Host "[OK] init_mysql.sql 执行完成。"

    Write-Host ""
    Write-Host "==== Next Step ===="
    Write-Host ('$env:MYSQL_USERNAME="{0}"' -f $MysqlUsername)
    Write-Host '$env:MYSQL_PASSWORD="你的密码"'
    Write-Host ('$env:MYSQL_URL="{0}"' -f (Remove-SecretText $MysqlUrl))
    Write-Host ".\scripts\start_backend_mysql.ps1 -LlmModel `"$LlmModel`""
} finally {
    if (Get-Variable -Name plainPassword -ErrorAction SilentlyContinue) {
        $plainPassword = $null
    }
}
