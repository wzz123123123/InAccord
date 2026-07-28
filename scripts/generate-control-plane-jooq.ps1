[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 55532
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $repositoryRoot 'gradlew.bat'
$bootstrap = Join-Path $repositoryRoot 'database/control-plane/bootstrap/00-pre-flyway-roles.sql'
$containerId = $null
$containerName = "accord-control-plane-jooq-$PID-$([guid]::NewGuid().ToString('N'))"
$postgresImage = 'postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302'
$migratorPassword = 'local-migrator-only'
$environmentNames = @(
    'ACCORD_DB_URL',
    'ACCORD_DB_MIGRATOR_USER',
    'ACCORD_DB_MIGRATOR_PASSWORD'
)
$priorEnvironment = @{}
$primaryError = $null
$cleanupErrors = [System.Collections.Generic.List[
    System.Management.Automation.ErrorRecord]]::new()

function Invoke-Checked {
    param(
        [Parameter(Mandatory)]
        [scriptblock]$Command,
        [Parameter(Mandatory)]
        [string]$FailureMessage
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit code $LASTEXITCODE)"
    }
}

function Invoke-GradleTask {
    param([Parameter(Mandatory)][string]$Task)

    Push-Location $repositoryRoot
    try {
        Invoke-Checked -FailureMessage "Gradle task failed: $Task" -Command {
            & $wrapper $Task `
                '--dependency-verification=strict' `
                '--no-configuration-cache' `
                '--no-daemon' `
                '--rerun-tasks'
        }
    }
    finally {
        Pop-Location
    }
}

function Get-ContainerIdByName {
    param([Parameter(Mandatory)][string]$Name)

    $matchingIds = @(& docker ps --all --quiet --no-trunc --filter "name=^/${Name}$")
    if ($LASTEXITCODE -ne 0) {
        throw "Could not query Docker container $Name"
    }
    if ($matchingIds.Count -gt 1) {
        throw "More than one Docker container matched exact name $Name"
    }
    if ($matchingIds.Count -eq 0) {
        return $null
    }
    return [string]$matchingIds[0]
}

foreach ($name in $environmentNames) {
    $priorEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "Gradle wrapper not found: $wrapper"
    }
    if (-not (Test-Path -LiteralPath $bootstrap -PathType Leaf)) {
        throw "Database bootstrap not found: $bootstrap"
    }
    $portProbe = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        $Port)
    try {
        $portProbe.Start()
    }
    catch {
        throw "TCP port $Port cannot be bound: $($_.Exception.Message)"
    }
    finally {
        $portProbe.Stop()
    }

    Invoke-Checked -FailureMessage 'Docker is unavailable' -Command {
        & docker version '--format' '{{.Server.Version}}'
    }
    Invoke-Checked -FailureMessage 'Required pinned PostgreSQL 17.5 image is unavailable' -Command {
        & docker image inspect $postgresImage '--format' '{{.Id}}'
    }

    $containerOutput = & docker run --detach `
        --name $containerName `
        --publish "127.0.0.1:${Port}:5432" `
        --env 'POSTGRES_DB=accord' `
        --env 'POSTGRES_PASSWORD=local-container-only' `
        $postgresImage
    $containerExitCode = $LASTEXITCODE
    $containerId = if ($null -eq $containerOutput) {
        ''
    } else {
        ([string]$containerOutput).Trim()
    }
    if ($containerExitCode -ne 0 -or $containerId -notmatch '^[0-9a-f]{64}$') {
        throw 'Docker did not return the exact PostgreSQL container ID'
    }

    $ready = $false
    for ($attempt = 1; $attempt -le 45; $attempt++) {
        & docker exec $containerId pg_isready --username postgres --dbname accord *> $null
        if ($LASTEXITCODE -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        throw 'PostgreSQL did not become ready within 45 seconds'
    }

    $serverVersion = (& docker exec $containerId `
        psql --username postgres --dbname accord --tuples-only --no-align `
        --command 'SHOW server_version_num').Trim()
    if ($LASTEXITCODE -ne 0 -or $serverVersion -ne '170005') {
        throw "Expected PostgreSQL server_version_num 170005, got '$serverVersion'"
    }

    Invoke-Checked -FailureMessage 'Could not copy the role bootstrap' -Command {
        & docker cp $bootstrap "${containerId}:/tmp/00-pre-flyway-roles.sql"
    }
    Invoke-Checked -FailureMessage 'Role bootstrap failed' -Command {
        & docker exec $containerId psql `
            --username postgres `
            --dbname accord `
            --set ON_ERROR_STOP=1 `
            --file /tmp/00-pre-flyway-roles.sql
    }
    Invoke-Checked -FailureMessage 'Could not provision the local migrator login' -Command {
        & docker exec $containerId psql `
            --username postgres `
            --dbname accord `
            --set ON_ERROR_STOP=1 `
            --command "ALTER ROLE accord_migrator_login PASSWORD '$migratorPassword';"
    }

    [Environment]::SetEnvironmentVariable(
        'ACCORD_DB_URL',
        "jdbc:postgresql://127.0.0.1:${Port}/accord",
        'Process')
    [Environment]::SetEnvironmentVariable(
        'ACCORD_DB_MIGRATOR_USER', 'accord_migrator_login', 'Process')
    [Environment]::SetEnvironmentVariable(
        'ACCORD_DB_MIGRATOR_PASSWORD', $migratorPassword, 'Process')

    Invoke-GradleTask ':database:control-plane:flywayMigrate'
    Invoke-GradleTask ':database:control-plane:jooqCodegen'
    Invoke-GradleTask ':database:control-plane:verifyGeneratedJooq'
    Invoke-GradleTask ':database:control-plane:compileJava'
}
catch {
    $primaryError = $_
}
finally {
    foreach ($name in $environmentNames) {
        try {
            [Environment]::SetEnvironmentVariable(
                $name,
                $priorEnvironment[$name],
                'Process')
        }
        catch {
            $cleanupErrors.Add($_)
        }
    }

    try {
        if (-not [string]::IsNullOrWhiteSpace($containerName)) {
            $cleanupContainerId = Get-ContainerIdByName -Name $containerName
            if ($null -ne $cleanupContainerId) {
                & docker rm --force $containerName *> $null
                if ($LASTEXITCODE -ne 0) {
                    throw "Could not remove PostgreSQL container $containerName"
                }
            }
            if ($null -ne (Get-ContainerIdByName -Name $containerName)) {
                throw "PostgreSQL container $containerName still exists after cleanup"
            }
        }
    }
    catch {
        $cleanupErrors.Add($_)
    }
}

foreach ($cleanupError in $cleanupErrors) {
    Write-Warning "Secondary cleanup failure: $($cleanupError.Exception.Message)" `
        -WarningAction Continue
}
if ($null -ne $primaryError) {
    $PSCmdlet.ThrowTerminatingError($primaryError)
}
if ($cleanupErrors.Count -gt 0) {
    $PSCmdlet.ThrowTerminatingError($cleanupErrors[0])
}
