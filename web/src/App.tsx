import { useState } from 'react'
import { ConditionsBanner } from './components/ConditionsBanner'
import { DataAttribution } from './components/DataAttribution'
import { DisclaimerFooter, MedicalDisclaimer } from './components/MedicalDisclaimer'
import { OnboardingFlow } from './components/OnboardingFlow'
import { RoutePlanner } from './components/RoutePlanner'
import { useProfileStore } from './store/profileStore'

function App() {
  const onboardingComplete = useProfileStore((state) => state.onboardingComplete)
  const [profileOpen, setProfileOpen] = useState(!onboardingComplete)

  return (
    <div className="app-shell flex h-dvh min-h-[600px] flex-col bg-[#eef2ef] text-[#073b3a]">
      <MedicalDisclaimer />
      <ConditionsBanner onEditProfile={() => setProfileOpen(true)} />
      <RoutePlanner />
      <div className="flex items-center justify-between gap-4 border-t border-[#e1e6e3] bg-[#fbfcfb] px-5 py-1">
        <DataAttribution />
      </div>
      <DisclaimerFooter />
      {profileOpen && (
        <OnboardingFlow
          canClose={onboardingComplete}
          onClose={() => setProfileOpen(false)}
        />
      )}
    </div>
  )
}

export default App
