[CmdletBinding()]
param(
    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
    [string]$ProjectName = 'carhire'
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & docker compose --project-name $ProjectName --env-file settings.env --file compose.yaml down
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose shutdown failed.' }
    Write-Output 'Stopped. Database, attachments and secret files are retained.'
} finally {
    Pop-Location
}
