param(
    [string] $ApiBaseUrl = "http://127.0.0.1:8080",
    [ValidateRange(3, 100)]
    [int] $Samples = 15,
    [ValidateRange(4, 40)]
    [int] $AreaCount = 20,
    [ValidateRange(1, 5000)]
    [double] $LatencyBudgetMs = 200
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$cacheKey = "beacon:hazard-fields:current"
$fixtureSource = "beacon-smoke-demo"
$cleanObservedAt = [DateTimeOffset]::UtcNow
$smokeObservedAt = $cleanObservedAt.AddSeconds(1)
$cleanTimestamp = $cleanObservedAt.ToString("O")
$smokeTimestamp = $smokeObservedAt.ToString("O")
$fixtureInstalled = $false

if ($AreaCount % 4 -ne 0) {
    throw "AreaCount must be a multiple of four"
}

function Invoke-DatabaseSql {
    param([Parameter(Mandatory)][string] $Sql)

    Push-Location $repositoryRoot
    try {
        $Sql | & docker compose exec -T postgres psql `
            -v ON_ERROR_STOP=1 -U beacon -d beacon | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Postgres command failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Clear-HazardCache {
    Push-Location $repositoryRoot
    try {
        & docker compose exec -T redis redis-cli DEL $cacheKey | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Redis cache clear failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-RoutePreview {
    param([Parameter(Mandatory)][hashtable] $Payload)

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod `
        -Uri "$ApiBaseUrl/api/v1/profiles/preview" `
        -Method Post `
        -ContentType "application/json" `
        -Body ($Payload | ConvertTo-Json -Depth 10 -Compress) `
        -TimeoutSec 30
    $watch.Stop()
    return [pscustomobject]@{
        Response = $response
        Milliseconds = $watch.Elapsed.TotalMilliseconds
    }
}

function Get-Percentile {
    param(
        [Parameter(Mandatory)][double[]] $Values,
        [Parameter(Mandatory)][double] $Percentile
    )

    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
    return $sorted[$index]
}

function New-RoutePayload {
    param([Parameter(Mandatory)][hashtable] $Weights)

    return @{
        origin = @(40.7484, -73.9857)
        destination = @(40.7359, -73.9911)
        mode = "foot"
        preset = "none"
        weights = $Weights
        hard_avoids = @()
        max_grade_pct = 20.0
        detour_tolerance = 0.5
        conservatism = 1.0
    }
}

$availableHazards = @(
    "pm25",
    "ozone",
    "no2",
    "traffic_prox",
    "construction",
    "industrial_prox",
    "grade",
    "heat",
    "cold_air",
    "humidity"
)
$hazards = @($availableHazards | Select-Object -First ($AreaCount / 4))
$hazardValues = for ($index = 0; $index -lt $hazards.Count; $index++) {
    "('$($hazards[$index])', $index)"
}
$hazardSql = $hazardValues -join ",`n    "
$allWeights = @{}
$hazards | ForEach-Object { $allWeights[$_] = 3.0 }
$pm25Payload = New-RoutePayload -Weights @{ pm25 = 3.0 }
$benchmarkPayload = New-RoutePayload -Weights $allWeights

try {
    Invoke-RestMethod -Uri "$ApiBaseUrl/actuator/health" -TimeoutSec 5 | Out-Null

    $cleanSql = @"
BEGIN;
INSERT INTO citywide_reading
  (hazard, station_id, observed_at, source, value, unit)
VALUES
  ('pm25', 'smoke-demo', '$cleanTimestamp', '$fixtureSource', 8.0, 'ug/m3');

WITH hazards(hazard, hazard_index) AS (
  VALUES
    $hazardSql
), bands AS (
  SELECT hazard, hazard_index, generate_series(1, 4) AS severity
  FROM hazards
)
INSERT INTO hazard_field
  (hazard, observed_at, band_min, band_max, severity, geom)
SELECT
  hazard,
  '$cleanTimestamp',
  (severity - 1) * 25.0,
  severity * 25.0,
  severity,
  ST_Multi(ST_MakeEnvelope(
    -74.24 + hazard_index * 0.002 + severity * 0.0002,
    40.50 + hazard_index * 0.001,
    -74.2395 + hazard_index * 0.002 + severity * 0.0002,
    40.5005 + hazard_index * 0.001,
    4326
  ))
FROM bands;
COMMIT;
"@
    Invoke-DatabaseSql -Sql $cleanSql
    $fixtureInstalled = $true
    Clear-HazardCache

    $clean = Invoke-RoutePreview -Payload $pm25Payload
    $cleanGeometry = $clean.Response.cleanest.route.geometry | ConvertTo-Json -Depth 10 -Compress
    $escapedGeometry = $cleanGeometry.Replace("'", "''")

    $smokeSql = @"
BEGIN;
INSERT INTO citywide_reading
  (hazard, station_id, observed_at, source, value, unit)
VALUES
  ('pm25', 'smoke-demo', '$smokeTimestamp', '$fixtureSource', 250.0, 'ug/m3');

WITH route AS (
  SELECT ST_SetSRID(ST_GeomFromGeoJSON('$escapedGeometry'), 4326) AS geom
), hazards(hazard, hazard_index) AS (
  VALUES
    $hazardSql
), bands AS (
  SELECT hazard, hazard_index, generate_series(1, 4) AS severity
  FROM hazards
)
INSERT INTO hazard_field
  (hazard, observed_at, band_min, band_max, severity, geom)
SELECT
  hazard,
  '$smokeTimestamp',
  (severity - 1) * 25.0,
  severity * 25.0,
  severity,
  CASE
    WHEN hazard = 'pm25' AND severity = 4 THEN
      ST_Multi(ST_CollectionExtract(ST_Buffer(route.geom::geography, 30.0)::geometry, 3))
    ELSE
      ST_Multi(ST_MakeEnvelope(
        -74.24 + hazard_index * 0.002 + severity * 0.0002,
        40.50 + hazard_index * 0.001,
        -74.2395 + hazard_index * 0.002 + severity * 0.0002,
        40.5005 + hazard_index * 0.001,
        4326
      ))
  END
FROM bands
CROSS JOIN route;
COMMIT;
"@
    Invoke-DatabaseSql -Sql $smokeSql
    Clear-HazardCache

    $currentAreas = Invoke-RestMethod -Uri "$ApiBaseUrl/api/v1/hazard-fields/current" -TimeoutSec 10
    $areaCount = $currentAreas.Count
    if ($areaCount -ne $AreaCount) {
        throw "Expected $AreaCount attached hazard areas, found $areaCount"
    }

    $smoke = Invoke-RoutePreview -Payload $pm25Payload
    $smokeGeometry = $smoke.Response.cleanest.route.geometry | ConvertTo-Json -Depth 10 -Compress
    $distanceShift = [Math]::Abs(
        $smoke.Response.cleanest.route.distance_m - $clean.Response.cleanest.route.distance_m
    )
    if ($smokeGeometry -eq $cleanGeometry -or $distanceShift -lt 10.0) {
        throw "Smoke-day route did not shift measurably from the clear-day route"
    }

    1..3 | ForEach-Object { Invoke-RoutePreview -Payload $benchmarkPayload | Out-Null }
    [double[]] $latencies = 1..$Samples | ForEach-Object {
        (Invoke-RoutePreview -Payload $benchmarkPayload).Milliseconds
    }
    $median = Get-Percentile -Values $latencies -Percentile 0.50
    $p95 = Get-Percentile -Values $latencies -Percentile 0.95

    Write-Host "Smoke-day route shift: $([Math]::Round($distanceShift, 1)) m" -ForegroundColor Green
    Write-Host "Attached hazard areas: $areaCount"
    Write-Host "Routing latency ($Samples samples): median $([Math]::Round($median, 1)) ms, p95 $([Math]::Round($p95, 1)) ms"

    if ($p95 -gt $LatencyBudgetMs) {
        throw "Routing p95 $([Math]::Round($p95, 1)) ms exceeds the $LatencyBudgetMs ms budget"
    }
} finally {
    if ($fixtureInstalled) {
        $cleanupSql = @"
BEGIN;
DELETE FROM citywide_reading WHERE source = '$fixtureSource';
DELETE FROM hazard_field
WHERE observed_at IN ('$cleanTimestamp', '$smokeTimestamp');
COMMIT;
"@
        Invoke-DatabaseSql -Sql $cleanupSql
        Clear-HazardCache
    }
}
