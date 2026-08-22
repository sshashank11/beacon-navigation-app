#!/usr/bin/env bash
# Dumps only what a deployed API needs to serve routes.
#
# The local database is about 830 MB, but roughly 380 MB of that is pipeline
# staging data: the street tree census, the per-source sample tables, and the
# traffic road table are inputs used to compute percentile scores, and nothing
# reads them at request time. Excluding them brings the restored database below
# Railway's 500 MB Trial volume limit.
#
# Usage: scripts/export-runtime-data.sh [output.dump]
set -euo pipefail

OUTPUT="${1:-beacon-runtime.dump}"
CONTAINER="${BEACON_PG_CONTAINER:-beacon-postgres}"
DB_USER="${POSTGRES_USER:-beacon}"
DB_NAME="${POSTGRES_DB:-beacon}"

# Staging tables. Re-creatable by re-running the pipeline refresh jobs.
EXCLUDED=(
  street_tree
  segment_nyccas_sample
  segment_tree_sample
  segment_traffic_sample
  segment_industrial_sample
  traffic_road
)

ARGS=()
for table in "${EXCLUDED[@]}"; do
  ARGS+=(--exclude-table-data="public.${table}")
done

echo "Dumping ${DB_NAME} without staging table data..."
docker exec -i "${CONTAINER}" pg_dump \
  -U "${DB_USER}" -d "${DB_NAME}" \
  --format=custom --no-owner --no-privileges \
  "${ARGS[@]}" > "${OUTPUT}"

echo "Wrote ${OUTPUT} ($(du -h "${OUTPUT}" | cut -f1))"
echo
echo "Restore into the deployed database with:"
echo "  pg_restore --no-owner --no-privileges -d \"\$DATABASE_URL\" ${OUTPUT}"
echo
echo "The target must have PostGIS available: CREATE EXTENSION postgis;"
echo "Schema definitions are excluded for the staging tables' data only, so"
echo "the pipeline can repopulate them later without a migration."
