export interface HazardCondition {
  hazard: string
  meanValue: number
  unit: string
  stationCount: number
  latestObservedAt: string
  source: string
}

export interface AirQualityCondition {
  pollutant: string
  aqi: number
  category: string
  observedAt: string
}

export interface PollenCondition {
  treeUpi: number | null
  grassUpi: number | null
  weedUpi: number | null
}

export interface WeatherCondition {
  temperatureC: number | null
  humidityPercent: number | null
  windSpeedMph: number | null
  windBearingDegrees: number | null
}

export interface AlertCondition {
  id: string
  event: string
  headline: string | null
  severity: string | null
  urgency: string | null
  onset: string | null
  expiresAt: string | null
}

export interface ConditionSnapshot {
  generatedAt: string
  hazards: HazardCondition[]
  airQuality: AirQualityCondition[]
  pollen: PollenCondition
  weather: WeatherCondition
  alerts: AlertCondition[]
  summary: string
}

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export async function fetchCurrentConditions(signal?: AbortSignal): Promise<ConditionSnapshot> {
  const response = await fetch(`${apiBaseUrl}/api/v1/conditions/now`, {
    headers: { Accept: 'application/json' },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Conditions request failed with status ${response.status}`)
  }

  return response.json() as Promise<ConditionSnapshot>
}
