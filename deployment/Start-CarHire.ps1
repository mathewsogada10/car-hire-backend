[CmdletBinding()]
param(
    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
    [string]$ProjectName = 'carhire',
    [Security.SecureString]$OwnerPassword
)

$ErrorActionPreference = 'Stop'
function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Arguments[0])" }
}
function Get-PlainText {
    param([Security.SecureString]$Value)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
function Protect-SecretDirectory {
    param([string]$Path)
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $Path /inheritance:r /grant:r "*$($sid):(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict secret-directory permissions.' }
    } else {
        & chmod 700 $Path
        if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict secret-directory permissions.' }
    }
}
function Write-Secret {
    param([string]$Path, [string]$Value)
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        # The private parent protects host access; file mounts must be readable by the non-root containers.
        & chmod 444 $Path
        if ($LASTEXITCODE -ne 0) { throw 'Unable to set container-readable secret permissions.' }
    }
}

Push-Location $PSScriptRoot
try {
    $os = Invoke-Docker @('info', '--format', '{{.OSType}}')
    if ($os -ne 'linux') { throw 'Switch Docker to Linux containers before starting.' }
    $version = Invoke-Docker @('compose', 'version', '--short')
    if ([version](($version -replace '^v', '') -split '-')[0] -lt [version]'2.20.0') {
        throw 'Docker Compose 2.20 or newer is required.'
    }
    $bundle = Get-Content -LiteralPath 'bundle.json' -Raw | ConvertFrom-Json
    if ($bundle.format -ne 'carhire-docker-bundle-v1') { throw 'Unsupported bundle format.' }
    foreach ($file in $bundle.files.PSObject.Properties) {
        $actual = (Get-FileHash -LiteralPath $file.Name -Algorithm SHA256).Hash
        if ($actual -ne $file.Value) { throw "Bundle checksum mismatch: $($file.Name)" }
    }
    $load = $false
    foreach ($image in $bundle.images) {
        $id = Invoke-Docker @('image', 'ls', '--no-trunc', '--quiet', '--filter', "reference=$($image.reference)")
        if ($id -ne $image.id) { $load = $true }
    }
    if ($load) { Invoke-Docker @('image', 'load', '--input', 'images.tar') }
    foreach ($image in $bundle.images) {
        $id = Invoke-Docker @('image', 'ls', '--no-trunc', '--quiet', '--filter', "reference=$($image.reference)")
        if ($id -ne $image.id) { throw "Image identity mismatch: $($image.reference)" }
    }

    $databaseSecret = Join-Path 'secrets' 'database-password'
    $ownerSecret = Join-Path 'secrets' 'owner-password'
    $databaseExists = Test-Path -LiteralPath $databaseSecret
    $ownerExists = Test-Path -LiteralPath $ownerSecret
    if ((-not $databaseExists) -or (-not $ownerExists)) {
        $volumes = Invoke-Docker @('volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName")
        if ($volumes) { throw 'Existing data volumes require the original secret files. Restore them before starting; credentials will not be regenerated.' }
    }
    if ($ownerExists -and $OwnerPassword) { throw 'Owner credentials already exist and will not be overwritten.' }
    if (-not (Test-Path -LiteralPath 'secrets')) { New-Item -ItemType Directory -Path 'secrets' | Out-Null }
    Protect-SecretDirectory (Join-Path $PSScriptRoot 'secrets')
    if (-not $ownerExists) {
        if (-not $OwnerPassword) {
            $OwnerPassword = Read-Host 'Choose the owner password (at least 12 characters)' -AsSecureString
            $confirmation = Read-Host 'Confirm the owner password' -AsSecureString
            if ((Get-PlainText $OwnerPassword) -cne (Get-PlainText $confirmation)) { throw 'Passwords do not match.' }
        }
        $plain = Get-PlainText $OwnerPassword
        if ($plain.Length -lt 12 -or $plain -match '[\x00\r\n]') { throw 'Password must be at least 12 characters with no NUL or line breaks.' }
        Write-Secret (Join-Path $PSScriptRoot $ownerSecret) $plain
        $plain = $null
    }
    if (-not $databaseExists) {
        $bytes = New-Object byte[] 32
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($bytes) } finally { $random.Dispose() }
        Write-Secret (Join-Path $PSScriptRoot $databaseSecret) ([Convert]::ToBase64String($bytes))
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
    if (-not (Test-Path -LiteralPath 'settings.env')) {
        Copy-Item -LiteralPath 'settings.env.example' -Destination 'settings.env'
    }
    $configuredImages = Invoke-Docker @('compose', '--project-name', $ProjectName, '--env-file', 'settings.env', '--file', 'compose.yaml', 'config', '--images')
    $expectedImages = @($bundle.images | ForEach-Object { $_.reference })
    if (Compare-Object ($configuredImages | Sort-Object -Unique) ($expectedImages | Sort-Object -Unique)) {
        throw 'Configured image tags do not match this bundle. Restore CARHIRE_IMAGE_TAG from settings.env.example.'
    }
    Invoke-Docker @('compose', '--project-name', $ProjectName, '--env-file', 'settings.env', '--file', 'compose.yaml',
        'up', '--detach', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '300')
    Invoke-Docker @('compose', '--project-name', $ProjectName, '--env-file', 'settings.env', '--file', 'compose.yaml', 'ps')
    Write-Output 'Car Hire is ready. Default address: http://localhost:8080 (or HTTP_PORT in settings.env).'
} finally {
    Pop-Location
}
