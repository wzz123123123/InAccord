param(
    [string]$BufExecutable = 'buf',
    [string[]]$BufPrefixArguments = @()
)

$ErrorActionPreference = 'Stop'

function Invoke-Buf {
    param([string[]]$BufArguments)

    & $BufExecutable @BufPrefixArguments @BufArguments
    if ($LASTEXITCODE -ne 0) {
        throw "buf $($BufArguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

if (-not (Test-Path -LiteralPath 'buf.yaml' -PathType Leaf)) {
    throw 'buf.yaml is required before protobuf verification'
}

Invoke-Buf -BufArguments @('lint')
Invoke-Buf -BufArguments @('build', '-o', 'build/contracts.binpb')
Invoke-Buf -BufArguments @('build', '-o', 'build/contracts.json')

$descriptor = Get-Content -Raw -Encoding utf8 build/contracts.json | ConvertFrom-Json

function Assert-MessageContract {
    param(
        [object]$FileDescriptor,
        [string]$MessageName,
        [object[]]$ExpectedFields
    )

    $message = @($FileDescriptor.messageType | Where-Object { $_.name -eq $MessageName })
    if ($message.Count -ne 1 -or @($message[0].field).Count -ne $ExpectedFields.Count) {
        throw "$MessageName descriptor is missing, duplicated, or has unexpected fields"
    }
    foreach ($expected in $ExpectedFields) {
        $field = @($message[0].field | Where-Object { $_.name -eq $expected.Name })
        if ($field.Count -ne 1 -or
            $field[0].number -ne $expected.Number -or
            $field[0].type -ne $expected.Type -or
            $field[0].typeName -ne $expected.TypeName) {
            throw "$MessageName field contract is invalid: $($expected.Name)"
        }
    }
}

$contextFile = @($descriptor.file | Where-Object { $_.name -eq 'accord/common/v1/context.proto' })
if ($contextFile.Count -ne 1 -or $contextFile[0].package -ne 'accord.common.v1') {
    throw 'context descriptor is missing or has the wrong package'
}
$expectedContextFields = @(
    @{ Name = 'tenant_id'; Number = 1; Type = 'TYPE_STRING'; TypeName = $null },
    @{ Name = 'scope_type'; Number = 2; Type = 'TYPE_ENUM'; TypeName = '.accord.common.v1.ScopeType' },
    @{ Name = 'scope_id'; Number = 3; Type = 'TYPE_STRING'; TypeName = $null },
    @{ Name = 'correlation_id'; Number = 4; Type = 'TYPE_STRING'; TypeName = $null },
    @{ Name = 'actor_id'; Number = 5; Type = 'TYPE_STRING'; TypeName = $null }
)
Assert-MessageContract -FileDescriptor $contextFile[0] -MessageName 'RequestContext' -ExpectedFields $expectedContextFields

$scopeType = @($contextFile[0].enumType | Where-Object { $_.name -eq 'ScopeType' })
$expectedScopeValues = @(
    @{ Name = 'SCOPE_TYPE_UNSPECIFIED'; Number = 0 },
    @{ Name = 'SCOPE_TYPE_TENANT'; Number = 1 },
    @{ Name = 'SCOPE_TYPE_PROJECT'; Number = 2 },
    @{ Name = 'SCOPE_TYPE_REPOSITORY'; Number = 3 }
)
if ($scopeType.Count -ne 1 -or @($scopeType[0].value).Count -ne $expectedScopeValues.Count) {
    throw 'ScopeType descriptor is missing, duplicated, or has unexpected values'
}
foreach ($expected in $expectedScopeValues) {
    $value = @($scopeType[0].value | Where-Object { $_.name -eq $expected.Name })
    if ($value.Count -ne 1 -or $value[0].number -ne $expected.Number) {
        throw "ScopeType value contract is invalid: $($expected.Name)"
    }
}

$reliabilityFile = @($descriptor.file | Where-Object { $_.name -eq 'accord/reliability/v1/reliability.proto' })
if ($reliabilityFile.Count -ne 1 -or
    @($reliabilityFile[0].dependency).Count -ne 1 -or
    $reliabilityFile[0].dependency -notcontains 'accord/common/v1/context.proto') {
    throw 'reliability descriptor does not import the canonical request context'
}
$expectedRequestFields = @(
    @{ Name = 'context'; Number = 1; Type = 'TYPE_MESSAGE'; TypeName = '.accord.common.v1.RequestContext' },
    @{ Name = 'event_id'; Number = 2; Type = 'TYPE_STRING'; TypeName = $null },
    @{ Name = 'idempotency_key'; Number = 3; Type = 'TYPE_STRING'; TypeName = $null }
)
$expectedResponseFields = @(
    @{ Name = 'event_id'; Number = 1; Type = 'TYPE_STRING'; TypeName = $null },
    @{ Name = 'state'; Number = 2; Type = 'TYPE_STRING'; TypeName = $null }
)
Assert-MessageContract -FileDescriptor $reliabilityFile[0] -MessageName 'RetryOutboxEventRequest' -ExpectedFields $expectedRequestFields
Assert-MessageContract -FileDescriptor $reliabilityFile[0] -MessageName 'RetryOutboxEventResponse' -ExpectedFields $expectedResponseFields

$service = @($reliabilityFile[0].service | Where-Object { $_.name -eq 'ReliabilityAdminService' })
$method = @($service.method | Where-Object { $_.name -eq 'RetryOutboxEvent' })
if ($service.Count -ne 1 -or
    $method.Count -ne 1 -or
    $method[0].inputType -ne '.accord.reliability.v1.RetryOutboxEventRequest' -or
    $method[0].outputType -ne '.accord.reliability.v1.RetryOutboxEventResponse') {
    throw 'RetryOutboxEvent descriptor contract is invalid'
}

Write-Output 'protobuf-contracts: PASS'
Write-Output 'protobuf-compatibility: BOOTSTRAP_BASELINE_ONLY'
