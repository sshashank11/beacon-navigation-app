from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
from shapely.geometry import mapping, shape
from shapely.ops import unary_union


NEW_YORK_PBF_URL = (
    "https://download.geofabrik.de/north-america/us/new-york-latest.osm.pbf"
)
NEW_YORK_PBF_MD5_URL = f"{NEW_YORK_PBF_URL}.md5"
NYC_BOROUGH_BOUNDARY_URL = (
    "https://data.cityofnewyork.us/resource/gthc-hcne.geojson?$limit=5000"
)
OSMIUM_IMAGE = "docker.io/iboates/osmium:1.19.0"
DEFAULT_OSM_DATA_DIR = Path(__file__).resolve().parents[3] / "data" / "osm"


@dataclass(frozen=True)
class OsmPreparationResult:
    source_path: Path
    boundary_path: Path
    extract_path: Path
    file_info: str


def prepare_osm_data(
    data_dir: Path = DEFAULT_OSM_DATA_DIR,
    *,
    force_download: bool = False,
) -> OsmPreparationResult:
    data_dir = data_dir.resolve()
    data_dir.mkdir(parents=True, exist_ok=True)

    source_path = data_dir / "new-york-latest.osm.pbf"
    boundary_path = data_dir / "nyc-boundary.geojson"
    extract_path = data_dir / "nyc.osm.pbf"
    extract_partial = data_dir / "nyc.osm.pbf.part"

    with httpx.Client(follow_redirects=True, timeout=60.0) as client:
        checksum_response = client.get(NEW_YORK_PBF_MD5_URL)
        checksum_response.raise_for_status()
        expected_md5 = _expected_md5(checksum_response.text)
        if force_download or not _md5_matches(source_path, expected_md5):
            _download(client, NEW_YORK_PBF_URL, source_path)
        if not _md5_matches(source_path, expected_md5):
            raise RuntimeError(f"Checksum verification failed for {source_path}")

        response = client.get(NYC_BOROUGH_BOUNDARY_URL)
        response.raise_for_status()
        boundary = dissolve_borough_boundaries(response.json())
        _write_json(boundary_path, boundary)

    _run_osmium(
        data_dir,
        [
            "extract",
            "--polygon",
            boundary_path.name,
            "--set-bounds",
            "--overwrite",
            "--output-format",
            "pbf",
            "--output",
            extract_partial.name,
            source_path.name,
        ],
    )
    if not extract_partial.exists() or extract_partial.stat().st_size < 1_000_000:
        raise RuntimeError(f"Osmium produced an unexpectedly small extract: {extract_partial}")
    extract_partial.replace(extract_path)

    file_info = _run_osmium(
        data_dir,
        ["fileinfo", "--extended", extract_path.name],
        capture_output=True,
    )

    return OsmPreparationResult(source_path, boundary_path, extract_path, file_info)


def dissolve_borough_boundaries(payload: dict[str, Any]) -> dict[str, Any]:
    features = payload.get("features")
    if not isinstance(features, list) or len(features) != 5:
        raise ValueError("Expected exactly five NYC borough boundary features")

    geometries = [shape(feature["geometry"]) for feature in features]
    dissolved = unary_union(geometries)
    if dissolved.is_empty or dissolved.geom_type not in {"Polygon", "MultiPolygon"}:
        raise ValueError("NYC borough boundaries did not produce a polygon")

    return {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "properties": {
                    "name": "New York City",
                    "source": "NYC Open Data gthc-hcne",
                },
                "geometry": mapping(dissolved),
            }
        ],
    }


def _download(client: httpx.Client, url: str, destination: Path) -> None:
    partial = destination.with_name(f"{destination.name}.part")
    with client.stream("GET", url) as response:
        response.raise_for_status()
        with partial.open("wb") as output:
            for chunk in response.iter_bytes(chunk_size=1024 * 1024):
                output.write(chunk)
    partial.replace(destination)


def _expected_md5(checksum_file: str) -> str:
    checksum = checksum_file.split()[0].lower()
    if len(checksum) != 32 or any(char not in "0123456789abcdef" for char in checksum):
        raise ValueError("Geofabrik returned an invalid MD5 checksum")
    return checksum


def _md5_matches(path: Path, expected: str) -> bool:
    if not path.exists():
        return False
    digest = hashlib.md5(usedforsecurity=False)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest() == expected


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    partial = path.with_name(f"{path.name}.part")
    partial.write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
    partial.replace(path)


def _run_osmium(
    data_dir: Path,
    arguments: list[str],
    *,
    capture_output: bool = False,
) -> str:
    command = osmium_command(data_dir, arguments)

    result = subprocess.run(
        command,
        cwd=data_dir,
        check=True,
        text=True,
        capture_output=capture_output,
    )
    return result.stdout


def osmium_command(data_dir: Path, arguments: list[str]) -> list[str]:
    local_osmium = shutil.which("osmium")
    if local_osmium:
        return [local_osmium, *arguments]
    if not shutil.which("docker"):
        raise RuntimeError("Install Osmium or Docker before preparing routing data")
    return [
        "docker",
        "run",
        "--rm",
        "--volume",
        f"{data_dir.resolve()}:/data",
        "--workdir",
        "/data",
        OSMIUM_IMAGE,
        *arguments,
    ]
