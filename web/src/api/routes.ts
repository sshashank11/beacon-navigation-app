import { authHeaders } from './auth'

export type RouteMode = 'foot' | 'bike'
export type RouteVariant = 'fastest' | 'balanced' | 'cleanest'
export type LegacyRouteVariant = Exclude<RouteVariant, 'balanced'>
export type HazardLayer = 'pm25' | 'no2' | 'ozone' | 'traffic' | 'industrial' | 'shade' | 'pollen'

export type LatLng = [latitude: number, longitude: number]

export interface RouteRequest {
  origin: LatLng
  destination: LatLng
  mode: RouteMode
  variant?: LegacyRouteVariant
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

export interface ComparedRoute {
  id: string
  route: RouteResponse
  exposure_breakdown: Record<string, number>
  comparative_diff: Record<string, number | null>
  weight_scale: number
  attempts: number
  detour_cap_m: number
  detour_cap_exceeded: boolean
}

export interface RouteComparison {
  fastest: ComparedRoute
  balanced: ComparedRoute
  cleanest: ComparedRoute
}

export interface RouteComparisonRequest {
  origin: LatLng
  destination: LatLng
  mode: RouteMode
  preset: 'none'
  weights: Record<string, number>
  hard_avoids: string[]
  max_grade_pct: number
  detour_tolerance: number
  conservatism: number
}

export interface RouteFeedbackRequest {
  feltWorse: boolean
  whichSegments: number[]
}

export interface RouteFeedbackResponse extends RouteFeedbackRequest {
  id: string
  routeId: string
  createdAt: string
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
  request: RouteComparisonRequest,
): Promise<RouteComparison> {
  const response = await fetch(`${apiBaseUrl}/api/v1/routes/compare`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    const detail = await response.text()
    throw new Error(detail || `Route comparison failed with status ${response.status}`)
  }

  return response.json() as Promise<RouteComparison>
}

/**
 * Feedback belongs to the route's owner, so this needs credentials. Without
 * them the API answers 404 rather than confirming the route exists.
 */
export async function submitRouteFeedback(
  routeId: string,
  request: RouteFeedbackRequest,
): Promise<RouteFeedbackResponse> {
  const response = await fetch(`${apiBaseUrl}/api/v1/routes/${routeId}/feedback`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    const detail = await response.text()
    throw new Error(detail || `Route feedback failed with status ${response.status}`)
  }

  return response.json() as Promise<RouteFeedbackResponse>
}

export function hazardTileUrl(hazard: HazardLayer): string {
  return `${apiBaseUrl}/api/v1/tiles/hazard/${hazard}/{z}/{x}/{y}.mvt`
}
