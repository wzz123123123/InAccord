param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$launcher = if ($IsWindows) { Join-Path $repositoryRoot 'gradlew.bat' } else { Join-Path $repositoryRoot 'gradlew' }
$requiresUncachedExecution = $GradleArgs | Where-Object {
  $_ -eq 'resolveAndLockAll' -or $_ -match '(^|:)(?:jlink|jpackage)[A-Za-z]*$'
}
if ($requiresUncachedExecution -and $GradleArgs -contains '--configuration-cache') {
  throw 'jlink, jpackage, and dependency-lock refresh tasks do not support --configuration-cache'
}
if ($requiresUncachedExecution -and $GradleArgs -notcontains '--no-configuration-cache') {
  $GradleArgs = @('--no-configuration-cache') + $GradleArgs
}
& $launcher @GradleArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
