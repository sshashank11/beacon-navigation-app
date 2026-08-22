# Vercel frontend deployment

Production URL: <https://beacon-navigation.vercel.app>

Vercel hosts the static React/Vite application. The checked-in
`web/vercel.json` sends `/api/*` to the public Railway API domain and sends all
other unmatched paths to `index.html` for client-side routing.

```text
browser -> beacon-navigation.vercel.app
              |
              +-- /api/* -> beacon-api-production-4b6b.up.railway.app
                                      |-- PostGIS (private Railway network)
                                      +-- Redis (private Railway network)
```

The browser continues to use relative `/api` URLs, so authentication and event
streams stay same-origin. Do not put provider secrets in Vercel or `VITE_*`
variables; those belong on the Railway API service.

## Project settings

- Project: `beacon-navigation`
- Framework preset: Vite
- Root directory: `web`
- Build command: `npm run build`
- Output directory: `dist`
- Production branch: `main`
- Git repository: `sshashank11/beacon-navigation-app`

The GitHub repository is connected, so pushes to `main` deploy production and
other branches receive preview deployments.

## Manual deployment

Install and authenticate the Vercel CLI, then run from the repository root:

```powershell
Set-Location web
npm ci
npm run lint
npm run build
vercel deploy --prod
```

The `.vercel` directory and Vercel-generated local environment files are
ignored. Do not commit them.

## Verify production

1. Open <https://beacon-navigation.vercel.app> and complete the disclaimer.
2. Open a nested client-side path and confirm the SPA shell returns HTTP 200.
3. Request `/api/v1/conditions/now` through the Vercel domain and confirm JSON
   is returned.
4. Compare a short walking route and confirm fastest, balanced, and cleanest
   variants are returned.
5. Confirm hashed files under `/assets` have an immutable cache policy and the
   API response remains uncached.

The August 22, 2026 production deployment passed the app-shell, static-asset,
SPA-fallback, live-conditions, and route-comparison checks. Production speech
uses the browser's speech synthesis; Fish Audio is intentionally not
configured.
