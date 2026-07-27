$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'local-up.mjs') @args
exit $LASTEXITCODE
