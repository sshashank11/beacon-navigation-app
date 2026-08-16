from __future__ import annotations

import argparse
from pathlib import Path

from beacon_pipeline.config import load_settings
from beacon_pipeline.elevation import enrich_segment_elevation
from beacon_pipeline.epa_facilities import refresh_industrial_scores
from beacon_pipeline.extract_segments import DEFAULT_OSM_PATH, extract_segments
from beacon_pipeline.flows import serve_flows
from beacon_pipeline.ingest.airnow import ingest_airnow
from beacon_pipeline.ingest.dob_permits import ingest_dob_permits
from beacon_pipeline.ingest.mapillary import (
    NOMAD_DEMO_CORRIDOR_BBOX,
    harvest_mapillary,
)
from beacon_pipeline.ingest.nws import ingest_nws
from beacon_pipeline.ingest.openaq import ingest_openaq
from beacon_pipeline.ingest.pollen import ingest_pollen
from beacon_pipeline.model.hazard_fields import (
    build_hazard_fields,
    refresh_construction_scores,
)
from beacon_pipeline.nyccas import refresh_nyccas_scores
from beacon_pipeline.osm import DEFAULT_OSM_DATA_DIR, prepare_osm_data
from beacon_pipeline.street_trees import refresh_street_tree_scores
from beacon_pipeline.traffic import refresh_traffic_scores


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
            "harvest-mapillary",
            "preview-segmentation",
            "score-images",
            "refresh-image-segment-features",
            "refresh-construction-scores",
            "build-hazard-fields",
            "prepare-osm",
            "extract-segments",
            "enrich-elevation",
            "refresh-nyccas-scores",
            "refresh-street-tree-scores",
            "refresh-industrial-scores",
            "refresh-traffic-scores",
            "serve",
        ],
    )
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_OSM_DATA_DIR)
    parser.add_argument("--osm-path", type=Path, default=DEFAULT_OSM_PATH)
    parser.add_argument("--dem-dir", type=Path)
    parser.add_argument("--raster-dir", type=Path)
    parser.add_argument("--epa-data-dir", type=Path)
    parser.add_argument("--tri-year", type=int)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("../data/vision-preview"),
    )
    parser.add_argument("--sample-limit", type=int, default=20)
    parser.add_argument(
        "--limit",
        type=int,
        help="cap how many unscored images score-images processes in one run",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        help="inference batch size; defaults per job (4 preview, 32 scoring)",
    )
    parser.add_argument("--device")
    parser.add_argument("--force-download", action="store_true")
    parser.add_argument(
        "--bbox",
        type=float,
        nargs=4,
        metavar=("WEST", "SOUTH", "EAST", "NORTH"),
        help="override the default Mapillary demo corridor",
    )
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
    if args.job == "extract-segments":
        result = extract_segments(settings.database_url, args.osm_path)
        print(
            "extract-segments: wrote "
            f"{result.segment_count:,} segments from {result.way_count:,} ways "
            f"(max {result.maximum_length_m:.2f}m, avg {result.average_length_m:.2f}m)"
        )
        return
    if args.job == "enrich-elevation":
        result = enrich_segment_elevation(
            settings.database_url,
            args.dem_dir or settings.elevation_raster_dir,
            force_download=args.force_download,
        )
        print(
            "enrich-elevation: graded "
            f"{result.graded_count:,}/{result.segment_count:,} segments "
            f"({result.minimum_grade_pct:.2f}% to {result.maximum_grade_pct:.2f}%, "
            f"{result.clamped_count:,} clamped)"
        )
        return
    if args.job == "refresh-nyccas-scores":
        result = refresh_nyccas_scores(
            settings.database_url,
            args.raster_dir or settings.nyccas_raster_dir,
            force_download=args.force_download,
        )
        years = ", ".join(
            f"{pollutant} year {year}"
            for pollutant, year in result.raster_years.items()
        )
        print(
            "refresh-nyccas-scores: sampled "
            f"{result.sampled_count:,}/{result.segment_count:,} segments "
            f"(PM2.5 {result.pm25_count:,}, NO2 {result.no2_count:,}, "
            f"ozone {result.ozone_count:,}; {years})"
        )
        return
    if args.job == "refresh-street-tree-scores":
        result = refresh_street_tree_scores(settings)
        print(
            "refresh-street-tree-scores: loaded "
            f"{result.tree_count:,} living trees "
            f"({result.allergenic_tree_count:,} allergenic); scored "
            f"{result.shaded_segment_count:,}/{result.segment_count:,} segments "
            f"for shade and {result.pollen_segment_count:,} for pollen"
        )
        return
    if args.job == "refresh-industrial-scores":
        result = refresh_industrial_scores(
            settings,
            data_dir=args.epa_data_dir,
            reporting_year=args.tri_year,
            force_download=args.force_download,
        )
        print(
            "refresh-industrial-scores: loaded "
            f"{result.facility_count:,} EPA facilities; scored "
            f"{result.exposed_segment_count:,}/{result.segment_count:,} segments "
            f"(max raw kernel {result.maximum_raw_kernel:.3f})"
        )
        return
    if args.job == "refresh-traffic-scores":
        result = refresh_traffic_scores(settings.database_url, args.osm_path)
        print(
            "refresh-traffic-scores: loaded "
            f"{result.road_count:,} weighted OSM roads; scored "
            f"{result.exposed_segment_count:,}/{result.segment_count:,} segments "
            f"(max raw kernel {result.maximum_raw_kernel:.3f})"
        )
        return
    if args.job == "preview-segmentation":
        from beacon_pipeline.vision.segmentation import preview_segmentations

        result = preview_segmentations(
            settings.database_url,
            args.output_dir,
            sample_limit=args.sample_limit,
            batch_size=args.batch_size or 4,
            device=args.device,
        )
        print(
            "preview-segmentation: rendered "
            f"{result.sample_count} image(s) with {result.model_id} on "
            f"{result.device} to {result.output_dir}"
        )
        print("detected classes: " + ", ".join(result.detected_classes))
        return
    if args.job == "score-images":
        from beacon_pipeline.vision.score_images import (
            DEFAULT_BATCH_SIZE,
            score_unscored_images,
        )

        def report(done: int, total: int) -> None:
            print(f"score-images: {done:,}/{total:,} scored", flush=True)

        scored = score_unscored_images(
            settings.database_url,
            limit=args.limit,
            batch_size=args.batch_size or DEFAULT_BATCH_SIZE,
            device=args.device,
            progress=report,
        )
        print(
            "score-images: scored "
            f"{scored.scored_count:,} image(s) as {scored.model_version} "
            f"({scored.pending_count:,} still pending)"
        )
        return
    if args.job == "refresh-image-segment-features":
        from beacon_pipeline.vision.segment_features import (
            refresh_image_segment_features,
        )

        features = refresh_image_segment_features(settings.database_url)
        print(
            "refresh-image-segment-features: aggregated "
            f"{features.frame_count:,} daylight frame(s) into "
            f"{features.segment_count:,} segment(s); "
            f"held back {features.excluded_dark_count:,} frame(s) captured "
            "outside daylight"
        )
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
    elif args.job == "harvest-mapillary":
        count = harvest_mapillary(
            settings,
            tuple(args.bbox) if args.bbox else NOMAD_DEMO_CORRIDOR_BBOX,
        )
    elif args.job == "refresh-construction-scores":
        count = refresh_construction_scores(settings.database_url)
    else:
        count = build_hazard_fields(settings)

    print(f"{args.job}: wrote {count} row(s)")
