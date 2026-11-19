[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$')]
    [string]$ImageTag = '0.1.0',
    [ValidateSet('linux/amd64', 'linux/arm64')]
    [string]$Platform = 'linux/amd64',
    [string]$WebRepository,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$backend = Split-Path $PSScriptRoot -Parent
if (-not $WebRepository) { $WebRepository = Join-Path (Split-Path $backend -Parent) 'car-hire-web' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Join-Path $backend 'dist') 'car-hire-docker' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$zipPath = "$OutputDirectory.zip"
if ((Test-Path -LiteralPath $OutputDirectory) -or (Test-Path -LiteralPath $zipPath)) {
    throw 'Output already exists. Choose a new OutputDirectory; existing deployments and secrets are never overwritten.'
}
if (-not (Test-Path -LiteralPath (Join-Path $WebRepository 'Dockerfile'))) { throw 'Web Dockerfile not found.' }

function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Arguments[0])" }
}

$os = Invoke-Docker @('info', '--format', '{{.OSType}}')
if ($os -ne 'linux') { throw 'Switch Docker to Linux containers before building.' }
Invoke-Docker @('build', '--platform', $Platform, '--tag', "carhire/backend:$ImageTag", $backend)
Invoke-Docker @('build', '--platform', $Platform, '--tag', "carhire/web:$ImageTag", $WebRepository)
Invoke-Docker @('pull', '--platform', $Platform, 'postgres:17-alpine')
Invoke-Docker @('tag', 'postgres:17-alpine', "carhire/postgres:$ImageTag")

$images = @("carhire/backend:$ImageTag", "carhire/web:$ImageTag", "carhire/postgres:$ImageTag")
$identities = @()
foreach ($image in $images) {
    $actualPlatform = Invoke-Docker @('image', 'inspect', '--format', '{{.Os}}/{{.Architecture}}', $image)
    if ($actualPlatform -ne $Platform) { throw "Image platform mismatch for $image. Build on a matching Docker engine or configure its cross-platform support." }
    $id = Invoke-Docker @('image', 'inspect', '--format', '{{.Id}}', $image)
    $identities += [ordered]@{ reference = $image; id = [string]$id }
}

New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$payloads = @('compose.yaml', 'settings.env.example', 'Start-CarHire.ps1', 'Stop-CarHire.ps1', 'README.md')
foreach ($name in $payloads) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $OutputDirectory $name)
}
$settings = Join-Path $OutputDirectory 'settings.env.example'
$content = [IO.File]::ReadAllText($settings).Replace('CARHIRE_IMAGE_TAG=0.1.0', "CARHIRE_IMAGE_TAG=$ImageTag")
[IO.File]::WriteAllText($settings, $content, [Text.UTF8Encoding]::new($false))

$archive = Join-Path $OutputDirectory 'images.tar'
Invoke-Docker (@('image', 'save', '--output', $archive) + $images)
$hashes = [ordered]@{}
foreach ($name in (@('images.tar') + $payloads)) {
    $hashes[$name] = (Get-FileHash -LiteralPath (Join-Path $OutputDirectory $name) -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifest = [ordered]@{
    format = 'carhire-docker-bundle-v1'
    platform = $Platform
    imageTag = $ImageTag
    createdAt = [DateTime]::UtcNow.ToString('o')
    images = $identities
    files = $hashes
}
$json = $manifest | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'bundle.json'), $json, [Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
try {
    $folder = Split-Path $OutputDirectory -Leaf
    foreach ($name in (@('bundle.json', 'images.tar') + $payloads)) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $OutputDirectory $name), "$folder/$name", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Output "Portable bundle: $zipPath"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash)"
