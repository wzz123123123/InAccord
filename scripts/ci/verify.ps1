$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'verify.mjs') @args
exit $LASTEXITCODE
