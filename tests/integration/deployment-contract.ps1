[CmdletBinding()]
param(
    [ValidateSet('test', 'production')]
    [string]$Mode = 'test',

    [string]$Values = 'build/deployment/rendered-values.yaml',

    [string]$Output = 'build/verification/ft15-deployment.json'
)

$ErrorActionPreference = 'Stop'
$node = Get-Command node -ErrorAction SilentlyContinue
if ($null -eq $node) {
    [Console]::Error.WriteLine('BLOCKED_TOOLCHAIN: node is unavailable; deployment verification was not started')
    exit 2
}

$arguments = @(
    'scripts/deployment/verify-deployment.mjs'
    '--mode', $Mode
    '--values', $Values
    '--output', $Output
)

& $node.Source @arguments
exit $LASTEXITCODE
