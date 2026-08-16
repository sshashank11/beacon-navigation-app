import { useQuery } from '@tanstack/react-query'
import {
  CloudSun,
  Flower2,
  Gauge,
  RefreshCw,
  Thermometer,
  TriangleAlert,
  WifiOff,
} from 'lucide-react'
import type { ComponentType } from 'react'
import {
  fetchCurrentConditions,
  type AirQualityCondition,
  type PollenCondition,
} from '../api/conditions'

const REFRESH_INTERVAL_MS = 15 * 60 * 1000

interface MetricProps {
  icon: ComponentType<{ className?: string; 'aria-hidden'?: boolean }>
  label: string
  value: string
  detail: string
  tone?: string
}

interface PollenReading {
  type: string
  value: number
}

export function ConditionsBanner() {
  const conditions = useQuery({
    queryKey: ['conditions', 'now'],
    queryFn: ({ signal }) => fetchCurrentConditions(signal),
    refetchInterval: REFRESH_INTERVAL_MS,
    staleTime: 5 * 60 * 1000,
  })

  if (conditions.isPending) {
    return <LoadingBanner />
  }

  if (conditions.isError) {
    return (
      <header className="sticky top-0 z-30 border-b border-[#d9dfdc] bg-[#f8faf9]" aria-live="polite">
        <div className="mx-auto flex min-h-20 max-w-[1600px] items-center gap-4 px-4 sm:px-6">
          <Brand />
          <div className="h-9 w-px bg-[#d9dfdc]" aria-hidden="true" />
          <WifiOff className="size-5 shrink-0 text-[#9a4b3f]" aria-hidden="true" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-[#24322a]">Live conditions unavailable</p>
            <p className="truncate text-xs text-[#647168]">Route planning can continue with baseline data.</p>
          </div>
          <button
            type="button"
            className="grid size-10 shrink-0 place-items-center border border-[#cbd4cf] bg-white text-[#36483d] transition-colors hover:bg-[#edf2ef] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
            onClick={() => void conditions.refetch()}
            title="Retry live conditions"
            aria-label="Retry live conditions"
          >
            <RefreshCw className="size-4" aria-hidden="true" />
          </button>
        </div>
      </header>
    )
  }

  const snapshot = conditions.data
  const airQuality = snapshot.airQuality[0]
  const pollen = highestPollen(snapshot.pollen)
  const activeAlert = snapshot.alerts[0]
  const updatedAt = formatUpdatedAt(snapshot.generatedAt)

  return (
    <header className="sticky top-0 z-30 border-b border-[#d7ded9] bg-[#f8faf9] text-[#1f2a24]" aria-live="polite">
      <div className="mx-auto grid min-h-20 max-w-[1600px] grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-4 px-4 py-3 sm:px-6 2xl:flex 2xl:items-stretch 2xl:gap-x-5 2xl:py-0">
        <Brand />
        <div className="hidden h-9 w-px self-center bg-[#d7ded9] 2xl:block" aria-hidden="true" />

        <div className="col-start-2 row-start-1 min-w-0 self-center 2xl:col-auto 2xl:row-auto 2xl:min-w-[220px] 2xl:flex-1">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase text-[#607067]">
            <CloudSun className="size-4 text-[#168447]" aria-hidden="true" />
            Live conditions
          </div>
          <p className="mt-1 line-clamp-2 text-sm font-medium leading-5 text-[#24322a] lg:line-clamp-1">
            {snapshot.summary}
          </p>
        </div>

        <div className="conditions-scroll col-span-3 col-start-1 row-start-2 mt-3 flex min-w-0 snap-x items-stretch overflow-x-auto border-t border-[#e0e5e2] 2xl:col-auto 2xl:row-auto 2xl:mt-0 2xl:overflow-visible 2xl:border-t-0">
          <Metric
            icon={Gauge}
            label="Air quality"
            value={airQuality ? `${airQuality.aqi}` : '--'}
            detail={airQuality ? `${pollutantLabel(airQuality)} · ${airQuality.category}` : 'Awaiting AQI'}
            tone={airQuality ? aqiTone(airQuality.aqi) : undefined}
          />
          <Metric
            icon={Flower2}
            label="Pollen"
            value={pollen ? pollenCategory(pollen.value) : '--'}
            detail={pollen ? `${capitalize(pollen.type)} · UPI ${formatNumber(pollen.value)}` : 'Awaiting index'}
          />
          <Metric
            icon={Thermometer}
            label="Temperature"
            value={snapshot.weather.temperatureC == null ? '--' : `${Math.round(snapshot.weather.temperatureC)}°C`}
            detail={snapshot.weather.humidityPercent == null
              ? 'Humidity unavailable'
              : `${Math.round(snapshot.weather.humidityPercent)}% humidity`}
          />
          {activeAlert && (
            <Metric
              icon={TriangleAlert}
              label="NWS alert"
              value={activeAlert.severity ?? 'Active'}
              detail={activeAlert.event}
              tone="text-[#a43f32]"
            />
          )}
        </div>

        <div className="col-start-3 row-start-1 ml-auto flex shrink-0 items-center gap-1 self-center pl-1 2xl:col-auto 2xl:row-auto">
          <span className="hidden text-xs text-[#6c786f] 2xl:inline">Updated {updatedAt}</span>
          <button
            type="button"
            className="grid size-9 place-items-center text-[#526159] transition-colors hover:bg-[#e9efeb] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
            onClick={() => void conditions.refetch()}
            title="Refresh live conditions"
            aria-label="Refresh live conditions"
            disabled={conditions.isFetching}
          >
            <RefreshCw className={`size-4 ${conditions.isFetching ? 'animate-spin' : ''}`} aria-hidden="true" />
          </button>
        </div>
      </div>
    </header>
  )
}

function Brand() {
  return (
    <div className="flex shrink-0 items-center gap-2" aria-label="Beacon">
      <img
        src="/beacon-project-logo.png"
        alt=""
        className="size-10 rounded-full border border-[#b9d99a] bg-[#fbfae7] object-cover"
        aria-hidden="true"
      />
      <span className="text-lg font-extrabold text-[#073b3a]">Beacon</span>
    </div>
  )
}

function Metric({ icon: Icon, label, value, detail, tone = 'text-[#24322a]' }: MetricProps) {
  return (
    <div className="flex w-44 shrink-0 snap-start items-center gap-3 border-l border-[#d7ded9] px-4 py-3 first:border-l-0 2xl:first:border-l">
      <Icon className="size-4 shrink-0 text-[#68776e]" aria-hidden />
      <div className="min-w-0">
        <p className="text-[11px] font-semibold uppercase text-[#748078]">{label}</p>
        <p className={`truncate text-sm font-semibold ${tone}`}>{value}</p>
        <p className="truncate text-[11px] text-[#6b776f]">{detail}</p>
      </div>
    </div>
  )
}

function LoadingBanner() {
  return (
    <header className="sticky top-0 z-30 border-b border-[#d7ded9] bg-[#f8faf9]" aria-label="Loading live conditions">
      <div className="mx-auto flex min-h-20 max-w-[1600px] items-center gap-5 px-4 sm:px-6">
        <Brand />
        <div className="h-9 w-px bg-[#d7ded9]" aria-hidden="true" />
        <div className="min-w-0 flex-1 animate-pulse">
          <div className="h-3 w-28 bg-[#dfe6e2]" />
          <div className="mt-2 h-4 max-w-xl bg-[#e7ece9]" />
        </div>
      </div>
    </header>
  )
}

function highestPollen(pollen: PollenCondition): PollenReading | null {
  const readings = [
    { type: 'tree', value: pollen.treeUpi },
    { type: 'grass', value: pollen.grassUpi },
    { type: 'weed', value: pollen.weedUpi },
  ].filter((reading): reading is PollenReading => reading.value != null)

  return readings.sort((left, right) => right.value - left.value)[0] ?? null
}

function pollutantLabel(condition: AirQualityCondition): string {
  const labels: Record<string, string> = { pm25: 'PM2.5', ozone: 'Ozone', no2: 'NO₂' }
  return labels[condition.pollutant] ?? condition.pollutant.toUpperCase()
}

function aqiTone(aqi: number): string {
  if (aqi <= 50) return 'text-[#18724f]'
  if (aqi <= 100) return 'text-[#8a6500]'
  if (aqi <= 150) return 'text-[#a44f1f]'
  return 'text-[#a43f32]'
}

function pollenCategory(upi: number): string {
  const categories = ['None', 'Very low', 'Low', 'Moderate', 'High', 'Very high']
  return categories[Math.max(0, Math.min(5, Math.round(upi)))]
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

function formatUpdatedAt(value: string): string {
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(value))
}

function capitalize(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1)
}
