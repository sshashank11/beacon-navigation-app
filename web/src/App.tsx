import { ConditionsBanner } from './components/ConditionsBanner'

function App() {
  return (
    <div className="flex min-h-screen flex-col bg-[#eef2ef] text-[#17211b]">
      <ConditionsBanner />
      <main className="grid flex-1 place-items-center px-6">
        <h1 className="text-4xl font-semibold">Beacon</h1>
      </main>
    </div>
  )
}

export default App
