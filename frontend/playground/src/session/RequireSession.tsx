import type { ReactNode } from 'react'
import { Navigate } from 'react-router'
import { useSession } from './SessionContext'

/**
 * Route guard: the locked areas render only with an active session.
 * A page refresh forgets the API key by design (memory-only session), so
 * any deep link or refresh lands back on the Session screen.
 */
export function RequireSession({ children }: { children: ReactNode }) {
  const { status } = useSession()
  if (status !== 'connected') {
    return <Navigate to="/" replace />
  }
  return <>{children}</>
}
