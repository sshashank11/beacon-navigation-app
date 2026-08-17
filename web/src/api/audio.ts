import { authHeaders } from './auth'
import { NotSignedInError } from './analysis'

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export interface RouteAudioClip {
  distanceOffsetM: number
  text: string
  audioUrl: string | null
  hasAudio: boolean
}

export interface RouteAudioManifest {
  routeId: string
  distanceM: number
  speechAvailable: boolean
  clips: RouteAudioClip[]
}

export async function fetchRouteAudio(routeId: string): Promise<RouteAudioManifest> {
  const response = await fetch(`${apiBaseUrl}/api/v1/routes/${routeId}/audio`, {
    headers: { Accept: 'application/json', ...authHeaders() },
  })
  if (response.status === 401 || response.status === 403) throw new NotSignedInError()
  if (!response.ok) {
    throw new Error(`Loading route audio failed with status ${response.status}`)
  }
  return response.json() as Promise<RouteAudioManifest>
}

/**
 * Downloads every clip up front and holds it as a blob URL.
 *
 * <p>The whole point of synthesising at route-build time is that walking never
 * waits on the network. Preloading here completes that: by the time the first
 * turn arrives, the audio is already in memory.
 */
export async function preloadClips(
  clips: RouteAudioClip[],
): Promise<Map<number, string>> {
  const loaded = new Map<number, string>()
  await Promise.all(
    clips
      .filter((clip) => clip.audioUrl)
      .map(async (clip) => {
        try {
          const response = await fetch(`${apiBaseUrl}${clip.audioUrl}`, {
            headers: authHeaders(),
          })
          if (!response.ok) return
          loaded.set(clip.distanceOffsetM, URL.createObjectURL(await response.blob()))
        } catch {
          // A clip that will not load is skipped; the line still shows as text
          // and the browser voice can read it.
        }
      }),
  )
  return loaded
}

export function releaseClips(urls: Map<number, string>) {
  urls.forEach((url) => URL.revokeObjectURL(url))
}

/** Metres between two coordinates, good enough for trigger distances. */
export function distanceBetween(
  [latA, lonA]: [number, number],
  [latB, lonB]: [number, number],
): number {
  const earthRadiusM = 6_371_000
  const toRadians = Math.PI / 180
  const dLat = (latB - latA) * toRadians
  const dLon = (lonB - lonA) * toRadians
  const meanLat = ((latA + latB) / 2) * toRadians
  const x = dLon * Math.cos(meanLat)
  return Math.sqrt(dLat * dLat + x * x) * earthRadiusM
}

/**
 * Distance travelled along a route polyline to the point nearest a position.
 *
 * <p>Announcements are placed by distance along the route, so playback needs
 * the same measure rather than straight-line distance from the start.
 */
export function travelledAlongRoute(
  position: [number, number],
  coordinates: [longitude: number, latitude: number][],
): number {
  let travelled = 0
  let bestDistance = Number.POSITIVE_INFINITY
  let bestTravelled = 0

  for (let index = 0; index < coordinates.length; index++) {
    const [lon, lat] = coordinates[index]
    const gap = distanceBetween(position, [lat, lon])
    if (gap < bestDistance) {
      bestDistance = gap
      bestTravelled = travelled
    }
    if (index + 1 < coordinates.length) {
      const [nextLon, nextLat] = coordinates[index + 1]
      travelled += distanceBetween([lat, lon], [nextLat, nextLon])
    }
  }
  return bestTravelled
}
