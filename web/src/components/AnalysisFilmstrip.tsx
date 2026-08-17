import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CameraOff, Images, Loader2, LogIn } from 'lucide-react'
import {
  fetchAnalysis,
  NotSignedInError,
  requestRouteAnalysis,
  streamAnalysis,
  type AnalysisFrame,
  type AnalysisStatus,
} from '../api/analysis'

interface AnalysisFilmstripProps {
  routeId: string
  routeDistanceM: number
  onFrameFocus: (frame: AnalysisFrame | null) => void
}

type ViewState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'signed-out' }
  | { kind: 'error'; message: string }
  | { kind: 'no-imagery' }
  | { kind: 'frames'; status: AnalysisStatus }

export function AnalysisFilmstrip({
  routeId,
  routeDistanceM,
  onFrameFocus,
}: AnalysisFilmstripProps) {
  const [state, setState] = useState<ViewState>({ kind: 'idle' })
  const [frames, setFrames] = useState<AnalysisFrame[]>([])
  const [activeSeq, setActiveSeq] = useState<number | null>(null)
  const stopStreamRef = useRef<(() => void) | null>(null)

  // A new route invalidates whatever was on screen.
  useEffect(() => {
    setState({ kind: 'idle' })
    setFrames([])
    setActiveSeq(null)
    onFrameFocus(null)
    return () => {
      stopStreamRef.current?.()
      stopStreamRef.current = null
    }
  }, [routeId, onFrameFocus])

  const mergeFrame = useCallback((incoming: AnalysisFrame) => {
    setFrames((current) => {
      const next = current.filter((frame) => frame.seq !== incoming.seq)
      next.push(incoming)
      next.sort((left, right) => left.seq - right.seq)
      return next
    })
  }, [])

  const analyse = useCallback(async () => {
    setState({ kind: 'loading' })
    setFrames([])
    setActiveSeq(null)
    try {
      const accepted = await requestRouteAnalysis(routeId)
      if (accepted.status === 'no_imagery' || accepted.frameCount === 0) {
        setState({ kind: 'no-imagery' })
        return
      }

      // Whatever is already scored is on the snapshot; the stream fills the rest.
      const snapshot = await fetchAnalysis(accepted.analysisId)
      setFrames(snapshot.frames)
      setState({ kind: 'frames', status: accepted.status })

      stopStreamRef.current?.()
      stopStreamRef.current = streamAnalysis(accepted.analysisId, {
        onFrame: mergeFrame,
        onDone: (status) => setState({ kind: 'frames', status }),
        onError: (error) =>
          setState(
            error instanceof NotSignedInError
              ? { kind: 'signed-out' }
              : { kind: 'error', message: error.message },
          ),
      })
    } catch (error) {
      setState(
        error instanceof NotSignedInError
          ? { kind: 'signed-out' }
          : {
              kind: 'error',
              message: error instanceof Error ? error.message : 'Analysis failed',
            },
      )
    }
  }, [mergeFrame, routeId])

  const focusFrame = useCallback(
    (frame: AnalysisFrame) => {
      setActiveSeq(frame.seq)
      onFrameFocus(frame)
    },
    [onFrameFocus],
  )

  const scoredCount = useMemo(
    () => frames.filter((frame) => frame.scored).length,
    [frames],
  )

  // Sampling every 50 m means gaps in the strip are gaps in coverage.
  const coveredM = frames.length * 50
  const coveragePct =
    routeDistanceM > 0 ? Math.min(100, Math.round((coveredM / routeDistanceM) * 100)) : 0

  return (
    <section className="mt-5 border-t border-[#d4dcd7] pt-4">
      <div className="flex items-center justify-between gap-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-[#243029]">
          <Images className="size-4" aria-hidden />
          Street imagery
        </h3>
        {state.kind !== 'loading' && (
          <button
            type="button"
            onClick={() => void analyse()}
            className="border border-[#d4dcd7] bg-white px-3 py-1.5 text-xs font-medium text-[#25543c] transition-colors hover:bg-[#eef3f0] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
          >
            {state.kind === 'idle' ? 'Analyse route' : 'Re-analyse'}
          </button>
        )}
      </div>

      {state.kind === 'idle' && (
        <p className="mt-2 text-xs text-[#526159]">
          Look at what this route actually passes through, frame by frame.
        </p>
      )}

      {state.kind === 'loading' && (
        <p className="mt-3 flex items-center gap-2 text-xs text-[#526159]">
          <Loader2 className="size-4 animate-spin" aria-hidden />
          Sampling the route every 50 metres…
        </p>
      )}

      {state.kind === 'signed-out' && (
        <p className="mt-3 flex items-center gap-2 border-l-2 border-[#168447] bg-[#eef3f0] px-3 py-2 text-xs text-[#25543c]">
          <LogIn className="size-4 shrink-0" aria-hidden />
          Sign in to analyse the imagery along your saved routes.
        </p>
      )}

      {state.kind === 'error' && (
        <p
          className="mt-3 border-l-2 border-[#b84d3e] bg-[#fff3f0] px-3 py-2 text-xs text-[#8d382d]"
          role="alert"
        >
          {state.message}
        </p>
      )}

      {state.kind === 'no-imagery' && <NoImageryNotice />}

      {state.kind === 'frames' && frames.length === 0 && <NoImageryNotice />}

      {state.kind === 'frames' && frames.length > 0 && (
        <>
          <p className="mt-2 text-xs text-[#526159]">
            {scoredCount} of {frames.length} frames scored
            {state.status === 'pending' && ' — more arriving'}
            {coveragePct < 80 && (
              <>
                {' '}· imagery covers about {coveragePct}% of the route
              </>
            )}
          </p>
          <ul className="mt-3 flex gap-3 overflow-x-auto pb-2">
            {frames.map((frame) => (
              <li key={frame.seq} className="shrink-0">
                <FrameCard
                  frame={frame}
                  active={frame.seq === activeSeq}
                  onFocus={focusFrame}
                />
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

/**
 * Mapillary coverage is strong in Manhattan and along major corridors and thin
 * in outer-borough residential streets, so an empty result is an expected
 * answer rather than a failure.
 */
function NoImageryNotice() {
  return (
    <p className="mt-3 flex items-start gap-2 border-l-2 border-[#c8a86b] bg-[#fdf8ee] px-3 py-2 text-xs text-[#6b5527]">
      <CameraOff className="mt-0.5 size-4 shrink-0" aria-hidden />
      <span>
        No street imagery covers this stretch. Coverage is densest in Manhattan and
        along major corridors, and sparse on residential streets.
      </span>
    </p>
  )
}

interface FrameCardProps {
  frame: AnalysisFrame
  active: boolean
  onFocus: (frame: AnalysisFrame) => void
}

function FrameCard({ frame, active, onFocus }: FrameCardProps) {
  return (
    <button
      type="button"
      onClick={() => onFocus(frame)}
      aria-pressed={active}
      className={`block w-40 border text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] ${
        active ? 'border-[#168447] bg-[#eef3f0]' : 'border-[#d4dcd7] bg-white hover:bg-[#f6f9f7]'
      }`}
    >
      <img
        src={frame.thumbUrl}
        alt={`Street view ${Math.round(frame.distanceOffsetM)} metres along the route`}
        loading="lazy"
        className="h-24 w-full object-cover"
      />
      <div className="px-2 py-1.5">
        <p className="text-[11px] font-medium text-[#243029]">
          {formatOffset(frame.distanceOffsetM)}
        </p>
        {frame.scored ? (
          <div className="mt-1 flex flex-wrap gap-1">
            <Badge label="sky" value={formatFraction(frame.skyViewFactor)} />
            <Badge label="green" value={formatFraction(frame.vegetationFrac)} />
            <Badge label="cars" value={frame.vehicleCount ?? '—'} />
            <Badge label="people" value={frame.personCount ?? '—'} />
          </div>
        ) : (
          <p className="mt-1 text-[11px] text-[#7b8a82]">Scoring…</p>
        )}
      </div>
    </button>
  )
}

function Badge({ label, value }: { label: string; value: string | number }) {
  return (
    <span className="border border-[#d4dcd7] bg-[#f6f9f7] px-1.5 py-0.5 text-[10px] text-[#526159]">
      {label} {value}
    </span>
  )
}

function formatOffset(distanceM: number): string {
  return distanceM >= 1000
    ? `${(distanceM / 1000).toFixed(1)} km in`
    : `${Math.round(distanceM)} m in`
}

function formatFraction(value: number | null): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`
}
