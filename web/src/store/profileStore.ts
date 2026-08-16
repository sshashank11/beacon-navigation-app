import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type ConditionId =
  | 'asthma'
  | 'allergies'
  | 'copd'
  | 'chemical_sensitivity'
  | 'cardiac'
  | 'pregnancy'
  | 'none'

export type TravelerType = 'just_me' | 'child' | 'older_adult'

export type HazardId =
  | 'pm25'
  | 'ozone'
  | 'no2'
  | 'pollen_tree'
  | 'pollen_grass'
  | 'pollen_weed'
  | 'traffic_prox'
  | 'construction'
  | 'industrial_prox'
  | 'grade'
  | 'heat'
  | 'cold_air'
  | 'humidity'
  | 'crowd_density'
  | 'shade_deficit'

export type HazardWeights = Record<HazardId, number>

interface Preset {
  weights: Partial<HazardWeights>
  hardAvoids: string[]
  maxGradePct: number
}

interface ProfileState {
  conditions: ConditionId[]
  weights: HazardWeights
  hardAvoids: string[]
  maxGradePct: number
  detourTolerance: number
  traveler: TravelerType
  conservatism: number
  onboardingComplete: boolean
  setConditions: (conditions: ConditionId[]) => void
  setWeight: (hazard: HazardId, weight: number) => void
  setMaxGradePct: (value: number) => void
  setDetourTolerance: (value: number) => void
  setTraveler: (traveler: TravelerType) => void
  completeOnboarding: () => void
}

export const hazardIds: HazardId[] = [
  'pm25',
  'ozone',
  'no2',
  'pollen_tree',
  'pollen_grass',
  'pollen_weed',
  'traffic_prox',
  'construction',
  'industrial_prox',
  'grade',
  'heat',
  'cold_air',
  'humidity',
  'crowd_density',
  'shade_deficit',
]

const emptyWeights = Object.fromEntries(hazardIds.map((hazard) => [hazard, 0])) as HazardWeights

const presets: Record<ConditionId, Preset> = {
  asthma: {
    weights: { pm25: 3, ozone: 3, traffic_prox: 2, construction: 2, cold_air: 1.5 },
    hardAvoids: [],
    maxGradePct: 20,
  },
  allergies: {
    weights: { pollen_tree: 3, pollen_grass: 3, pollen_weed: 3, humidity: 1.5 },
    hardAvoids: [],
    maxGradePct: 20,
  },
  copd: {
    weights: { pm25: 3, ozone: 3, grade: 3, traffic_prox: 2, heat: 1.5, cold_air: 1.5 },
    hardAvoids: ['grade_above_6_pct'],
    maxGradePct: 6,
  },
  chemical_sensitivity: {
    weights: { industrial_prox: 3, construction: 3, traffic_prox: 1.5, no2: 1.5 },
    hardAvoids: ['active_construction_frontage', 'industrial_within_200m'],
    maxGradePct: 20,
  },
  cardiac: {
    weights: { pm25: 3, heat: 3, grade: 3, ozone: 1.5 },
    hardAvoids: ['grade_above_5_pct'],
    maxGradePct: 5,
  },
  pregnancy: {
    weights: {},
    hardAvoids: [],
    maxGradePct: 20,
  },
  none: {
    weights: {},
    hardAvoids: [],
    maxGradePct: 20,
  },
}

const travelerConservatism: Record<TravelerType, number> = {
  just_me: 1,
  child: 0.7,
  older_adult: 0.85,
}

export const useProfileStore = create<ProfileState>()(
  persist(
    (set) => ({
      conditions: [],
      weights: { ...emptyWeights },
      hardAvoids: [],
      maxGradePct: 20,
      detourTolerance: 0.25,
      traveler: 'just_me',
      conservatism: travelerConservatism.just_me,
      onboardingComplete: false,
      setConditions: (conditions) => set(seedConditions(conditions)),
      setWeight: (hazard, weight) => set((state) => ({
        weights: { ...state.weights, [hazard]: weight },
      })),
      setMaxGradePct: (maxGradePct) => set({ maxGradePct }),
      setDetourTolerance: (detourTolerance) => set({ detourTolerance }),
      setTraveler: (traveler) => set({
        traveler,
        conservatism: travelerConservatism[traveler],
      }),
      completeOnboarding: () => set({ onboardingComplete: true }),
    }),
    {
      name: 'beacon-trigger-profile',
      version: 1,
    },
  ),
)

function seedConditions(conditions: ConditionId[]) {
  const selected = conditions.includes('none') ? ['none' as const] : conditions
  const weights = { ...emptyWeights }
  const hardAvoids = new Set<string>()
  let maxGradePct = 20

  for (const condition of selected) {
    const preset = presets[condition]
    for (const [hazard, weight] of Object.entries(preset.weights)) {
      const key = hazard as HazardId
      weights[key] = Math.max(weights[key], weight ?? 0)
    }
    preset.hardAvoids.forEach((avoid) => hardAvoids.add(avoid))
    maxGradePct = Math.min(maxGradePct, preset.maxGradePct)
  }

  return {
    conditions: selected,
    weights,
    hardAvoids: [...hardAvoids],
    maxGradePct,
  }
}
