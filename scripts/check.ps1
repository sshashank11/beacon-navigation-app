$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) "beacon-navigation-check"
$apiBuildDirectory = Join-Path $temporaryRoot "api-build"
$apiProjectCache = Join-Path $temporaryRoot "api-project-cache"

function Invoke-Check {
    param(
        [Parameter(Mandatory)]
        [string] $Name,
        [Parameter(Mandatory)]
        [string] $Directory,
        [Parameter(Mandatory)]
        [scriptblock] $Command
    )

    Write-Host "`n==> $Name" -ForegroundColor Cyan
    Push-Location (Join-Path $repositoryRoot $Directory)
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) {
            throw "$Name failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

Invoke-Check "API tests" "api" {
    .\gradlew.bat test --no-daemon "-PbuildDir=$apiBuildDirectory" --project-cache-dir $apiProjectCache
}
Invoke-Check "Pipeline tests" "pipeline" { uv run --with pytest pytest }
Invoke-Check "Web lint" "web" { npm run lint }
Invoke-Check "Web build" "web" { npm run build }

Write-Host "`nAll Beacon checks passed." -ForegroundColor Green
