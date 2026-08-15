from __future__ import annotations

import argparse

from beacon_pipeline.config import load_settings
from beacon_pipeline.flows import serve_flows
from beacon_pipeline.ingest.airnow import ingest_airnow
from beacon_pipeline.ingest.dob_permits import ingest_dob_permits
from beacon_pipeline.ingest.nws import ingest_nws
from beacon_pipeline.ingest.openaq import ingest_openaq
from beacon_pipeline.ingest.pollen import ingest_pollen
from beacon_pipeline.model.hazard_fields import build_hazard_fields, refresh_construction_scores


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
            "serve",
        ],
    )
    args = parser.parse_args()
    settings = load_settings()

    if args.job == "serve":
        serve_flows()
        return
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
