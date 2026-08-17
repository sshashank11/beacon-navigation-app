const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export interface Account {
  id: string
  email: string
}

/**
 * Credentials live in memory for the tab's lifetime and are never written to
 * localStorage or sessionStorage. HTTP Basic means the password is replayed on
 * every request, so persisting it would leave it readable by any script on the
 * page long after the person stopped using the app. A refresh signs you out,
 * which is the honest trade until the API issues tokens instead.
 */
let credentials: { email: string; password: string } | null = null
const listeners = new Set<(account: Account | null) => void>()
let account: Account | null = null

export function authHeaders(): Record<string, string> {
  if (!credentials) return {}
  const encoded = btoa(`${credentials.email}:${credentials.password}`)
  return { Authorization: `Basic ${encoded}` }
}

export function currentAccount(): Account | null {
  return account
}

export function subscribeToAccount(listener: (next: Account | null) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function setAccount(next: Account | null) {
  account = next
  listeners.forEach((listener) => listener(next))
}

export function signOut() {
  credentials = null
  setAccount(null)
}

export async function signIn(email: string, password: string): Promise<Account> {
  credentials = { email, password }
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/me`, {
    headers: { Accept: 'application/json', ...authHeaders() },
  })

  if (!response.ok) {
    credentials = null
    setAccount(null)
    throw new Error(
      response.status === 401
        ? 'That email and password did not match an account.'
        : `Sign in failed with status ${response.status}`,
    )
  }

  const signedIn = (await response.json()) as Account
  setAccount(signedIn)
  return signedIn
}

/**
 * Deletes the account and everything derived from it.
 *
 * <p>Self-reported sensitivities are health-adjacent, and the person they
 * belong to should be able to remove their trace without asking anyone.
 * Routes and their feedback cascade from the account row.
 */
export async function deleteAccount(): Promise<void> {
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/me`, {
    method: 'DELETE',
    headers: { Accept: 'application/json', ...authHeaders() },
  })
  if (!response.ok) {
    throw new Error(`Deleting the account failed with status ${response.status}`)
  }
  signOut()
}

export async function register(email: string, password: string): Promise<Account> {
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/register`, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })

  if (!response.ok) {
    throw new Error(
      response.status === 409
        ? 'That email address already has an account.'
        : 'Registration needs a valid email and a password of at least 12 characters.',
    )
  }

  await response.json()
  return signIn(email, password)
}
