import { useState } from 'react'
import { ConditionsBanner } from './components/ConditionsBanner'
import { OnboardingFlow } from './components/OnboardingFlow'
import { RoutePlanner } from './components/RoutePlanner'
import { useProfileStore } from './store/profileStore'

function App() {
  const onboardingComplete = useProfileStore((state) => state.onboardingComplete)
  const [profileOpen, setProfileOpen] = useState(!onboardingComplete)

  return (
    <div className="app-shell flex h-dvh min-h-[600px] flex-col bg-[#eef2ef] text-[#073b3a]">
      <ConditionsBanner onEditProfile={() => setProfileOpen(true)} />
      <RoutePlanner />
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
