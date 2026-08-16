import { ConditionsBanner } from './components/ConditionsBanner'
import { RoutePlanner } from './components/RoutePlanner'

function App() {
  return (
    <div className="flex h-dvh min-h-[600px] flex-col bg-[#eef2ef] text-[#073b3a]">
      <ConditionsBanner />
      <RoutePlanner />
    </div>
  )
}

export default App
