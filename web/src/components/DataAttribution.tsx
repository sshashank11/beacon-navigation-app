import { useState } from 'react'
import { Info } from 'lucide-react'

/**
 * Visible credit for every source the app routes on.
 *
 * <p>Two reasons this is in the interface rather than only the README. Several
 * of these licences require attribution where the data is used, and knowing
 * which sources a claim rests on is part of being able to judge the claim.
 */
const SOURCES = [
  { name: 'OpenStreetMap contributors', use: 'street network and road classes', licence: 'ODbL' },
  { name: 'Mapillary contributors', use: 'street-level imagery', licence: 'CC BY-SA' },
  { name: 'NYC DOHMH — NYCCAS', use: 'air pollution surfaces', licence: 'NYC Open Data' },
  { name: 'NYC Parks', use: '2015 Street Tree Census', licence: 'NYC Open Data' },
  { name: 'NYC DOB', use: 'construction permits', licence: 'NYC Open Data' },
  { name: 'US EPA', use: 'TRI and ECHO industrial facilities', licence: 'Public domain' },
  { name: 'NOAA / National Weather Service', use: 'forecasts and alerts', licence: 'Public domain' },
  { name: 'OpenAQ', use: 'air quality observations', licence: 'CC BY 4.0' },
  { name: 'AirNow (EPA/NOAA)', use: 'official AQI category', licence: 'Public domain' },
  { name: 'Google Pollen API', use: 'daily pollen indices', licence: 'Google Maps Platform ToS' },
  { name: 'USGS 3DEP', use: 'elevation and grade', licence: 'Public domain' },
  { name: 'OpenFreeMap / OpenMapTiles', use: 'basemap tiles', licence: 'ODbL' },
  { name: 'NVIDIA SegFormer (Cityscapes)', use: 'image segmentation model', licence: 'NVIDIA source-code licence' },
]

export function DataAttribution() {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-1 text-[11px] text-[#6a776f] underline decoration-dotted transition-colors hover:text-[#25543c] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
      >
        <Info className="size-3" aria-hidden />
        Data sources
      </button>
      {open && (
        <div
          className="fixed inset-0 z-50 grid place-items-center bg-[#0b1f16]/70 p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="attribution-title"
          onClick={() => setOpen(false)}
        >
          <div
            className="max-h-[80vh] w-full max-w-xl overflow-y-auto border border-[#d4dcd7] bg-white p-6"
            onClick={(event) => event.stopPropagation()}
          >
            <h2 id="attribution-title" className="text-lg font-bold text-[#073b3a]">
              Where this data comes from
            </h2>
            <ul className="mt-4 space-y-2">
              {SOURCES.map((source) => (
                <li key={source.name} className="border-b border-[#eef2ef] pb-2 text-sm">
                  <p className="font-medium text-[#243029]">{source.name}</p>
                  <p className="text-xs text-[#526159]">
                    {source.use} · {source.licence}
                  </p>
                </li>
              ))}
            </ul>
            <p className="mt-4 text-xs text-[#6a776f]">
              Imagery scores cover a demo corridor rather than the whole city, and
              percentile ranks compare photographed segments to each other.
            </p>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="mt-4 border border-[#d4dcd7] px-3 py-1.5 text-xs text-[#526159] transition-colors hover:bg-[#eef3f0]"
            >
              Close
            </button>
          </div>
        </div>
      )}
    </>
  )
}
