import { authHeaders } from './auth'

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export type AnalysisStatus = 'pending' | 'ready' | 'no_imagery'

export interface AnalysisFrame {
  seq: number
  distanceOffsetM: number
  mapillaryId: string
  thumbUrl: string
  longitude: number
  latitude: number
  routeBearingDeg: number
  imageDistanceM: number
  scored: boolean
  skyViewFactor: number | null
  vegetationFrac: number | null
  sidewalkFrac: number | null
  vehicleCount: number | null
  personCount: number | null
}

export interface AnalysisAccepted {
  analysisId: string
  routeId: string
  status: AnalysisStatus
  frameCount: number
  pendingCount: number
}

export interface AnalysisSnapshot {
  analysisId: string
  routeId: string
  status: AnalysisStatus
  frameCount: number
  frames: AnalysisFrame[]
}

export class NotSignedInError extends Error {
  constructor() {
    super('Sign in to analyse the imagery along a route.')
    this.name = 'NotSignedInError'
  }
}

async function readJson<T>(response: Response, action: string): Promise<T> {
  if (response.status === 401 || response.status === 403) {
    throw new NotSignedInError()
  }
  if (!response.ok) {
    throw new Error(`${action} failed with status ${response.status}`)
  }
  return response.json() as Promise<T>
}

export async function requestRouteAnalysis(routeId: string): Promise<AnalysisAccepted> {
  const response = await fetch(`${apiBaseUrl}/api/v1/routes/${routeId}/analysis`, {
    method: 'POST',
    headers: { Accept: 'application/json', ...authHeaders() },
  })
  return readJson<AnalysisAccepted>(response, 'Requesting imagery analysis')
}

export async function fetchAnalysis(analysisId: string): Promise<AnalysisSnapshot> {
  const response = await fetch(`${apiBaseUrl}/api/v1/analysis/${analysisId}`, {
    headers: { Accept: 'application/json', ...authHeaders() },
  })
  return readJson<AnalysisSnapshot>(response, 'Loading imagery analysis')
}

/**
 * Streams frames as the worker scores them.
 *
 * <p>EventSource cannot carry an Authorization header, so this reads the SSE
 * body from fetch directly and parses the events. That also means one code
 * path handles a 401 the same way the other calls do.
 */
export function streamAnalysis(
  analysisId: string,
  handlers: {
    onFrame: (frame: AnalysisFrame) => void
    onDone: (status: AnalysisStatus) => void
    onError: (error: Error) => void
  },
): () => void {
  const controller = new AbortController()

  void (async () => {
    try {
      const response = await fetch(`${apiBaseUrl}/api/v1/analysis/${analysisId}/stream`, {
        headers: { Accept: 'text/event-stream', ...authHeaders() },
        signal: controller.signal,
      })
      if (response.status === 401 || response.status === 403) throw new NotSignedInError()
      if (!response.ok || !response.body) {
        throw new Error(`Imagery stream failed with status ${response.status}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // SSE separates events with a blank line.
        let split = buffer.indexOf('\n\n')
        while (split !== -1) {
          handleEvent(buffer.slice(0, split), handlers)
          buffer = buffer.slice(split + 2)
          split = buffer.indexOf('\n\n')
        }
      }
      handlers.onDone('ready')
    } catch (error) {
      if (controller.signal.aborted) return
      handlers.onError(error instanceof Error ? error : new Error('Imagery stream failed'))
    }
  })()

  return () => controller.abort()
}

function handleEvent(
  raw: string,
  handlers: {
    onFrame: (frame: AnalysisFrame) => void
    onDone: (status: AnalysisStatus) => void
  },
) {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).trim())
  }
  if (data.length === 0) return

  const payload = JSON.parse(data.join('\n')) as unknown
  if (event === 'frame') {
    handlers.onFrame(payload as AnalysisFrame)
  } else if (event === 'complete' || event === 'timeout' || event === 'no-imagery') {
    handlers.onDone((payload as { status: AnalysisStatus }).status)
  }
}
