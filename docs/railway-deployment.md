# Railway backend deployment

Production frontend: <https://beacon-navigation.vercel.app>

Beacon's API and data services run on Railway. The frontend is deployed to
Vercel:

```text
browser -> Vercel web app
              |
              +-- /api/* -> beacon-api (public Railway domain)
                                  |-- PostGIS (private)
                                  +-- Redis (private)
```

The Vercel rewrite keeps HTTP Basic credentials and analysis streams on the web
app's origin. The API needs a public Railway domain for the rewrite upstream,
but browser code does not call that domain directly and no CORS configuration
is required.

## Before deploying

- The included infrastructure fits Railway's 500 MB Trial volume. The runtime
  export omits pipeline-only staging rows and stores the industrial proximity
  routing flag directly in `segment_static_score`.
- Export the local runtime database with `scripts/export-runtime-data.sh`.
- The clipped OSM extract is published as the `beacon-data-v1` GitHub release
  asset and is ready for the API build variable.
- Install and authenticate the Railway CLI with `npm i -g @railway/cli` and
  `railway login`.

Do not commit database dumps, provider keys, or the OSM extract.

## 1. Create the project and data services

Create a Railway project named `beacon-navigation`. Add these services in its
production environment:

1. A **PostGIS** template from the Railway template marketplace. The regular
   PostgreSQL template does not include the extension Beacon requires.
2. Railway's **Redis** database.

Both database services must keep their generated persistent volumes. Keep them
private; only the temporary Postgres TCP proxy used for the initial restore
needs to be reachable from this computer.

## 2. Create `beacon-api`

Create a service from this GitHub repository and name it `beacon-api`.

- Root directory: `/`
- Infrastructure: managed by `.railway/railway.ts`
- Memory: at least 2 GB
- Volume mount path: `/data`
- Public domain: required for the Vercel `/api` rewrite

Add these variables in the service's **Variables** tab. Replace `PostGIS` and
`Redis` if the canvas uses different service names; Railway autocompletes
reference variables.

```dotenv
DATABASE_URL=jdbc:postgresql://${{PostGIS.PGHOST}}:${{PostGIS.PGPORT}}/${{PostGIS.PGDATABASE}}
POSTGRES_USER=${{PostGIS.PGUSER}}
POSTGRES_PASSWORD=${{PostGIS.PGPASSWORD}}
REDIS_URL=${{Redis.REDIS_URL}}
OSM_EXTRACT_URL=https://github.com/sshashank11/beacon-navigation-app/releases/download/beacon-data-v1/nyc.osm.pbf
BEACON_ROUTING_ENABLED=true
```

`DATABASE_URL` deliberately starts with `jdbc:postgresql://`. Railway's native
Postgres URL starts with `postgresql://`, which Spring Boot's JDBC datasource
does not accept.

The volume caches GraphHopper data. Railway mounts volumes as root, so also set:

```dotenv
RAILWAY_RUN_UID=0
```

The container startup shim uses that override to repair the volume ownership,
then starts the Java process as the unprivileged `beacon` user.

The first deployment downloads the 86 MB OSM extract during the image build and
imports about 1.1 million graph edges at startup. The health check allows five
minutes for that first import.

## 3. Restore the runtime database

Open the PostGIS service's public TCP proxy temporarily. Use its generated
`PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, and `PGDATABASE` values with
`pg_restore`:

On a 500 MB volume, temporarily set the PostGIS start command to the following
before restoring. This reduces transient WAL usage during the one-time import:

```text
docker-entrypoint.sh postgres -c wal_level=minimal -c max_wal_senders=0 -c min_wal_size=32MB -c max_wal_size=64MB
```

```powershell
$env:PGPASSWORD = '<Railway PGPASSWORD>'
pg_restore --clean --if-exists --no-owner --no-privileges `
  --jobs 2 `
  --host '<Railway PGHOST>' `
  --port '<Railway PGPORT>' `
  --username '<Railway PGUSER>' `
  --dbname '<Railway PGDATABASE>' `
  .\beacon-runtime.dump
Remove-Item Env:PGPASSWORD
```

Flyway runs when `beacon-api` starts, so restoring into an empty database before
the first successful API boot is the cleanest order. If Flyway has already
created empty tables, the `--clean --if-exists` flags replace them with the dump.
Disable the Postgres TCP proxy after the restore, remove the temporary start
command, and redeploy PostGIS to restore normal WAL durability before starting
the API.

## 4. Deploy the frontend

Deploy the `web` directory to Vercel using the checked-in `vercel.json`. Set its
API rewrite destination to the public `beacon-api` Railway domain. The current
production procedure is documented in
[`vercel-deployment.md`](vercel-deployment.md).

The existing Railway `beacon-web` Caddy service may remain online as a rollback
endpoint. If it is retained, configure it with:

```dotenv
API_UPSTREAM=http://${{beacon-api.RAILWAY_PRIVATE_DOMAIN}}:${{beacon-api.PORT}}
```

Leave `VITE_API_BASE_URL` unset. Caddy proxies same-origin `/api/*` traffic over
Railway's private network, while Vercel uses the public API domain configured in
`web/vercel.json`.

## 5. Optional server speech

Production uses the browser voice and does not require another service. The
server speech integration remains optional; if it is enabled later, add a MinIO
service with a volume at `/data` and configure `beacon-api` with the selected
provider credentials.

```dotenv
MINIO_ENDPOINT=http://${{MinIO.RAILWAY_PRIVATE_DOMAIN}}:9000
MINIO_ACCESS_KEY=${{MinIO.MINIO_ROOT_USER}}
MINIO_SECRET_KEY=${{MinIO.MINIO_ROOT_PASSWORD}}
MINIO_BUCKET=beacon
FISH_AUDIO_KEY=<sealed secret>
FISH_AUDIO_REFERENCE_ID=<optional voice id>
```

Seal provider secrets after entering them. The Python pipeline is not required
for serving routes because its scores are already in the restored database; run
it separately only when refreshing source data and scores.

## 6. Verify production

1. Confirm `beacon-api` logs show Flyway success and a loaded or imported graph.
2. Open `https://beacon-navigation.vercel.app`; it should return HTTP 200.
3. Open `https://beacon-navigation.vercel.app/api/v1/conditions/now`; it should
   return JSON through the Vercel rewrite.
4. Load the Vercel domain, register a temporary account, compare a short route,
   start analysis, and delete the account.
5. Redeploy `beacon-api` once and confirm the graph loads from `/data` rather
   than importing the PBF again.

The August 22, 2026 deployment passed these checks with a 244 MB database,
580,211 scored segments, a 28-second first API start, and a 10.5-second restart
from the persisted graph.

Railway deployments created from GitHub will rebuild automatically. The watch
patterns in `.railway/railway.ts` keep frontend-only changes from rebuilding
the API and vice versa. Run `railway config plan` before applying any future
infrastructure edits.
