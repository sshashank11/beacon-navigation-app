from __future__ import annotations

import argparse
from pathlib import Path

from beacon_pipeline.config import load_settings
from beacon_pipeline.flows import serve_flows
from beacon_pipeline.ingest.airnow import ingest_airnow
from beacon_pipeline.ingest.dob_permits import ingest_dob_permits
from beacon_pipeline.ingest.nws import ingest_nws
from beacon_pipeline.ingest.openaq import ingest_openaq
from beacon_pipeline.ingest.pollen import ingest_pollen
from beacon_pipeline.model.hazard_fields import build_hazard_fields, refresh_construction_scores
from beacon_pipeline.osm import DEFAULT_OSM_DATA_DIR, prepare_osm_data


def main() -> None:
    parser = argparse.ArgumentParser(prog="beacon-pipeline")
    parser.add_argument(
        "job",
        choices=[
            "ingest-openaq",
            "ingest-airnow",
            "ingest-nws",
            "ingest-pollen",
            "ingest-dob-permits",
            "refresh-construction-scores",
            "build-hazard-fields",
            "prepare-osm",
            "serve",
        ],
    )
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_OSM_DATA_DIR)
    parser.add_argument("--force-download", action="store_true")
    args = parser.parse_args()

    if args.job == "prepare-osm":
        result = prepare_osm_data(args.data_dir, force_download=args.force_download)
        print(f"prepare-osm: wrote {result.extract_path}")
        print(result.file_info)
        return
    if args.job == "serve":
        serve_flows()
        return

    settings = load_settings()
    if args.job == "ingest-openaq":
        count = ingest_openaq(settings)
    elif args.job == "ingest-airnow":
        count = ingest_airnow(settings)
    elif args.job == "ingest-nws":
        count = ingest_nws(settings)
    elif args.job == "ingest-pollen":
        count = ingest_pollen(settings)
    elif args.job == "ingest-dob-permits":
        count = ingest_dob_permits(settings)
    elif args.job == "refresh-construction-scores":
        count = refresh_construction_scores(settings.database_url)
    else:
        count = build_hazard_fields(settings)

    print(f"{args.job}: wrote {count} row(s)")
