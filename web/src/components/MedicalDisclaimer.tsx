import { useEffect, useState } from 'react'
import { ShieldAlert } from 'lucide-react'

const ACKNOWLEDGED_KEY = 'beacon.disclaimer.acknowledged.v1'

/**
 * First-launch disclaimer.
 *
 * <p>Not dismissable by clicking away, pressing escape, or reloading: the only
 * way past it is the acknowledgement button. This app compares streets against
 * each other using surfaces built for neighbourhood-scale comparison, which is
 * genuinely useful and is not a measurement of what anyone will breathe. Saying
 * so plainly, once, before the first route, is the difference between a
 * thoughtful product and a naive one.
 */
export function MedicalDisclaimer() {
  const [acknowledged, setAcknowledged] = useState(true)

  useEffect(() => {
    setAcknowledged(window.localStorage.getItem(ACKNOWLEDGED_KEY) === 'true')
  }, [])

  if (acknowledged) return null

  function acknowledge() {
    window.localStorage.setItem(ACKNOWLEDGED_KEY, 'true')
    setAcknowledged(true)
  }

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-[#0b1f16]/70 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="disclaimer-title"
    >
      <div className="w-full max-w-lg border border-[#d4dcd7] bg-white p-6 shadow-xl">
        <h2
          id="disclaimer-title"
          className="flex items-center gap-2 text-lg font-bold text-[#073b3a]"
        >
          <ShieldAlert className="size-5 text-[#b8862f]" aria-hidden />
          Before you start
        </h2>
        <div className="mt-3 space-y-3 text-sm text-[#3d4a43]">
          <p>
            <strong>Beacon is not medical advice.</strong> It compares routes
            against each other. It does not measure what you will breathe, and it
            cannot tell you whether a trip is safe for you.
          </p>
          <p>
            The environmental data behind it describes neighbourhood averages
            rather than specific streets at specific moments. Treat every
            comparison as relative: this route is likely lower in something than
            that one, not that either carries a known exposure.
          </p>
          <p>
            Nothing here should change how you use medication or whether you seek
            care. For decisions about your health, talk to your physician.
          </p>
          <p>
            You tell Beacon which conditions bother you so it can weight streets
            accordingly. Those sensitivities are sent with each request and are
            never stored on the server.
          </p>
        </div>
        <button
          type="button"
          onClick={acknowledge}
          className="mt-5 w-full bg-[#168447] px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-[#12703c] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
        >
          I understand this is not medical advice
        </button>
      </div>
    </div>
  )
}

/** The same point, kept in view for as long as the app is being used. */
export function DisclaimerFooter() {
  return (
    <p className="border-t border-[#e1e6e3] bg-[#fbfcfb] px-5 py-2 text-[11px] text-[#6a776f]">
      Not medical advice. Beacon compares routes relative to one another and does
      not measure personal exposure. Consult your physician about your health.
    </p>
  )
}
