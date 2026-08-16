import {
  Activity,
  Baby,
  Ban,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleUserRound,
  FlaskConical,
  Flower2,
  HeartPulse,
  PersonStanding,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  X,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import {
  type ConditionId,
  type HazardId,
  type TravelerType,
  useProfileStore,
} from '../store/profileStore'

const steps = [
  { label: 'Sensitivities', icon: ShieldCheck },
  { label: 'Priorities', icon: SlidersHorizontal },
  { label: 'Tolerance', icon: PersonStanding },
  { label: 'Traveler', icon: CircleUserRound },
]

const conditions: {
  id: ConditionId
  label: string
  detail: string
  icon: typeof Activity
}[] = [
  { id: 'asthma', label: 'Asthma', detail: 'Air pollution and cold air', icon: Activity },
  { id: 'allergies', label: 'Allergies', detail: 'Tree, grass, and weed pollen', icon: Flower2 },
  { id: 'copd', label: 'COPD', detail: 'Air quality and steep grades', icon: Activity },
  { id: 'chemical_sensitivity', label: 'Chemical sensitivity', detail: 'Industry, traffic, and construction', icon: FlaskConical },
  { id: 'cardiac', label: 'Heart condition', detail: 'Air quality, heat, and hills', icon: HeartPulse },
  { id: 'pregnancy', label: 'Pregnancy', detail: 'Set individual priorities next', icon: Baby },
  { id: 'none', label: 'None of these', detail: 'Start with neutral settings', icon: Ban },
]

const hazardGroups: { label: string; hazards: { id: HazardId; label: string }[] }[] = [
  {
    label: 'Air',
    hazards: [
      { id: 'pm25', label: 'Fine particles' },
      { id: 'ozone', label: 'Ozone' },
      { id: 'no2', label: 'Nitrogen dioxide' },
      { id: 'traffic_prox', label: 'Traffic exhaust' },
      { id: 'construction', label: 'Construction dust' },
      { id: 'industrial_prox', label: 'Industrial emissions' },
    ],
  },
  {
    label: 'Plants & weather',
    hazards: [
      { id: 'pollen_tree', label: 'Tree pollen' },
      { id: 'pollen_grass', label: 'Grass pollen' },
      { id: 'pollen_weed', label: 'Weed pollen' },
      { id: 'heat', label: 'Heat' },
      { id: 'cold_air', label: 'Cold air' },
      { id: 'humidity', label: 'Humidity' },
      { id: 'shade_deficit', label: 'Low shade' },
    ],
  },
  {
    label: 'Terrain & surroundings',
    hazards: [
      { id: 'grade', label: 'Steep grades' },
      { id: 'crowd_density', label: 'Crowds' },
    ],
  },
]

const travelers: {
  id: TravelerType
  label: string
  detail: string
  icon: typeof CircleUserRound
}[] = [
  { id: 'just_me', label: 'Just me', detail: 'Use my selected priorities', icon: CircleUserRound },
  { id: 'child', label: 'With a child', detail: 'Apply more cautious thresholds', icon: Baby },
  { id: 'older_adult', label: 'With an older adult', detail: 'Favor gentler exposure thresholds', icon: PersonStanding },
]

const weightLabels = ['Ignore', 'Low', 'Moderate', 'High']

interface OnboardingFlowProps {
  canClose: boolean
  onClose: () => void
}

export function OnboardingFlow({ canClose, onClose }: OnboardingFlowProps) {
  const [step, setStep] = useState(0)
  const profile = useProfileStore()

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [])

  const selectedConditionLabels = conditions
    .filter((condition) => profile.conditions.includes(condition.id))
    .map((condition) => condition.label)
  const topPriorities = useMemo(() => hazardGroups
    .flatMap((group) => group.hazards)
    .filter((hazard) => profile.weights[hazard.id] > 0)
    .sort((left, right) => profile.weights[right.id] - profile.weights[left.id])
    .slice(0, 4), [profile.weights])

  function toggleCondition(condition: ConditionId) {
    if (condition === 'none') {
      profile.setConditions(['none'])
      return
    }
    const withoutNone = profile.conditions.filter((item) => item !== 'none')
    profile.setConditions(withoutNone.includes(condition)
      ? withoutNone.filter((item) => item !== condition)
      : [...withoutNone, condition])
  }

  function finish() {
    profile.completeOnboarding()
    onClose()
  }

  const nextDisabled = step === 0 && profile.conditions.length === 0

  return (
    <div className="fixed inset-0 z-50 flex min-h-[560px] flex-col overflow-hidden bg-[#f4f7f4] text-[#173027]" role="dialog" aria-modal="true" aria-label="Trigger profile setup">
      <header className="flex h-16 shrink-0 items-center justify-between border-b border-[#d9e0db] bg-[#fbfcfb] px-4 sm:px-7">
        <div className="flex items-center gap-3">
          <img src="/beacon-project-logo.png" alt="" className="size-9 rounded-full border border-[#b9d99a] object-cover" aria-hidden="true" />
          <div>
            <p className="text-sm font-extrabold text-[#073b3a]">Beacon</p>
            <p className="text-[11px] font-semibold uppercase text-[#718077]">Trigger profile</p>
          </div>
        </div>
        <div className="text-xs font-semibold text-[#68766e]">Step {step + 1} of {steps.length}</div>
        {canClose ? (
          <button type="button" className="grid size-9 place-items-center text-[#536159] hover:bg-[#e9efeb] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]" onClick={onClose} title="Close profile setup" aria-label="Close profile setup">
            <X className="size-4" aria-hidden="true" />
          </button>
        ) : <div className="size-9" aria-hidden="true" />}
      </header>

      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <nav className="shrink-0 border-b border-[#d9e0db] bg-[#edf3ee] px-4 py-3 md:w-60 md:border-b-0 md:border-r md:px-5 md:py-8" aria-label="Profile setup steps">
          <ol className="grid grid-cols-4 gap-1 md:block md:space-y-2">
            {steps.map((item, index) => {
              const Icon = item.icon
              const active = index === step
              const complete = index < step
              return (
                <li key={item.label}>
                  <button
                    type="button"
                    className={`flex h-12 w-full items-center justify-center gap-3 px-2 text-left text-xs font-semibold transition-colors md:justify-start md:px-3 md:text-sm ${active ? 'bg-white text-[#116b43] shadow-sm' : 'text-[#66746c] hover:text-[#24342b]'}`}
                    onClick={() => setStep(index)}
                    aria-current={active ? 'step' : undefined}
                    aria-label={item.label}
                  >
                    <span className={`grid size-7 shrink-0 place-items-center ${complete ? 'bg-[#168447] text-white' : active ? 'bg-[#dff0e4] text-[#168447]' : 'bg-[#dde5df] text-[#66746c]'}`}>
                      {complete ? <Check className="size-4" aria-hidden="true" /> : <Icon className="size-4" aria-hidden="true" />}
                    </span>
                    <span className="hidden md:block">{item.label}</span>
                  </button>
                </li>
              )
            })}
          </ol>
          <div className="mt-8 hidden border-t border-[#d4ddd7] pt-5 text-xs leading-5 text-[#69776f] md:block">
            <ShieldCheck className="mb-2 size-4 text-[#168447]" aria-hidden="true" />
            Sensitivities stay in your profile and are used only to calculate route trade-offs.
          </div>
        </nav>

        <main className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto w-full max-w-4xl px-5 py-8 sm:px-8 md:py-10">
            {step === 0 && (
              <section aria-labelledby="conditions-title">
                <StepHeading eyebrow="Sensitivities" title="What should Beacon account for?" detail="Choose any that apply. You can tune every priority on the next screen." id="conditions-title" />
                <div className="mt-7 grid gap-3 sm:grid-cols-2">
                  {conditions.map((condition) => {
                    const Icon = condition.icon
                    const selected = profile.conditions.includes(condition.id)
                    return (
                      <button
                        key={condition.id}
                        type="button"
                        className={`flex min-h-20 items-center gap-4 border px-4 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] ${selected ? 'border-[#168447] bg-[#edf8f0]' : 'border-[#d5ddd8] bg-white hover:border-[#9eb0a5]'}`}
                        onClick={() => toggleCondition(condition.id)}
                        aria-pressed={selected}
                      >
                        <span className={`grid size-10 shrink-0 place-items-center ${selected ? 'bg-[#168447] text-white' : 'bg-[#e9efeb] text-[#52665a]'}`}>
                          <Icon className="size-5" aria-hidden="true" />
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block text-sm font-bold text-[#24342b]">{condition.label}</span>
                          <span className="mt-0.5 block text-xs leading-4 text-[#6d7a72]">{condition.detail}</span>
                        </span>
                        <span className={`grid size-5 shrink-0 place-items-center border ${selected ? 'border-[#168447] bg-[#168447] text-white' : 'border-[#aab7af] text-transparent'}`}>
                          <Check className="size-3.5" aria-hidden="true" />
                        </span>
                      </button>
                    )
                  })}
                </div>
              </section>
            )}

            {step === 1 && (
              <section aria-labelledby="weights-title">
                <StepHeading eyebrow="Priorities" title="Tune what matters on your route" detail="Set each factor from ignore to high. Your selections have already seeded a starting point." id="weights-title" />
                <div className="mt-7 space-y-8">
                  {hazardGroups.map((group) => (
                    <div key={group.label}>
                      <h3 className="border-b border-[#d9e0db] pb-2 text-xs font-bold uppercase text-[#5f7066]">{group.label}</h3>
                      <div className="grid gap-x-10 lg:grid-cols-2">
                        {group.hazards.map((hazard) => (
                          <WeightControl
                            key={hazard.id}
                            hazard={hazard.id}
                            label={hazard.label}
                            value={profile.weights[hazard.id]}
                            onChange={profile.setWeight}
                          />
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </section>
            )}

            {step === 2 && (
              <section aria-labelledby="tolerance-title">
                <StepHeading eyebrow="Effort & tolerance" title="Set the boundaries for a better route" detail="These limits keep lower-exposure options practical for your trip." id="tolerance-title" />
                <div className="mt-8 divide-y divide-[#d9e0db] border-y border-[#d9e0db] bg-white">
                  <RangeSetting
                    label="Maximum hill grade"
                    detail="Routes above this grade are strongly deprioritized."
                    value={profile.maxGradePct}
                    min={0}
                    max={20}
                    step={1}
                    display={`${profile.maxGradePct}%`}
                    onChange={profile.setMaxGradePct}
                  />
                  <RangeSetting
                    label="Extra travel for lower exposure"
                    detail="Balanced and cleanest options stay within this allowance."
                    value={Math.round(profile.detourTolerance * 100)}
                    min={0}
                    max={100}
                    step={5}
                    display={`${Math.round(profile.detourTolerance * 100)}%`}
                    onChange={(value) => profile.setDetourTolerance(value / 100)}
                  />
                </div>
                <div className="mt-6 grid grid-cols-3 border border-[#d5ddd8] bg-[#edf3ee] text-center">
                  <ToleranceStat label="Direct" value="0%" active={profile.detourTolerance === 0} />
                  <ToleranceStat label="Default" value="25%" active={profile.detourTolerance === 0.25} />
                  <ToleranceStat label="Flexible" value="50%+" active={profile.detourTolerance >= 0.5} />
                </div>
              </section>
            )}

            {step === 3 && (
              <section aria-labelledby="traveler-title">
                <StepHeading eyebrow="Traveler" title="Who is taking this route?" detail="Beacon adjusts how cautiously it applies the priorities you selected." id="traveler-title" />
                <div className="mt-7 grid gap-3 md:grid-cols-3">
                  {travelers.map((traveler) => {
                    const Icon = traveler.icon
                    const selected = traveler.id === profile.traveler
                    return (
                      <button
                        key={traveler.id}
                        type="button"
                        className={`min-h-40 border p-5 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] ${selected ? 'border-[#168447] bg-[#edf8f0]' : 'border-[#d5ddd8] bg-white hover:border-[#9eb0a5]'}`}
                        onClick={() => profile.setTraveler(traveler.id)}
                        aria-pressed={selected}
                      >
                        <span className={`grid size-10 place-items-center ${selected ? 'bg-[#168447] text-white' : 'bg-[#e8eeea] text-[#52665a]'}`}>
                          <Icon className="size-5" aria-hidden="true" />
                        </span>
                        <span className="mt-5 block text-sm font-bold text-[#24342b]">{traveler.label}</span>
                        <span className="mt-1 block text-xs leading-5 text-[#6d7a72]">{traveler.detail}</span>
                      </button>
                    )
                  })}
                </div>

                <div className="mt-7 border-l-2 border-[#168447] bg-white px-5 py-4">
                  <div className="flex items-center gap-2 text-xs font-bold uppercase text-[#5f7066]">
                    <Sparkles className="size-4 text-[#168447]" aria-hidden="true" />
                    Profile summary
                  </div>
                  <p className="mt-3 text-sm font-semibold text-[#27372e]">
                    {selectedConditionLabels.join(', ') || 'Neutral sensitivities'}
                  </p>
                  <p className="mt-1 text-xs leading-5 text-[#68766e]">
                    {topPriorities.length > 0
                      ? `Highest priorities: ${topPriorities.map((hazard) => hazard.label).join(', ')}.`
                      : 'No exposure priorities are currently elevated.'}
                    {' '}Up to {Math.round(profile.detourTolerance * 100)}% extra travel and {profile.maxGradePct}% maximum grade.
                  </p>
                </div>
              </section>
            )}
          </div>
        </main>
      </div>

      <footer className="flex h-20 shrink-0 items-center justify-between border-t border-[#d9e0db] bg-[#fbfcfb] px-5 sm:px-8 md:pl-[272px]">
        <button
          type="button"
          className="flex h-11 items-center gap-2 px-3 text-sm font-semibold text-[#526159] hover:bg-[#edf2ef] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] disabled:opacity-30"
          onClick={() => setStep((current) => current - 1)}
          disabled={step === 0}
        >
          <ChevronLeft className="size-4" aria-hidden="true" />
          Back
        </button>
        {step < steps.length - 1 ? (
          <button
            type="button"
            className="flex h-11 items-center gap-2 bg-[#168447] px-5 text-sm font-bold text-white hover:bg-[#106c3b] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447] disabled:bg-[#a8b5ae]"
            onClick={() => setStep((current) => current + 1)}
            disabled={nextDisabled}
          >
            Continue
            <ChevronRight className="size-4" aria-hidden="true" />
          </button>
        ) : (
          <button type="button" className="flex h-11 items-center gap-2 bg-[#168447] px-5 text-sm font-bold text-white hover:bg-[#106c3b] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]" onClick={finish}>
            <Check className="size-4" aria-hidden="true" />
            Finish setup
          </button>
        )}
      </footer>
    </div>
  )
}

function StepHeading({ eyebrow, title, detail, id }: { eyebrow: string; title: string; detail: string; id: string }) {
  return (
    <div>
      <p className="text-xs font-bold uppercase text-[#168447]">{eyebrow}</p>
      <h2 id={id} className="mt-2 max-w-2xl text-2xl font-extrabold leading-tight text-[#173027] sm:text-3xl">{title}</h2>
      <p className="mt-3 max-w-2xl text-sm leading-6 text-[#68766e]">{detail}</p>
    </div>
  )
}

function WeightControl({ hazard, label, value, onChange }: { hazard: HazardId; label: string; value: number; onChange: (hazard: HazardId, value: number) => void }) {
  const position = Math.round(value)
  return (
    <label className="grid min-h-20 grid-cols-[minmax(0,1fr)_110px] items-center gap-5 border-b border-[#e0e5e2] py-3">
      <span className="min-w-0">
        <span className="block text-sm font-semibold text-[#2c3b32]">{label}</span>
        <span className="mt-0.5 block text-xs font-medium text-[#168447]">{weightLabels[position]}</span>
      </span>
      <input
        type="range"
        min="0"
        max="3"
        step="1"
        value={position}
        onChange={(event) => onChange(hazard, Number(event.target.value))}
        className="beacon-range w-full"
        aria-label={`${label} priority`}
      />
    </label>
  )
}

function RangeSetting({ label, detail, value, min, max, step, display, onChange }: { label: string; detail: string; value: number; min: number; max: number; step: number; display: string; onChange: (value: number) => void }) {
  return (
    <label className="grid gap-5 px-5 py-6 sm:grid-cols-[minmax(0,1fr)_minmax(220px,300px)] sm:items-center">
      <span>
        <span className="flex items-center gap-3">
          <span className="text-base font-bold text-[#28382f]">{label}</span>
          <span className="bg-[#e5f2e9] px-2 py-1 text-sm font-extrabold text-[#126d42]">{display}</span>
        </span>
        <span className="mt-1 block text-xs leading-5 text-[#6d7a72]">{detail}</span>
      </span>
      <input type="range" min={min} max={max} step={step} value={value} onChange={(event) => onChange(Number(event.target.value))} className="beacon-range w-full" />
    </label>
  )
}

function ToleranceStat({ label, value, active }: { label: string; value: string; active: boolean }) {
  return (
    <div className={`px-3 py-4 ${active ? 'bg-white text-[#126d42]' : 'text-[#69776f]'}`}>
      <p className="text-xs font-bold uppercase">{label}</p>
      <p className="mt-1 text-sm font-extrabold">{value}</p>
    </div>
  )
}
