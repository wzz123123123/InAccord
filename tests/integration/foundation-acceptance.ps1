[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $AcceptanceArguments
)

$ErrorActionPreference = 'Stop'
$node = Get-Command node -ErrorAction Stop
$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$launcher = Join-Path $repositoryRoot 'scripts\acceptance\run-foundation-acceptance.mjs'

& $node.Source $launcher @AcceptanceArguments
exit $LASTEXITCODE
