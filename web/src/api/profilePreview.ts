import type { RouteResponse } from './routes'
import type { HazardWeights } from '../store/profileStore'

export interface ProfilePreviewRequest {
  origin: [number, number]
  destination: [number, number]
  mode: 'foot' | 'bike'
  preset: 'none'
  weights: HazardWeights
  hard_avoids: string[]
  max_grade_pct: number
  detour_tolerance: number
  conservatism: number
}

export interface ComparedPreviewRoute {
  route: RouteResponse
  exposure_breakdown: Record<string, number>
  comparative_diff: Record<string, number | null>
  weight_scale: number
  attempts: number
  detour_cap_m: number
  detour_cap_exceeded: boolean
}

export interface ProfilePreviewResponse {
  fastest: ComparedPreviewRoute
  balanced: ComparedPreviewRoute
  cleanest: ComparedPreviewRoute
}

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export async function fetchProfilePreview(
  request: ProfilePreviewRequest,
  signal?: AbortSignal,
): Promise<ProfilePreviewResponse> {
  const response = await fetch(`${apiBaseUrl}/api/v1/profiles/preview`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
    signal,
  })

  if (!response.ok) {
    const detail = await response.text()
    throw new Error(detail || `Profile preview failed with status ${response.status}`)
  }

  return response.json() as Promise<ProfilePreviewResponse>
}
