Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$generator = (Resolve-Path -LiteralPath 'scripts/generate-control-plane-jooq.ps1').Path
$script:dockerCalls = [System.Collections.Generic.List[string]]::new()
$script:fakeContainerExists = $false
$script:fakeContainerName = $null
$script:fakeContainerId = 'a' * 64

function global:docker {
    param([Parameter(ValueFromRemainingArguments)][object[]]$DockerArgs)

    $arguments = @($DockerArgs | ForEach-Object { [string]$_ })
    $script:dockerCalls.Add(($arguments -join ' '))
    if ($arguments.Count -ge 1 -and $arguments[0] -eq 'version') {
        $global:LASTEXITCODE = 0
        return '29.4.2'
    }
    if ($arguments.Count -ge 2 -and
            $arguments[0] -eq 'image' -and $arguments[1] -eq 'inspect') {
        $global:LASTEXITCODE = 0
        return 'sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302'
    }
    if ($arguments.Count -ge 1 -and $arguments[0] -eq 'run') {
        $nameIndex = [Array]::IndexOf($arguments, '--name')
        if ($nameIndex -ge 0 -and $nameIndex + 1 -lt $arguments.Count) {
            $script:fakeContainerName = $arguments[$nameIndex + 1]
        }
        $script:fakeContainerExists = $true
        $global:LASTEXITCODE = 125
        return
    }
    if ($arguments.Count -ge 1 -and $arguments[0] -eq 'ps') {
        $global:LASTEXITCODE = 0
        if ($script:fakeContainerExists) {
            return $script:fakeContainerId
        }
        return
    }
    if ($arguments.Count -ge 1 -and $arguments[0] -eq 'rm') {
        if ($null -eq $script:fakeContainerName -or
                $arguments -notcontains $script:fakeContainerName) {
            $global:LASTEXITCODE = 1
            return
        }
        $script:fakeContainerExists = $false
        $global:LASTEXITCODE = 0
        return $script:fakeContainerId
    }
    throw "Unexpected docker invocation: $($arguments -join ' ')"
}

$probe = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback,
    0)
$probe.Start()
$port = ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
$probe.Stop()

$primaryFailure = $null
try {
    . $generator -Port $port
} catch {
    $primaryFailure = $_
} finally {
    Remove-Item -LiteralPath Function:\docker -ErrorAction SilentlyContinue
}

if ($null -eq $primaryFailure -or
        $primaryFailure.Exception.Message -notmatch
            'Docker did not return the exact PostgreSQL container ID') {
    $actualMessage = if ($null -eq $primaryFailure) {
        '<no failure>'
    } else {
        $primaryFailure.Exception.Message
    }
    throw "Generator did not preserve the injected docker run failure: $actualMessage"
}
if ($script:fakeContainerExists) {
    throw 'Generator left a created container after docker run returned no ID'
}
if ($script:fakeContainerName -notmatch
        '^accord-control-plane-jooq-[0-9]+-[0-9a-f]{32}$') {
    throw "Generator did not assign a unique stable container name: $script:fakeContainerName"
}
$nameFilter = "name=^/$([regex]::Escape($script:fakeContainerName))$"
if (-not ($script:dockerCalls | Where-Object {
        $_ -like "ps *--filter $nameFilter*"
    })) {
    throw 'Generator cleanup did not query the exact container name'
}
if (-not ($script:dockerCalls | Where-Object {
        $_ -like "rm --force $script:fakeContainerName"
    })) {
    throw 'Generator cleanup did not remove the exact container name'
}

Write-Output 'control-plane-jooq-generator-failure: PASS'
