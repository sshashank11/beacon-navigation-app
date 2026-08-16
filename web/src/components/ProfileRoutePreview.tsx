import { useQuery } from '@tanstack/react-query'
import { Clock3, LoaderCircle, RefreshCw, Route } from 'lucide-react'
import * as maplibregl from 'maplibre-gl'
import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { FeatureCollection, LineString, Point } from 'geojson'
import {
  fetchProfilePreview,
  type ProfilePreviewRequest,
  type ProfilePreviewResponse,
} from '../api/profilePreview'
import type { HazardWeights } from '../store/profileStore'

const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty'
const SAMPLE_ORIGIN: [number, number] = [40.7484, -73.9857]
const SAMPLE_DESTINATION: [number, number] = [40.7359, -73.9911]
const PREVIEW_SOURCE_ID = 'profile-preview-routes'
const PREVIEW_DEBOUNCE_MS = 300

interface ProfileRoutePreviewProps {
  weights: HazardWeights
  hardAvoids: string[]
  maxGradePct: number
  detourTolerance: number
  conservatism: number
}

export function ProfileRoutePreview({
  weights,
  hardAvoids,
  maxGradePct,
  detourTolerance,
  conservatism,
}: ProfileRoutePreviewProps) {
  const request = useMemo<ProfilePreviewRequest>(() => ({
    origin: SAMPLE_ORIGIN,
    destination: SAMPLE_DESTINATION,
    mode: 'foot',
    preset: 'none',
    weights,
    hard_avoids: hardAvoids,
    max_grade_pct: maxGradePct,
    detour_tolerance: detourTolerance,
    conservatism,
  }), [conservatism, detourTolerance, hardAvoids, maxGradePct, weights])
  const debouncedRequest = useDebouncedValue(request, PREVIEW_DEBOUNCE_MS)
  const preview = useQuery({
    queryKey: ['profile-preview', debouncedRequest],
    queryFn: ({ signal }) => fetchProfilePreview(debouncedRequest, signal),
    placeholderData: (previous) => previous,
  })

  return (
    <aside className="border border-[#cfd9d2] bg-white" aria-label="Live route preview">
      <div className="flex min-h-14 items-center justify-between border-b border-[#dce3df] px-4">
        <div>
          <p className="text-xs font-bold uppercase text-[#168447]">Live preview</p>
          <p className="mt-0.5 text-xs text-[#69766e]">Midtown to Union Square</p>
        </div>
        <PreviewStatus preview={preview} />
      </div>
      <PreviewMap data={preview.data} />
      <PreviewMetrics data={preview.data} />
    </aside>
  )
}

function PreviewMap({ data }: { data?: ProfilePreviewResponse }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const [mapReady, setMapReady] = useState(false)

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: MAP_STYLE_URL,
      center: [-73.9884, 40.7422],
      zoom: 13.4,
      attributionControl: { compact: true },
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    map.on('load', () => {
      map.resize()
      map.addSource(PREVIEW_SOURCE_ID, {
        type: 'geojson',
        data: previewFeatures(),
      })
      addPreviewLayers(map)
      setMapReady(true)
    })
    const observer = new ResizeObserver(() => map.resize())
    observer.observe(containerRef.current)
    mapRef.current = map

    return () => {
      observer.disconnect()
      map.remove()
      mapRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    if (!mapReady || !map || !data) return
    const source = map.getSource(PREVIEW_SOURCE_ID) as GeoJSONSource
    source.setData(previewFeatures(data))
    fitPreview(map, data)
  }, [data, mapReady])

  return (
    <div className="relative h-56 bg-[#e8eeea] sm:h-64 lg:h-72">
      <div ref={containerRef} className="h-full w-full" aria-label="Sample profile route map" />
      {!data && (
        <div className="pointer-events-none absolute inset-0 grid place-items-center bg-[#edf2ef]/80">
          <LoaderCircle className="size-5 animate-spin text-[#168447]" aria-hidden="true" />
        </div>
      )}
    </div>
  )
}

function PreviewStatus({ preview }: { preview: { isFetching: boolean; isError: boolean; refetch: () => unknown } }) {
  if (preview.isError) {
    return (
      <button type="button" className="grid size-8 place-items-center text-[#9a4b3f] hover:bg-[#fff0ed]" onClick={() => void preview.refetch()} title="Retry preview" aria-label="Retry preview">
        <RefreshCw className="size-4" aria-hidden="true" />
      </button>
    )
  }
  return (
    <span className={`inline-flex items-center gap-1.5 text-[11px] font-semibold ${preview.isFetching ? 'text-[#7b6b35]' : 'text-[#5f7066]'}`}>
      {preview.isFetching && <LoaderCircle className="size-3 animate-spin" aria-hidden="true" />}
      {preview.isFetching ? 'Updating' : 'Current'}
    </span>
  )
}

function PreviewMetrics({ data }: { data?: ProfilePreviewResponse }) {
  if (!data) {
    return <div className="h-[86px] animate-pulse border-t border-[#dce3df] bg-[#f5f7f5]" />
  }
  const distanceDiff = data.cleanest.comparative_diff.distance ?? 0
  const strongest = strongestImprovement(data.cleanest.comparative_diff)

  return (
    <div className="grid min-h-[86px] grid-cols-2 divide-x divide-[#dce3df] border-t border-[#dce3df]">
      <div className="flex items-center gap-3 px-4 py-3">
        <span className="grid size-8 shrink-0 place-items-center bg-[#e4f2e8] text-[#168447]">
          <Route className="size-4" aria-hidden="true" />
        </span>
        <span className="min-w-0">
          <span className="block text-[10px] font-bold uppercase text-[#728078]">Cleanest</span>
          <span className="mt-0.5 block text-sm font-bold text-[#27372e]">{formatDistance(data.cleanest.route.distance_m)}</span>
          <span className="mt-0.5 inline-flex items-center gap-1 text-[10px] text-[#6d7a72]">
            <Clock3 className="size-3" aria-hidden="true" />
            {formatDuration(data.cleanest.route.duration_s)}
          </span>
        </span>
      </div>
      <div className="px-4 py-3">
        <p className="text-[10px] font-bold uppercase text-[#728078]">Trade-off</p>
        <p className="mt-1 text-sm font-bold text-[#168447]">
          {strongest ? `${formatHazard(strongest.hazard)} ${formatPercent(strongest.value)}` : 'Baseline route'}
        </p>
        <p className="mt-1 text-[10px] text-[#6d7a72]">{formatPercent(distanceDiff)} distance</p>
      </div>
    </div>
  )
}

function addPreviewLayers(map: MapLibreMap) {
  map.addLayer({
    id: 'preview-fastest',
    type: 'line',
    source: PREVIEW_SOURCE_ID,
    filter: ['==', ['get', 'variant'], 'fastest'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': '#617c8a', 'line-width': 3, 'line-opacity': 0.6, 'line-dasharray': [2, 2] },
  })
  map.addLayer({
    id: 'preview-balanced',
    type: 'line',
    source: PREVIEW_SOURCE_ID,
    filter: ['==', ['get', 'variant'], 'balanced'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': '#16818a', 'line-width': 4, 'line-opacity': 0.75 },
  })
  map.addLayer({
    id: 'preview-cleanest-casing',
    type: 'line',
    source: PREVIEW_SOURCE_ID,
    filter: ['==', ['get', 'variant'], 'cleanest'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': '#ffffff', 'line-width': 8, 'line-opacity': 0.9 },
  })
  map.addLayer({
    id: 'preview-cleanest',
    type: 'line',
    source: PREVIEW_SOURCE_ID,
    filter: ['==', ['get', 'variant'], 'cleanest'],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': '#168447', 'line-width': 5 },
  })
  map.addLayer({
    id: 'preview-points',
    type: 'circle',
    source: PREVIEW_SOURCE_ID,
    filter: ['==', ['geometry-type'], 'Point'],
    paint: {
      'circle-radius': 5,
      'circle-color': ['match', ['get', 'kind'], 'origin', '#168447', '#d45d4c'],
      'circle-stroke-color': '#ffffff',
      'circle-stroke-width': 2,
    },
  })
}

function previewFeatures(data?: ProfilePreviewResponse): FeatureCollection<LineString | Point> {
  const points = [
    { type: 'Feature' as const, properties: { kind: 'origin' }, geometry: { type: 'Point' as const, coordinates: [SAMPLE_ORIGIN[1], SAMPLE_ORIGIN[0]] } },
    { type: 'Feature' as const, properties: { kind: 'destination' }, geometry: { type: 'Point' as const, coordinates: [SAMPLE_DESTINATION[1], SAMPLE_DESTINATION[0]] } },
  ]
  if (!data) return { type: 'FeatureCollection', features: points }
  const routes = (['fastest', 'balanced', 'cleanest'] as const).map((variant) => ({
    type: 'Feature' as const,
    properties: { variant },
    geometry: data[variant].route.geometry,
  }))
  return { type: 'FeatureCollection', features: [...routes, ...points] }
}

function fitPreview(map: MapLibreMap, data: ProfilePreviewResponse) {
  const coordinates = data.cleanest.route.geometry.coordinates
  if (coordinates.length === 0) return
  const bounds = coordinates.reduce(
    (current, coordinate) => current.extend(coordinate),
    new maplibregl.LngLatBounds(coordinates[0], coordinates[0]),
  )
  map.fitBounds(bounds, { padding: 36, maxZoom: 15.5, duration: 450 })
}

function strongestImprovement(diff: Record<string, number | null>) {
  return Object.entries(diff)
    .filter(([hazard, value]) => hazard !== 'distance' && value != null && value < 0)
    .map(([hazard, value]) => ({ hazard, value: value as number }))
    .sort((left, right) => left.value - right.value)[0]
}

function formatHazard(hazard: string): string {
  const labels: Record<string, string> = {
    pm25: 'PM2.5',
    no2: 'NO2',
    ozone: 'Ozone',
    traffic_prox: 'Traffic',
    industrial_prox: 'Industrial',
    shade_deficit: 'Low shade',
    pollen_tree: 'Tree pollen',
    grade: 'Grade',
  }
  return labels[hazard] ?? hazard
}

function formatPercent(value: number): string {
  const percent = value * 100
  if (Math.abs(percent) < 0.05) return 'Same'
  return `${percent > 0 ? '+' : ''}${percent.toFixed(0)}%`
}

function formatDistance(metres: number): string {
  return `${(metres / 1609.344).toFixed(1)} mi`
}

function formatDuration(seconds: number): string {
  return `${Math.max(1, Math.round(seconds / 60))} min`
}

function useDebouncedValue<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timeout = window.setTimeout(() => setDebounced(value), delay)
    return () => window.clearTimeout(timeout)
  }, [delay, value])
  return debounced
}
