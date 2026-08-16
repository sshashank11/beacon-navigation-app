export type RouteMode = 'foot' | 'bike'
export type RouteVariant = 'fastest' | 'cleanest'
export type HazardLayer = 'pm25' | 'no2' | 'ozone' | 'traffic' | 'industrial' | 'shade' | 'pollen'

export type LatLng = [latitude: number, longitude: number]

export interface RouteRequest {
  origin: LatLng
  destination: LatLng
  mode: RouteMode
  variant?: RouteVariant
}

export interface RouteInstruction {
  sign: number
  street_name: string
  distance_m: number
  duration_s: number
  points: LatLng[]
  extra: Record<string, unknown>
}

export interface RouteResponse {
  geometry: {
    type: 'LineString'
    coordinates: [longitude: number, latitude: number][]
  }
  distance_m: number
  duration_s: number
  instructions: RouteInstruction[]
}

export interface RouteComparison {
  fastest: RouteResponse
  cleanest: RouteResponse
}

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export async function createRoute(request: RouteRequest): Promise<RouteResponse> {
  const response = await fetch(`${apiBaseUrl}/api/v1/routes`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    const detail = await response.text()
    throw new Error(detail || `Route request failed with status ${response.status}`)
  }

  return response.json() as Promise<RouteResponse>
}

export async function createRouteComparison(
  request: Omit<RouteRequest, 'variant'>,
): Promise<RouteComparison> {
  const [fastest, cleanest] = await Promise.all([
    createRoute({ ...request, variant: 'fastest' }),
    createRoute({ ...request, variant: 'cleanest' }),
  ])
  return { fastest, cleanest }
}

export function hazardTileUrl(hazard: HazardLayer): string {
  return `${apiBaseUrl}/api/v1/tiles/hazard/${hazard}/{z}/{x}/{y}.mvt`
}
