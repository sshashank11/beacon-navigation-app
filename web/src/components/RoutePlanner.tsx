import { useMutation } from '@tanstack/react-query'
import {
  ArrowDownUp,
  Bike,
  Clock3,
  Footprints,
  Layers3,
  Leaf,
  LoaderCircle,
  MapPin,
  Navigation,
  Route,
  Trash2,
} from 'lucide-react'
import * as maplibregl from 'maplibre-gl'
import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { FeatureCollection, LineString, Point } from 'geojson'
import {
  createRouteComparison,
  hazardTileUrl,
  type HazardLayer,
  type LatLng,
  type RouteMode,
  type RouteComparison,
  type RouteResponse,
  type RouteVariant,
} from '../api/routes'

const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty'
const NEW_YORK_CENTER: [number, number] = [-73.9654, 40.7006]
const POINTS_SOURCE_ID = 'route-points'
const ROUTE_SOURCE_ID = 'route-line'
const HAZARD_SOURCE_ID = 'hazard-tiles'
const HAZARD_LAYER_ID = 'hazard-score-lines'

const hazardOptions: { value: HazardLayer; label: string }[] = [
  { value: 'pm25', label: 'PM2.5' },
  { value: 'no2', label: 'NO2' },
  { value: 'ozone', label: 'Ozone' },
  { value: 'traffic', label: 'Traffic' },
  { value: 'industrial', label: 'Industrial' },
  { value: 'shade', label: 'Shade' },
  { value: 'pollen', label: 'Tree pollen' },
]

type ActivePoint = 'origin' | 'destination'

interface RoutePoints {
  origin: LatLng | null
  destination: LatLng | null
}

const emptyFeatureCollection: FeatureCollection = {
  type: 'FeatureCollection',
  features: [],
}

export function RoutePlanner() {
  const mapContainerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const activePointRef = useRef<ActivePoint>('origin')
  const [mapReady, setMapReady] = useState(false)
  const [activePoint, setActivePoint] = useState<ActivePoint>('origin')
  const [points, setPoints] = useState<RoutePoints>({ origin: null, destination: null })
  const [mode, setMode] = useState<RouteMode>('foot')
  const [selectedVariant, setSelectedVariant] = useState<RouteVariant>('cleanest')
  const [hazardVisible, setHazardVisible] = useState(false)
  const [hazard, setHazard] = useState<HazardLayer>('pm25')

  const routeMutation = useMutation({
    mutationFn: createRouteComparison,
  })
  const resetRouteRef = useRef(routeMutation.reset)
  resetRouteRef.current = routeMutation.reset

  const selectActivePoint = useCallback((next: ActivePoint) => {
    activePointRef.current = next
    setActivePoint(next)
  }, [])

  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) return

    const map = new maplibregl.Map({
      container: mapContainerRef.current,
      style: MAP_STYLE_URL,
      center: NEW_YORK_CENTER,
      zoom: 11.4,
      attributionControl: { compact: true },
    })

    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    const handleMapClick = (event: globalThis.MouseEvent) => {
      if ((event.target as HTMLElement).closest('.maplibregl-control-container')) return

      const bounds = map.getContainer().getBoundingClientRect()
      const selectedLocation = map.unproject([
        event.clientX - bounds.left,
        event.clientY - bounds.top,
      ])
      const selected: LatLng = [selectedLocation.lat, selectedLocation.lng]
      const field = activePointRef.current

      setPoints((current) => ({ ...current, [field]: selected }))
      resetRouteRef.current()
      selectActivePoint(field === 'origin' ? 'destination' : 'origin')
    }
    map.getContainer().addEventListener('click', handleMapClick, { capture: true })
    map.on('load', () => {
      map.resize()
      map.addSource(POINTS_SOURCE_ID, { type: 'geojson', data: emptyFeatureCollection })
      map.addLayer({
        id: 'route-points-halo',
        type: 'circle',
        source: POINTS_SOURCE_ID,
        paint: {
          'circle-radius': 9,
          'circle-color': '#ffffff',
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 2,
        },
      })
      map.addLayer({
        id: 'route-points-fill',
        type: 'circle',
        source: POINTS_SOURCE_ID,
        paint: {
          'circle-radius': 6,
          'circle-color': ['match', ['get', 'kind'], 'origin', '#168447', '#d45d4c'],
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 1,
        },
      })
      setMapReady(true)
    })

    map.getCanvas().style.cursor = 'crosshair'
    const resizeObserver = new ResizeObserver(() => map.resize())
    resizeObserver.observe(mapContainerRef.current)
    mapRef.current = map

    return () => {
      resizeObserver.disconnect()
      map.getContainer().removeEventListener('click', handleMapClick, { capture: true })
      map.remove()
      mapRef.current = null
    }
  }, [selectActivePoint])

  useEffect(() => {
    if (!mapReady) return
    const source = mapRef.current?.getSource(POINTS_SOURCE_ID) as GeoJSONSource | undefined
    source?.setData(pointsFeatureCollection(points))
  }, [mapReady, points])

  useEffect(() => {
    const map = mapRef.current
    if (!mapReady || !map) return

    const source = map.getSource(ROUTE_SOURCE_ID) as GeoJSONSource | undefined
    const data = routeFeatureCollection(routeMutation.data, selectedVariant)

    if (source) {
      source.setData(data)
    } else if (routeMutation.data) {
      map.addSource(ROUTE_SOURCE_ID, { type: 'geojson', data })
      map.addLayer({
        id: 'route-line-casing',
        type: 'line',
        source: ROUTE_SOURCE_ID,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': '#ffffff',
          'line-width': ['case', ['get', 'selected'], 10, 7],
          'line-opacity': ['case', ['get', 'selected'], 0.92, 0.65],
        },
      }, 'route-points-halo')
      map.addLayer({
        id: 'route-line-fill',
        type: 'line',
        source: ROUTE_SOURCE_ID,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': ['match', ['get', 'variant'], 'cleanest', '#168447', '#116f8b'],
          'line-width': ['case', ['get', 'selected'], 6, 3],
          'line-opacity': ['case', ['get', 'selected'], 1, 0.62],
        },
      }, 'route-points-halo')
    }

    if (routeMutation.data) fitRoute(map, routeMutation.data[selectedVariant])
  }, [mapReady, routeMutation.data, selectedVariant])

  useEffect(() => {
    const map = mapRef.current
    if (!mapReady || !map) return

    if (map.getLayer(HAZARD_LAYER_ID)) map.removeLayer(HAZARD_LAYER_ID)
    if (map.getSource(HAZARD_SOURCE_ID)) map.removeSource(HAZARD_SOURCE_ID)
    if (!hazardVisible) return

    map.addSource(HAZARD_SOURCE_ID, {
      type: 'vector',
      tiles: [hazardTileUrl(hazard)],
      minzoom: 12,
      maxzoom: 16,
    })
    map.addLayer({
      id: HAZARD_LAYER_ID,
      type: 'line',
      source: HAZARD_SOURCE_ID,
      'source-layer': 'hazard',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': hazardColorRamp(hazard),
        'line-opacity': 0.52,
        'line-width': ['interpolate', ['linear'], ['zoom'], 12, 1, 16, 2.5],
      },
    }, map.getLayer('route-line-casing') ? 'route-line-casing' : 'route-points-halo')
  }, [hazard, hazardVisible, mapReady])

  function findRoute() {
    if (!points.origin || !points.destination) return
    routeMutation.mutate({ origin: points.origin, destination: points.destination, mode })
  }

  function selectMode(nextMode: RouteMode) {
    setMode(nextMode)
    routeMutation.reset()
  }

  function swapPoints() {
    setPoints(({ origin, destination }) => ({ origin: destination, destination: origin }))
    routeMutation.reset()
  }

  function clearRoute() {
    setPoints({ origin: null, destination: null })
    selectActivePoint('origin')
    setSelectedVariant('cleanest')
    routeMutation.reset()
    const source = mapRef.current?.getSource(ROUTE_SOURCE_ID) as GeoJSONSource | undefined
    source?.setData(emptyFeatureCollection)
  }

  const readyToRoute = points.origin && points.destination

  return (
    <main className="route-workspace flex min-h-0 flex-1 bg-[#edf1ef]">
      <aside className="route-panel z-10 flex shrink-0 flex-col border-r border-[#d4dcd7] bg-[#fbfcfb]">
        <div className="flex items-center justify-between border-b border-[#e1e6e3] px-5 py-4">
          <div>
            <p className="text-xs font-semibold uppercase text-[#6a776f]">Route planner</p>
            <h1 className="mt-1 text-xl font-bold text-[#073b3a]">Choose your trip</h1>
          </div>
          <button
            type="button"
            className="grid size-9 place-items-center text-[#536159] transition-colors hover:bg-[#edf2ef] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] disabled:opacity-40"
            onClick={clearRoute}
            disabled={!points.origin && !points.destination}
            title="Clear route"
            aria-label="Clear route"
          >
            <Trash2 className="size-4" aria-hidden />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-5">
          <div className="grid grid-cols-2 border border-[#cfd8d2] bg-[#f2f5f3] p-1" aria-label="Travel mode">
            <ModeButton mode="foot" currentMode={mode} icon={Footprints} label="Walk" onSelect={selectMode} />
            <ModeButton mode="bike" currentMode={mode} icon={Bike} label="Bike" onSelect={selectMode} />
          </div>

          <HazardLayerControl
            visible={hazardVisible}
            hazard={hazard}
            onVisibleChange={setHazardVisible}
            onHazardChange={setHazard}
          />

          <div className="relative mt-5 space-y-2">
            <PointButton
              kind="origin"
              active={activePoint === 'origin'}
              value={points.origin}
              onSelect={selectActivePoint}
            />
            <PointButton
              kind="destination"
              active={activePoint === 'destination'}
              value={points.destination}
              onSelect={selectActivePoint}
            />
            <button
              type="button"
              className="absolute right-3 top-1/2 grid size-8 -translate-y-1/2 place-items-center border border-[#d4dcd7] bg-white text-[#526159] shadow-sm transition-colors hover:bg-[#eef3f0] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] disabled:opacity-40"
              onClick={swapPoints}
              disabled={!points.origin && !points.destination}
              title="Swap origin and destination"
              aria-label="Swap origin and destination"
            >
              <ArrowDownUp className="size-4" aria-hidden />
            </button>
          </div>

          {routeMutation.data && (
            <RouteComparisonSummary
              comparison={routeMutation.data}
              selected={selectedVariant}
              onSelect={setSelectedVariant}
            />
          )}
          {routeMutation.isError && (
            <p className="mt-4 border-l-2 border-[#b84d3e] bg-[#fff3f0] px-3 py-2 text-sm text-[#8d382d]" role="alert">
              No route was found for those points.
            </p>
          )}
        </div>

        <div className="border-t border-[#e1e6e3] p-5">
          <button
            type="button"
            className="flex h-12 w-full items-center justify-center gap-2 bg-[#168447] px-4 text-sm font-semibold text-white transition-colors hover:bg-[#106c3b] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] disabled:cursor-not-allowed disabled:bg-[#a8b5ae]"
            onClick={findRoute}
            disabled={!readyToRoute || routeMutation.isPending}
          >
            {routeMutation.isPending
              ? <LoaderCircle className="size-4 animate-spin" aria-hidden />
              : <Route className="size-4" aria-hidden />}
            {routeMutation.isPending ? 'Finding route' : 'Find route'}
          </button>
        </div>
      </aside>

      <section className="relative min-h-[280px] min-w-0 flex-1" aria-label="Route map">
        <div ref={mapContainerRef} className="route-map" />
      </section>
    </main>
  )
}

interface ModeButtonProps {
  mode: RouteMode
  currentMode: RouteMode
  icon: typeof Footprints
  label: string
  onSelect: (mode: RouteMode) => void
}

function ModeButton({ mode, currentMode, icon: Icon, label, onSelect }: ModeButtonProps) {
  const selected = mode === currentMode
  return (
    <button
      type="button"
      className={`flex h-10 items-center justify-center gap-2 text-sm font-semibold transition-colors ${
        selected ? 'bg-white text-[#168447] shadow-sm' : 'text-[#637168] hover:text-[#26342c]'
      }`}
      onClick={() => onSelect(mode)}
      aria-pressed={selected}
    >
      <Icon className="size-4" aria-hidden />
      {label}
    </button>
  )
}

interface HazardLayerControlProps {
  visible: boolean
  hazard: HazardLayer
  onVisibleChange: (visible: boolean) => void
  onHazardChange: (hazard: HazardLayer) => void
}

function HazardLayerControl({
  visible,
  hazard,
  onVisibleChange,
  onHazardChange,
}: HazardLayerControlProps) {
  const shade = hazard === 'shade'
  return (
    <div className="mt-4 border-y border-[#dce3df] py-4">
      <div className="flex items-center gap-3">
        <span className="grid size-9 shrink-0 place-items-center bg-[#e8efe9] text-[#376149]">
          <Layers3 className="size-4" aria-hidden />
        </span>
        <label htmlFor="hazard-layer" className="min-w-0 flex-1 text-sm font-semibold text-[#243129]">
          Hazard layer
        </label>
        <label className="relative inline-flex h-6 w-11 shrink-0 cursor-pointer items-center" title="Toggle hazard layer">
          <input
            id="hazard-layer"
            type="checkbox"
            className="peer sr-only"
            checked={visible}
            onChange={(event) => onVisibleChange(event.target.checked)}
          />
          <span className="absolute inset-0 bg-[#b8c4bd] transition-colors peer-checked:bg-[#168447] peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-[#168447]" />
          <span className="absolute left-1 size-4 bg-white shadow-sm transition-transform peer-checked:translate-x-5" />
        </label>
      </div>
      <div className="mt-3 grid grid-cols-[minmax(0,1fr)_88px] items-center gap-3">
        <select
          className="h-10 min-w-0 border border-[#cfd8d2] bg-white px-3 text-sm font-medium text-[#2d3b33] outline-none focus:border-[#168447] disabled:bg-[#eef2ef] disabled:text-[#89948e]"
          value={hazard}
          onChange={(event) => onHazardChange(event.target.value as HazardLayer)}
          disabled={!visible}
          aria-label="Hazard shown on map"
        >
          {hazardOptions.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        <div className="min-w-0" aria-label={shade ? 'Low to high shade score' : 'Low to high hazard score'}>
          <div
            className="h-2 w-full"
            style={{
              background: shade
                ? 'linear-gradient(90deg, #c24135, #e7b443, #218354)'
                : 'linear-gradient(90deg, #218354, #e7b443, #c24135)',
            }}
          />
          <div className="mt-1 flex justify-between text-[10px] font-semibold uppercase text-[#748078]">
            <span>Low</span><span>High</span>
          </div>
        </div>
      </div>
    </div>
  )
}

interface PointButtonProps {
  kind: ActivePoint
  active: boolean
  value: LatLng | null
  onSelect: (kind: ActivePoint) => void
}

function PointButton({ kind, active, value, onSelect }: PointButtonProps) {
  const isOrigin = kind === 'origin'
  return (
    <button
      type="button"
      className={`flex h-16 w-full items-center gap-3 border px-3 pr-12 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] ${
        active ? 'border-[#168447] bg-[#f1f8f1]' : 'border-[#d5ddd8] bg-white hover:border-[#aebdb4]'
      }`}
      onClick={() => onSelect(kind)}
      aria-pressed={active}
    >
      <span className={`grid size-8 shrink-0 place-items-center text-white ${isOrigin ? 'bg-[#168447]' : 'bg-[#d45d4c]'}`}>
        <MapPin className="size-4" aria-hidden />
      </span>
      <span className="min-w-0">
        <span className="block text-xs font-semibold uppercase text-[#6a776f]">{isOrigin ? 'Origin' : 'Destination'}</span>
        <span className={`mt-0.5 block truncate text-sm ${value ? 'font-medium text-[#243129]' : 'text-[#7b8780]'}`}>
          {value ? formatCoordinates(value) : 'Not selected'}
        </span>
      </span>
    </button>
  )
}

interface RouteComparisonSummaryProps {
  comparison: RouteComparison
  selected: RouteVariant
  onSelect: (variant: RouteVariant) => void
}

function RouteComparisonSummary({ comparison, selected, onSelect }: RouteComparisonSummaryProps) {
  const distanceDelta = comparison.fastest.distance_m === 0
    ? 0
    : (comparison.cleanest.distance_m / comparison.fastest.distance_m - 1) * 100

  return (
    <div className="mt-5 border-t border-[#dce3df] pt-4">
      <div className="mb-2 flex items-center justify-between">
        <p className="text-xs font-semibold uppercase text-[#6a776f]">Route options</p>
        <p className="text-xs font-medium text-[#6d7a72]">{formatSignedPercent(distanceDelta)} distance</p>
      </div>
      <RouteOption
        variant="fastest"
        label="Fastest"
        icon={Navigation}
        route={comparison.fastest}
        selected={selected === 'fastest'}
        onSelect={onSelect}
      />
      <RouteOption
        variant="cleanest"
        label="Lower PM2.5"
        icon={Leaf}
        route={comparison.cleanest}
        selected={selected === 'cleanest'}
        onSelect={onSelect}
      />
    </div>
  )
}

interface RouteOptionProps {
  variant: RouteVariant
  label: string
  icon: typeof Navigation
  route: RouteResponse
  selected: boolean
  onSelect: (variant: RouteVariant) => void
}

function RouteOption({ variant, label, icon: Icon, route, selected, onSelect }: RouteOptionProps) {
  const color = variant === 'cleanest' ? '#168447' : '#116f8b'
  return (
    <button
      type="button"
      className={`mb-2 flex min-h-16 w-full items-center gap-3 border px-3 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] ${
        selected ? 'border-[#8aa99a] bg-[#f2f7f4]' : 'border-[#d7dfda] bg-white hover:border-[#aebdb4]'
      }`}
      onClick={() => onSelect(variant)}
      aria-pressed={selected}
    >
      <span className="grid size-9 shrink-0 place-items-center text-white" style={{ backgroundColor: color }}>
        <Icon className="size-4" aria-hidden />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-semibold text-[#243129]">{label}</span>
        <span className="mt-0.5 flex items-center gap-3 text-xs text-[#68766e]">
          <span>{formatDistance(route.distance_m)}</span>
          <span className="inline-flex items-center gap-1">
            <Clock3 className="size-3" aria-hidden />
            {formatDuration(route.duration_s)}
          </span>
        </span>
      </span>
    </button>
  )
}

function pointsFeatureCollection(points: RoutePoints): FeatureCollection<Point> {
  return {
    type: 'FeatureCollection',
    features: (['origin', 'destination'] as const).flatMap((kind) => {
      const point = points[kind]
      if (!point) return []
      return [{
        type: 'Feature' as const,
        properties: { kind },
        geometry: { type: 'Point' as const, coordinates: [point[1], point[0]] },
      }]
    }),
  }
}

function routeFeatureCollection(
  comparison?: RouteComparison,
  selected: RouteVariant = 'cleanest',
): FeatureCollection<LineString> {
  if (!comparison) return { type: 'FeatureCollection', features: [] }
  const variants: RouteVariant[] = selected === 'fastest'
    ? ['cleanest', 'fastest']
    : ['fastest', 'cleanest']
  return {
    type: 'FeatureCollection',
    features: variants.map((variant) => ({
      type: 'Feature',
      properties: { variant, selected: variant === selected },
      geometry: comparison[variant].geometry,
    })),
  }
}

function hazardColorRamp(hazard: HazardLayer): maplibregl.ExpressionSpecification {
  const low = hazard === 'shade' ? '#c24135' : '#218354'
  const high = hazard === 'shade' ? '#218354' : '#c24135'
  return ['interpolate', ['linear'], ['get', 'score'], 0, low, 50, '#e7b443', 100, high]
}

function fitRoute(map: MapLibreMap, route: RouteResponse) {
  const coordinates = route.geometry.coordinates
  if (coordinates.length === 0) return

  const bounds = coordinates.reduce(
    (current, coordinate) => current.extend(coordinate),
    new maplibregl.LngLatBounds(coordinates[0], coordinates[0]),
  )
  map.fitBounds(bounds, { padding: 72, maxZoom: 16, duration: 700 })
}

function formatCoordinates([latitude, longitude]: LatLng): string {
  return `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`
}

function formatDistance(metres: number): string {
  const miles = metres / 1609.344
  return miles < 0.1 ? `${Math.round(metres)} m` : `${miles.toFixed(1)} mi`
}

function formatDuration(seconds: number): string {
  const minutes = Math.max(1, Math.round(seconds / 60))
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  return remainder ? `${hours} hr ${remainder} min` : `${hours} hr`
}

function formatSignedPercent(value: number): string {
  if (Math.abs(value) < 0.05) return 'Same'
  return `${value > 0 ? '+' : ''}${value.toFixed(1)}%`
}
