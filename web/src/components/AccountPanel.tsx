import { useEffect, useState, type FormEvent } from 'react'
import { LogOut, Trash2, UserRound } from 'lucide-react'
import {
  currentAccount,
  deleteAccount,
  register,
  signIn,
  signOut,
  subscribeToAccount,
  type Account,
} from '../api/auth'

/**
 * Minimal account controls.
 *
 * <p>Signing in is only needed for the parts of the app that read something
 * back: saved routes, their imagery analysis, and feedback. Planning a route
 * works without an account, so this stays out of the way until it is needed.
 */
export function AccountPanel() {
  const [account, setAccount] = useState<Account | null>(currentAccount())
  const [open, setOpen] = useState(false)
  const [mode, setMode] = useState<'sign-in' | 'register'>('sign-in')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  useEffect(() => subscribeToAccount(setAccount), [])

  async function remove() {
    setBusy(true)
    setError(null)
    try {
      await deleteAccount()
      setConfirmingDelete(false)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not delete the account')
    } finally {
      setBusy(false)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'register') {
        await register(email, password)
      } else {
        await signIn(email, password)
      }
      setOpen(false)
      setPassword('')
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  if (account) {
    return (
      <div className="space-y-1.5 text-xs text-[#526159]">
        <div className="flex items-center gap-2">
          <UserRound className="size-4" aria-hidden />
          <span className="truncate" title={account.email}>
            {account.email}
          </span>
          <button
            type="button"
            onClick={signOut}
            className="ml-auto flex items-center gap-1 border border-[#d4dcd7] bg-white px-2 py-1 text-[#25543c] transition-colors hover:bg-[#eef3f0] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
          >
            <LogOut className="size-3.5" aria-hidden />
            Sign out
          </button>
        </div>
        {confirmingDelete ? (
          <div className="border border-[#e8c4bc] bg-[#fff8f6] px-2 py-1.5">
            <p className="text-[11px] text-[#8d382d]">
              Delete this account and every route and note saved with it? This
              cannot be undone.
            </p>
            <div className="mt-1.5 flex gap-2">
              <button
                type="button"
                onClick={() => void remove()}
                disabled={busy}
                className="flex items-center gap-1 bg-[#b84d3e] px-2 py-1 text-[11px] font-medium text-white transition-colors hover:bg-[#a04234] disabled:opacity-50"
              >
                <Trash2 className="size-3" aria-hidden />
                {busy ? 'Deleting…' : 'Delete everything'}
              </button>
              <button
                type="button"
                onClick={() => setConfirmingDelete(false)}
                className="border border-[#d4dcd7] bg-white px-2 py-1 text-[11px] text-[#526159]"
              >
                Keep it
              </button>
            </div>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setConfirmingDelete(true)}
            className="text-[11px] text-[#8d382d] underline decoration-dotted transition-colors hover:text-[#6f2b21]"
          >
            Delete my account and data
          </button>
        )}
        {error && (
          <p className="text-[11px] text-[#8d382d]" role="alert">
            {error}
          </p>
        )}
      </div>
    )
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="flex items-center gap-1.5 border border-[#d4dcd7] bg-white px-2.5 py-1 text-xs text-[#25543c] transition-colors hover:bg-[#eef3f0] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
      >
        <UserRound className="size-3.5" aria-hidden />
        Sign in
      </button>
    )
  }

  return (
    <form onSubmit={submit} className="space-y-2 border border-[#d4dcd7] bg-white p-3">
      <div className="flex gap-2 text-xs">
        {(['sign-in', 'register'] as const).map((option) => (
          <button
            key={option}
            type="button"
            onClick={() => setMode(option)}
            className={`px-2 py-1 transition-colors ${
              mode === option
                ? 'bg-[#168447] text-white'
                : 'border border-[#d4dcd7] text-[#526159] hover:bg-[#eef3f0]'
            }`}
          >
            {option === 'sign-in' ? 'Sign in' : 'Create account'}
          </button>
        ))}
      </div>
      <label className="block text-xs text-[#526159]">
        Email
        <input
          type="email"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          className="mt-1 w-full border border-[#d4dcd7] px-2 py-1 text-sm text-[#243029] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
        />
      </label>
      <label className="block text-xs text-[#526159]">
        Password
        <input
          type="password"
          required
          minLength={12}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          className="mt-1 w-full border border-[#d4dcd7] px-2 py-1 text-sm text-[#243029] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
        />
        {mode === 'register' && (
          <span className="mt-1 block text-[11px] text-[#7b8a82]">
            At least 12 characters. Length is what resists guessing.
          </span>
        )}
      </label>
      {error && (
        <p className="border-l-2 border-[#b84d3e] bg-[#fff3f0] px-2 py-1 text-[11px] text-[#8d382d]" role="alert">
          {error}
        </p>
      )}
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={busy}
          className="bg-[#168447] px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-[#12703c] disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#168447]"
        >
          {busy ? 'Working…' : mode === 'sign-in' ? 'Sign in' : 'Create account'}
        </button>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="border border-[#d4dcd7] px-3 py-1.5 text-xs text-[#526159] transition-colors hover:bg-[#eef3f0]"
        >
          Cancel
        </button>
      </div>
      <p className="text-[11px] text-[#7b8a82]">
        Your session lasts until you close the tab; nothing is stored on this device.
      </p>
    </form>
  )
}
