from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass

import httpx
import psycopg

from beacon_pipeline.config import NYC_BBOX, Settings

STREET_TREE_DATASET_ID = "uvpi-gqnh"
STREET_TREE_RESOURCE_URL = (
    f"https://data.cityofnewyork.us/resource/{STREET_TREE_DATASET_ID}.json"
)
TREE_PAGE_SIZE = 50_000
TREE_BUFFER_M = 15.0
TREE_FIELDS = "tree_id,spc_latin,spc_common,status,tree_dbh,latitude,longitude"


@dataclass(frozen=True)
class StreetTree:
    tree_id: int
    species_latin: str | None
    species_common: str | None
    genus: str | None
    status: str
    dbh_inches: float | None
    longitude: float
    latitude: float


@dataclass(frozen=True)
class StreetTreeScoreResult:
    tree_count: int
    allergenic_tree_count: int
    segment_count: int
    shaded_segment_count: int
    pollen_segment_count: int


def refresh_street_tree_scores(settings: Settings) -> StreetTreeScoreResult:
    tree_count = ingest_street_trees(settings)
    _replace_tree_samples(settings.database_url)
    _refresh_tree_percentiles(settings.database_url)
    return street_tree_statistics(settings.database_url, tree_count)


def ingest_street_trees(settings: Settings) -> int:
    headers = (
        {"X-App-Token": settings.nyc_open_data_app_token}
        if settings.nyc_open_data_app_token
        else {}
    )
    count = 0
    with (
        httpx.Client(headers=headers, follow_redirects=True, timeout=60.0) as client,
        psycopg.connect(settings.database_url) as connection,
        connection.cursor() as cursor,
    ):
        cursor.execute(
            """
            CREATE TEMP TABLE street_tree_stage
              (LIKE street_tree INCLUDING DEFAULTS INCLUDING GENERATED
                                INCLUDING CONSTRAINTS)
            ON COMMIT DROP
            """
        )
        with cursor.copy(
            """
            COPY street_tree_stage
              (tree_id, species_latin, species_common, genus, status,
               dbh_inches, geom)
            FROM STDIN
            """
        ) as copy:
            for trees in fetch_street_tree_batches(client):
                for tree in trees:
                    copy.write_row(
                        (
                            tree.tree_id,
                            tree.species_latin,
                            tree.species_common,
                            tree.genus,
                            tree.status,
                            tree.dbh_inches,
                            f"SRID=4326;POINT({tree.longitude} {tree.latitude})",
                        )
                    )
                    count += 1

        if count == 0:
            raise RuntimeError("NYC Open Data returned no living street trees")
        cursor.execute("TRUNCATE street_tree")
        cursor.execute(
            """
            INSERT INTO street_tree
              (tree_id, species_latin, species_common, genus, status,
               dbh_inches, geom)
            SELECT tree_id, species_latin, species_common, genus, status,
                   dbh_inches, geom
            FROM street_tree_stage
            """
        )
        cursor.execute("ANALYZE street_tree")
        connection.commit()
    return count


def fetch_street_tree_batches(
    client: httpx.Client,
    *,
    page_size: int = TREE_PAGE_SIZE,
) -> Iterator[list[StreetTree]]:
    if page_size <= 0:
        raise ValueError("page_size must be positive")

    last_tree_id = 0
    while True:
        response = client.get(
            STREET_TREE_RESOURCE_URL,
            params={
                "$select": TREE_FIELDS,
                "$where": (
                    "status='Alive' AND latitude IS NOT NULL "
                    "AND longitude IS NOT NULL "
                    f"AND tree_id > {last_tree_id}"
                ),
                "$order": "tree_id",
                "$limit": page_size,
            },
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, list):
            raise TypeError("Street tree response must be a list")
        if not payload:
            return

        trees = [tree for row in payload if (tree := parse_street_tree(row))]
        if trees:
            yield trees

        next_tree_id = int(payload[-1]["tree_id"])
        if next_tree_id <= last_tree_id:
            raise RuntimeError("Street tree pagination did not advance")
        last_tree_id = next_tree_id
        if len(payload) < page_size:
            return


def parse_street_tree(row: object) -> StreetTree | None:
    if not isinstance(row, dict) or row.get("status") != "Alive":
        return None
    try:
        tree_id = int(row["tree_id"])
        longitude = float(row["longitude"])
        latitude = float(row["latitude"])
    except (KeyError, TypeError, ValueError):
        return None
    west, south, east, north = NYC_BBOX
    if not (west <= longitude <= east and south <= latitude <= north):
        return None

    species_latin = _optional_text(row.get("spc_latin"))
    species_common = _optional_text(row.get("spc_common"))
    dbh_inches = _optional_float(row.get("tree_dbh"))
    genus = species_latin.split(maxsplit=1)[0] if species_latin else None
    return StreetTree(
        tree_id=tree_id,
        species_latin=species_latin,
        species_common=species_common,
        genus=genus,
        status="Alive",
        dbh_inches=dbh_inches,
        longitude=longitude,
        latitude=latitude,
    )


def street_tree_statistics(
    database_url: str,
    tree_count: int | None = None,
) -> StreetTreeScoreResult:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT
              (SELECT count(*)::bigint FROM street_tree),
              (SELECT count(*)::bigint
               FROM street_tree AS tree
               JOIN allergenic_tree_genus AS allergen USING (genus)),
              count(*)::bigint,
              count(*) FILTER (WHERE tree_count > 0)::bigint,
              count(*) FILTER (WHERE allergen_weight > 0)::bigint
            FROM segment_tree_sample
            """
        )
        row = cursor.fetchone()
    if row is None:
        raise RuntimeError("Street tree statistics query returned no row")
    if tree_count is not None and row[0] != tree_count:
        raise RuntimeError("Street tree load count did not match the database")
    return StreetTreeScoreResult(*row)


def _replace_tree_samples(database_url: str) -> None:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute("SET LOCAL work_mem = '256MB'")
        cursor.execute(
            """
            CREATE TEMP TABLE segment_projected ON COMMIT DROP AS
            SELECT id, length_m, ST_Transform(geom, 2263) AS geom
            FROM segment
            """
        )
        cursor.execute(
            "CREATE INDEX segment_projected_geom_gix "
            "ON segment_projected USING GIST (geom)"
        )
        cursor.execute("ANALYZE segment_projected")
        cursor.execute("TRUNCATE segment_tree_sample")
        cursor.execute(
            """
            INSERT INTO segment_tree_sample
              (segment_id, tree_count, allergen_weight, trees_per_100m,
               pollen_weight_per_100m, computed_at)
            SELECT
              segment.id,
              count(tree.tree_id)::integer,
              coalesce(sum(allergen.allergenicity_rating), 0)::real,
              (count(tree.tree_id) * 100.0 / segment.length_m)::real,
              (coalesce(sum(allergen.allergenicity_rating), 0) * 100.0
                 / segment.length_m)::real,
              now()
            FROM segment_projected AS segment
            LEFT JOIN street_tree AS tree
              ON ST_DWithin(tree.geom_2263, segment.geom, %s)
            LEFT JOIN allergenic_tree_genus AS allergen USING (genus)
            GROUP BY segment.id, segment.length_m
            """,
            (TREE_BUFFER_M,),
        )
        connection.commit()


def _refresh_tree_percentiles(database_url: str) -> None:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            WITH ranked AS (
              SELECT
                segment_id,
                PERCENT_RANK() OVER (ORDER BY trees_per_100m) * 100 AS shade,
                PERCENT_RANK() OVER (ORDER BY pollen_weight_per_100m) * 100 AS pollen
              FROM segment_tree_sample
            )
            INSERT INTO segment_static_score
              (segment_id, shade_benefit, pollen_source, computed_at)
            SELECT segment_id, shade, pollen, now()
            FROM ranked
            ON CONFLICT (segment_id) DO UPDATE SET
              shade_benefit = EXCLUDED.shade_benefit,
              pollen_source = EXCLUDED.pollen_source,
              computed_at = EXCLUDED.computed_at
            """
        )
        connection.commit()


def _optional_text(value: object) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    return value.strip()


def _optional_float(value: object) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
