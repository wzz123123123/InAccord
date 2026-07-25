$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

if ((git rev-parse --is-inside-work-tree).Trim() -ne 'true') { throw 'FT1 requires an existing Git worktree' }
git show-ref --verify --quiet refs/heads/main
if ($LASTEXITCODE -ne 0) { throw 'tracked main design baseline is required before FT1' }
$requiredDesign = @(
  'requirements-agent-platform-design.md',
  'docs/superpowers/specs/2026-07-25-accord-java-python-runtime-design.md',
  'docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md'
)
foreach ($path in $requiredDesign) {
  git ls-files --error-unmatch -- $path | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "design baseline is not tracked: $path" }
}
$approvedDigests = [ordered]@{
  'requirements-agent-platform-design.md' = '0755A08228254311588EE0B867AFEBED6CD49E26B0FCCB790247BFDA31AB2C77'
  'docs/superpowers/specs/2026-07-25-accord-java-python-runtime-design.md' = '46DE7309CB28F8E59B3FDAB4D6FA664AE4D932FC7F517741681CC25C3B84E4E1'
}
function Get-NormalizedSha256([string]$Path) {
  $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
  $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
  $normalizedBytes = $utf8.GetBytes($utf8.GetString($bytes).Replace("`r`n", "`n"))
  $sha256 = [System.Security.Cryptography.SHA256]::Create()
  try {
    return [BitConverter]::ToString($sha256.ComputeHash($normalizedBytes)).Replace('-', '')
  } finally {
    $sha256.Dispose()
  }
}
foreach ($entry in $approvedDigests.GetEnumerator()) {
  $actual = Get-NormalizedSha256 -Path $entry.Key
  if ($actual -cne $entry.Value) {
    throw "approved design digest mismatch for $($entry.Key): $actual"
  }
}
git merge-base --is-ancestor main HEAD
if ($LASTEXITCODE -ne 0) { throw 'feature branch must descend from local main' }
Write-Output 'design-baseline: PASS'
