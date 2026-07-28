$ErrorActionPreference = 'Stop'
$required = @(
  '.editorconfig',
  '.gitattributes',
  '.gitignore',
  '.tool-versions',
  'settings.gradle',
  'build.gradle',
  'gradle.properties',
  'gradle/libs.versions.toml',
  'gradlew',
  'gradlew.bat',
  'gradle/wrapper/gradle-wrapper.jar',
  'gradle/wrapper/gradle-wrapper.properties',
  'gradle/wrapper/gradle-wrapper.jar.sha256',
  'gradle/verification-metadata.xml',
  'gradle.lockfile',
  'settings-gradle.lockfile',
  'package.json',
  'pnpm-lock.yaml',
  'tsconfig.base.json',
  'pnpm-workspace.yaml',
  'pyproject.toml',
  'uv.lock',
  'apps/control-plane/api/build.gradle',
  'apps/control-plane/worker/build.gradle',
  'apps/control-plane/modules/platform-kernel/build.gradle',
  'apps/control-plane/modules/reliability/build.gradle',
  'database/control-plane/build.gradle',
  'database/control-plane/buildscript-gradle.lockfile',
  'config/testcontainers/testcontainers.properties',
  'database/control-plane/src/test/java/com/inforvans/accord/database/TestcontainersConfigurationTest.java',
  'tests/integration/src/test/java/com/inforvans/accord/integration/TestcontainersConfigurationTest.java',
  'apps/webhook-edge/build.gradle',
  'security-services/signing-service/build.gradle',
  'security-services/merge-controller/build.gradle',
  'cmd/accordctl/build.gradle',
  'libs/java/observability/build.gradle',
  'tests/contract/build.gradle',
  'tests/integration/build.gradle',
  'tests/api/build.gradle',
  'tests/security-negative/build.gradle',
  'tests/state-machine/build.gradle',
  'tests/fault-injection/build.gradle',
  'cmd/accordctl/src/main/java/module-info.java',
  'cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCtl.java',
  'cmd/accordctl/src/test/java/com/inforvans/accord/cli/AccordCtlTest.java',
  'apps/web/package.json',
  'apps/agent-runtime/pyproject.toml',
  'apps/agent-runtime/src/accord_agent_runtime/__init__.py',
  'apps/agent-runtime/src/accord_agent_runtime/boundary.py',
  'apps/agent-runtime/tests/test_bootstrap.py',
  'scripts/run-gradle.ps1',
  'scripts/generate-control-plane-jooq.ps1',
  'tests/bootstrap/verify-control-plane-jooq-generator.ps1'
)
$gradleProjectDirectories = @(
  'apps/control-plane/api',
  'apps/control-plane/worker',
  'apps/control-plane/modules/platform-kernel',
  'apps/control-plane/modules/reliability',
  'database/control-plane',
  'apps/webhook-edge',
  'security-services/signing-service',
  'security-services/merge-controller',
  'cmd/accordctl',
  'libs/java/observability',
  'tests/contract',
  'tests/integration',
  'tests/api',
  'tests/security-negative',
  'tests/state-machine',
  'tests/fault-injection'
)
$required += $gradleProjectDirectories | ForEach-Object { "$_/gradle.lockfile" }
$missing = $required | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
if ($missing.Count -gt 0) {
  throw "Missing workspace files: $($missing -join ', ')"
}
$sourceRoots = @('apps', 'database', 'security-services', 'cmd', 'libs', 'tests')
$generatedDirectoryPattern = '[\\/](?:\.gradle|\.venv|node_modules|build|dist|\.pytest_cache|__pycache__|\.mypy_cache|\.ruff_cache)[\\/]'
$forbiddenDirectories = Get-ChildItem $sourceRoots -Directory -Recurse -ErrorAction SilentlyContinue |
  Where-Object {
    $_.FullName -notmatch $generatedDirectoryPattern -and
    $_.Name -eq 'common'
  }
if ($forbiddenDirectories) {
  throw "Unbounded common module found: $($forbiddenDirectories.FullName -join ', ')"
}
$approvedExtensions = @(
  '.java', '.py', '.ts', '.tsx', '.js', '.mjs', '.sql', '.proto',
  '.json', '.yaml', '.yml', '.md', '.toml', '.ps1', '.gradle',
  '.properties', '.xml', '.html', '.css', '.svg', '.lock', '.lockfile', '.bat',
  '.jar', '.sha256'
)
$approvedExtensionlessNames = @(
  'Dockerfile',
  'gradlew',
  'org.springframework.boot.autoconfigure.AutoConfiguration.imports'
)
$generatedFileNames = @('.jqwik-database')
$unexpectedRuntimeFiles = Get-ChildItem $sourceRoots -Recurse -File -ErrorAction SilentlyContinue |
  Where-Object {
    $_.FullName -notmatch $generatedDirectoryPattern -and
    $_.Name -notin $generatedFileNames -and
    $_.Extension -notin $approvedExtensions -and
    $_.Name -notin $approvedExtensionlessNames
  }
if ($unexpectedRuntimeFiles) {
  throw "source file outside the approved runtime matrix: $($unexpectedRuntimeFiles.FullName -join ', ')"
}

$settings = Get-Content -Raw -Encoding utf8 settings.gradle
@(
  ':apps:control-plane:api',
  ':apps:control-plane:worker',
  ':apps:control-plane:modules:platform-kernel',
  ':apps:control-plane:modules:reliability',
  ':database:control-plane',
  ':apps:webhook-edge',
  ':security-services:signing-service',
  ':security-services:merge-controller',
  ':cmd:accordctl',
  ':libs:java:observability',
  ':tests:contract',
  ':tests:integration',
  ':tests:api',
  ':tests:security-negative',
  ':tests:state-machine',
  ':tests:fault-injection'
) | ForEach-Object {
  if (-not $settings.Contains($_)) { throw "Gradle project not included: $_" }
}
$toolVersions = Get-Content -Encoding utf8 .tool-versions
$expectedTools = [ordered]@{
  java = 'temurin-21.0.11+10'; gradle = '8.14.3'; nodejs = '22.22.1';
  pnpm = '10.12.4'; python = '3.12.11'; uv = '0.7.13'; buf = '1.55.1';
  helm = '3.17.3'; opentofu = '1.9.1'; k6 = '0.57.0';
  conftest = '0.61.2'; kubeconform = '0.7.0'; syft = '1.27.1';
  cosign = '2.5.0'; trivy = '0.63.0'
}
foreach ($tool in $expectedTools.GetEnumerator()) {
  if ($toolVersions -notcontains "$($tool.Key) $($tool.Value)") {
    throw "Tool version is not pinned: $($tool.Key) $($tool.Value)"
  }
}
if (-not (Select-String -Quiet gradle/wrapper/gradle-wrapper.properties -Pattern '^distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531$')) {
  throw 'Gradle wrapper distribution checksum is absent or wrong'
}
$expectedWrapperJarHash = (Get-Content -Raw -Encoding ascii gradle/wrapper/gradle-wrapper.jar.sha256).Trim()
$actualWrapperJarHash = (Get-FileHash -Algorithm SHA256 gradle/wrapper/gradle-wrapper.jar).Hash.ToLowerInvariant()
if ($actualWrapperJarHash -cne $expectedWrapperJarHash) {
  throw "Gradle wrapper JAR checksum mismatch: $actualWrapperJarHash"
}
$canonicalTestcontainersProperties = 'config/testcontainers/testcontainers.properties'
$testcontainersPins = @(
  Get-Content -Encoding ascii -LiteralPath $canonicalTestcontainersProperties
)
$expectedTestcontainersPins = @(
  'ryuk.container.image=testcontainers/ryuk:0.12.0@sha256:dd3f023a6ed7015b3f95a49ccd65a2daf0c56e681422c12952b19a810dfa6298',
  'tinyimage.container.image=alpine:3.17@sha256:8fc3dacfb6d69da8d44e42390de777e48577085db99aa4e4af35f483eb08b989'
)
if (($testcontainersPins -join "`n") -cne ($expectedTestcontainersPins -join "`n")) {
  throw 'Testcontainers helper image pins are absent, reordered, or incorrect'
}
$rootBuild = (Get-Content -Raw -Encoding utf8 -LiteralPath 'build.gradle') -replace "`r`n", "`n"
$expectedTestcontainersDeclaration =
  "def testcontainersConfigDirectory = layout.projectDirectory.dir('config/testcontainers')"
$expectedTestcontainersSourceSet = @(
  "    plugins.withId('java') {",
  '        sourceSets {',
  '            test {',
  '                resources.srcDir(testcontainersConfigDirectory)',
  '            }',
  '        }',
  '        java {'
) -join "`n"
$expectedDuplicateFailure = @(
  "        tasks.named('processTestResources').configure {",
  '            duplicatesStrategy = DuplicatesStrategy.FAIL',
  '        }'
) -join "`n"
@(
  $expectedTestcontainersDeclaration,
  $expectedTestcontainersSourceSet,
  $expectedDuplicateFailure
) | ForEach-Object {
  if ([regex]::Matches($rootBuild, [regex]::Escape($_)).Count -ne 1) {
    throw 'Global Testcontainers test-resource wiring is absent, duplicated, or incorrect'
  }
}
$unexpectedTestcontainersProperties = @(
  Get-ChildItem $sourceRoots -Recurse -File -Filter 'testcontainers.properties' `
      -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch $generatedDirectoryPattern }
)
if ($unexpectedTestcontainersProperties.Count -gt 0) {
  throw "Module-local Testcontainers properties found: $($unexpectedTestcontainersProperties.FullName -join ', ')"
}
Write-Output 'workspace-layout: PASS'
