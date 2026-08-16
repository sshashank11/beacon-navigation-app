import { useMutation } from '@tanstack/react-query'
import {
  ArrowDownUp,
  Bike,
  Clock3,
  Footprints,
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
  createRoute,
  type LatLng,
  type RouteMode,
  type RouteResponse,
} from '../api/routes'

const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty'
const NEW_YORK_CENTER: [number, number] = [-73.9654, 40.7006]
const POINTS_SOURCE_ID = 'route-points'
const ROUTE_SOURCE_ID = 'route-line'

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

  const routeMutation = useMutation({
    mutationFn: createRoute,
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
    const data = routeFeatureCollection(routeMutation.data)

    if (source) {
      source.setData(data)
    } else if (routeMutation.data) {
      map.addSource(ROUTE_SOURCE_ID, { type: 'geojson', data })
      map.addLayer({
        id: 'route-line-casing',
        type: 'line',
        source: ROUTE_SOURCE_ID,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#ffffff', 'line-width': 9, 'line-opacity': 0.9 },
      }, 'route-points-halo')
      map.addLayer({
        id: 'route-line-fill',
        type: 'line',
        source: ROUTE_SOURCE_ID,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#116f8b', 'line-width': 5 },
      }, 'route-points-halo')
    }

    if (routeMutation.data) fitRoute(map, routeMutation.data)
  }, [mapReady, routeMutation.data])

  function findRoute() {
    if (!points.origin || !points.destination) return
    routeMutation.mutate({ origin: points.origin, destination: points.destination, mode })
  }

  function swapPoints() {
    setPoints(({ origin, destination }) => ({ origin: destination, destination: origin }))
    routeMutation.reset()
  }

  function clearRoute() {
    setPoints({ origin: null, destination: null })
    selectActivePoint('origin')
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
            <ModeButton mode="foot" currentMode={mode} icon={Footprints} label="Walk" onSelect={setMode} />
            <ModeButton mode="bike" currentMode={mode} icon={Bike} label="Bike" onSelect={setMode} />
          </div>

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

          {routeMutation.data && <RouteSummary route={routeMutation.data} />}
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
        <div ref={mapContainerRef} className="absolute inset-0" />
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

function RouteSummary({ route }: { route: RouteResponse }) {
  return (
    <div className="mt-5 border-y border-[#dce3df] py-4">
      <div className="grid grid-cols-2 gap-4">
        <div className="flex items-center gap-3">
          <span className="grid size-9 place-items-center bg-[#e3f2df] text-[#168447]">
            <Navigation className="size-4" aria-hidden />
          </span>
          <div>
            <p className="text-xs text-[#718078]">Distance</p>
            <p className="text-base font-semibold text-[#073b3a]">{formatDistance(route.distance_m)}</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className="grid size-9 place-items-center bg-[#e6eef3] text-[#116f8b]">
            <Clock3 className="size-4" aria-hidden />
          </span>
          <div>
            <p className="text-xs text-[#718078]">Duration</p>
            <p className="text-base font-semibold text-[#073b3a]">{formatDuration(route.duration_s)}</p>
          </div>
        </div>
      </div>
    </div>
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

function routeFeatureCollection(route?: RouteResponse): FeatureCollection<LineString> {
  if (!route) return { type: 'FeatureCollection', features: [] }
  return {
    type: 'FeatureCollection',
    features: [{ type: 'Feature', properties: {}, geometry: route.geometry }],
  }
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
