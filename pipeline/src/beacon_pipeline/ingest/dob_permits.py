from __future__ import annotations

from collections.abc import Iterable
from datetime import date
from typing import Any

import httpx

from beacon_pipeline.config import Settings
from beacon_pipeline.db import ConstructionPermit, replace_construction_permits


DOB_NOW_DATASET = "rbx6-tga4"
BUILDING_CENTROIDS_DATASET = "u9wf-3gbt"
SOURCE = "nyc_dob_now"
PAGE_SIZE = 50_000


def ingest_dob_permits(settings: Settings) -> int:
    headers = {"X-App-Token": settings.nyc_open_data_app_token} if settings.nyc_open_data_app_token else {}
    with httpx.Client(base_url="https://data.cityofnewyork.us", headers=headers, timeout=60.0) as client:
        source_rows = _fetch_active_permits(client, date.today())
        centroids = _fetch_building_centroids(client, {str(row["bin"]) for row in source_rows})

    permits: list[ConstructionPermit] = []
    for row in source_rows:
        bin_number = str(row["bin"])
        coordinates = centroids.get(bin_number)
        if coordinates is None:
            continue
        longitude, latitude = coordinates
        permits.append(
            ConstructionPermit(
                permit_id=str(row["work_permit"]),
                source=SOURCE,
                bin=bin_number,
                permit_type=str(row["work_type"]),
                issued_at=_parse_date(row.get("issued_date")),
                expires_at=_parse_date(row.get("expired_date")) or date.today(),
                severity=_permit_severity(str(row["work_type"])),
                longitude=longitude,
                latitude=latitude,
            )
        )
    if source_rows and not permits:
        raise RuntimeError("DOB returned active permits, but none matched a building centroid")
    return replace_construction_permits(settings.database_url, permits, SOURCE)


def _fetch_active_permits(client: httpx.Client, today: date) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    offset = 0
    while True:
        response = client.get(
            f"/resource/{DOB_NOW_DATASET}.json",
            params={
                "$select": "work_permit,bin,work_type,issued_date,expired_date",
                "$where": (
                    "permit_status = 'Permit Issued' "
                    f"AND expired_date >= '{today.isoformat()}T00:00:00.000'"
                ),
                "$order": "work_permit",
                "$limit": PAGE_SIZE,
                "$offset": offset,
            },
        )
        response.raise_for_status()
        page = response.json()
        rows.extend(
            row
            for row in page
            if row.get("work_permit") and row.get("bin") and row.get("work_type") and row.get("expired_date")
        )
        if len(page) < PAGE_SIZE:
            break
        offset += PAGE_SIZE
    return rows


def _fetch_building_centroids(
    client: httpx.Client,
    bins: set[str],
) -> dict[str, tuple[float, float]]:
    result: dict[str, tuple[float, float]] = {}
    for batch in _batches(sorted(bins), 100):
        values = ",".join(f"'{value}'" for value in batch)
        response = client.get(
            f"/resource/{BUILDING_CENTROIDS_DATASET}.geojson",
            params={"$select": "bin,the_geom", "$where": f"bin in ({values})", "$limit": 100},
        )
        response.raise_for_status()
        for feature in response.json().get("features", []):
            properties = feature.get("properties") or {}
            geometry = feature.get("geometry") or {}
            coordinates = geometry.get("coordinates") or []
            if properties.get("bin") and len(coordinates) >= 2:
                result[str(properties["bin"])] = (float(coordinates[0]), float(coordinates[1]))
    return result


def _permit_severity(work_type: str) -> int:
    normalized = work_type.casefold()
    if "demolition" in normalized:
        return 4
    if normalized in {
        "earth work",
        "foundation",
        "general construction",
        "structural",
        "support of excavation",
    }:
        return 3
    if "scaffold" in normalized or normalized in {"construction fence", "sidewalk shed"}:
        return 2
    return 1


def _parse_date(value: str | None) -> date | None:
    if not value:
        return None
    return date.fromisoformat(value[:10])


def _batches(values: list[str], size: int) -> Iterable[list[str]]:
    for index in range(0, len(values), size):
        yield values[index : index + size]
