$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'local-down.mjs') @args
exit $LASTEXITCODE
