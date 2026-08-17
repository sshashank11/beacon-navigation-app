import { useCallback, useEffect, useRef, useState } from 'react'
import { Headphones, Loader2, Volume2 } from 'lucide-react'
import { NotSignedInError } from '../api/analysis'
import {
  fetchRouteAudio,
  preloadClips,
  releaseClips,
  travelledAlongRoute,
  type RouteAudioManifest,
} from '../api/audio'

interface RouteAudioGuideProps {
  routeId: string
  coordinates: [longitude: number, latitude: number][]
}

/** Only announce once per line, and only once within this window of it. */
const TRIGGER_WINDOW_M = 30

export function RouteAudioGuide({ routeId, coordinates }: RouteAudioGuideProps) {
  const [manifest, setManifest] = useState<RouteAudioManifest | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [following, setFollowing] = useState(false)
  const [spoken, setSpoken] = useState<number[]>([])
  const clipUrls = useRef<Map<number, string>>(new Map())
  const watchId = useRef<number | null>(null)

  useEffect(() => {
    setManifest(null)
    setSpoken([])
    setFollowing(false)
  }, [routeId])

  useEffect(() => {
    return () => {
      if (watchId.current !== null) navigator.geolocation.clearWatch(watchId.current)
      releaseClips(clipUrls.current)
      clipUrls.current = new Map()
    }
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const loaded = await fetchRouteAudio(routeId)
      setManifest(loaded)
      releaseClips(clipUrls.current)
      clipUrls.current = await preloadClips(loaded.clips)
    } catch (caught) {
      setError(
        caught instanceof NotSignedInError
          ? 'Sign in to hear the guidance for your saved routes.'
          : caught instanceof Error
            ? caught.message
            : 'Could not load route audio',
      )
    } finally {
      setLoading(false)
    }
  }, [routeId])

  const announce = useCallback((offset: number, text: string) => {
    const url = clipUrls.current.get(offset)
    if (url) {
      void new Audio(url).play().catch(() => speakWithBrowser(text))
    } else {
      speakWithBrowser(text)
    }
  }, [])

  const follow = useCallback(() => {
    if (!manifest || !('geolocation' in navigator)) {
      setError('This device cannot report its position.')
      return
    }
    setFollowing(true)
    watchId.current = navigator.geolocation.watchPosition(
      (position) => {
        const travelled = travelledAlongRoute(
          [position.coords.latitude, position.coords.longitude],
          coordinates,
        )
        setSpoken((already) => {
          const due = manifest.clips.find(
            (clip) =>
              !already.includes(clip.distanceOffsetM) &&
              travelled >= clip.distanceOffsetM - TRIGGER_WINDOW_M,
          )
          if (!due) return already
          announce(due.distanceOffsetM, due.text)
          return [...already, due.distanceOffsetM]
        })
      },
      () => setError('Location permission is needed to play guidance as you walk.'),
      { enableHighAccuracy: true, maximumAge: 5000 },
    )
  }, [announce, coordinates, manifest])

  const stop = useCallback(() => {
    if (watchId.current !== null) navigator.geolocation.clearWatch(watchId.current)
    watchId.current = null
    setFollowing(false)
  }, [])

  return (
    <section className="mt-5 border-t border-[#d4dcd7] pt-4">
      <div className="flex items-center justify-between gap-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-[#243029]">
          <Headphones className="size-4" aria-hidden />
          Spoken guidance
        </h3>
        {!manifest && !loading && (
          <button
            type="button"
            onClick={() => void load()}
            className="border border-[#d4dcd7] bg-white px-3 py-1.5 text-xs font-medium text-[#25543c] transition-colors hover:bg-[#eef3f0] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
          >
            Prepare audio
          </button>
        )}
        {manifest && (
          <button
            type="button"
            onClick={following ? stop : follow}
            className="border border-[#d4dcd7] bg-white px-3 py-1.5 text-xs font-medium text-[#25543c] transition-colors hover:bg-[#eef3f0] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
          >
            {following ? 'Stop following' : 'Play as I walk'}
          </button>
        )}
      </div>

      {loading && (
        <p className="mt-3 flex items-center gap-2 text-xs text-[#526159]">
          <Loader2 className="size-4 animate-spin" aria-hidden />
          Preparing every line before you set off…
        </p>
      )}

      {error && (
        <p className="mt-3 border-l-2 border-[#b84d3e] bg-[#fff3f0] px-3 py-2 text-xs text-[#8d382d]" role="alert">
          {error}
        </p>
      )}

      {manifest && !manifest.speechAvailable && (
        <p className="mt-3 border-l-2 border-[#c8a86b] bg-[#fdf8ee] px-3 py-2 text-xs text-[#6b5527]">
          No speech service is configured, so these lines will be read by your
          browser's own voice instead.
        </p>
      )}

      {manifest && (
        <ol className="mt-3 space-y-1.5">
          {manifest.clips.map((clip) => (
            <li
              key={clip.distanceOffsetM}
              className={`flex items-start gap-2 border-l-2 px-2 py-1 text-xs ${
                spoken.includes(clip.distanceOffsetM)
                  ? 'border-[#168447] bg-[#eef3f0] text-[#25543c]'
                  : 'border-[#d4dcd7] text-[#526159]'
              }`}
            >
              <button
                type="button"
                onClick={() => announce(clip.distanceOffsetM, clip.text)}
                title="Play this line"
                aria-label={`Play: ${clip.text}`}
                className="mt-0.5 text-[#168447] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
              >
                <Volume2 className="size-3.5" aria-hidden />
              </button>
              <span>
                <span className="font-medium">{formatOffset(clip.distanceOffsetM)}</span>
                {' — '}
                {clip.text}
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

/**
 * Falls back to the device's own voice.
 *
 * <p>Robotic but working beats silent, and it means the feature is usable
 * before any speech credit exists.
 */
function speakWithBrowser(text: string) {
  if (!('speechSynthesis' in window)) return
  const utterance = new SpeechSynthesisUtterance(text)
  utterance.rate = 1.0
  window.speechSynthesis.speak(utterance)
}

function formatOffset(distanceM: number): string {
  return distanceM >= 1000
    ? `${(distanceM / 1000).toFixed(1)} km`
    : `${Math.round(distanceM)} m`
}
