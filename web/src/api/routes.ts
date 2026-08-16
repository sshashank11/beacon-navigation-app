export type RouteMode = 'foot' | 'bike'

export type LatLng = [latitude: number, longitude: number]

export interface RouteRequest {
  origin: LatLng
  destination: LatLng
  mode: RouteMode
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
