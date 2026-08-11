import { EstratoLogo } from '../EstratoLogo'

/**
 * Logo + wordmark + playground badge, as specified in the mockup.
 * `compact` is the mobile-topbar variant (smaller mark; the smaller
 * wordmark comes from `.topbar .wordmark` in shell.css).
 */
export function LogoLockup({ compact = false }: { compact?: boolean }) {
  return (
    <div className="logo-lockup">
      <EstratoLogo width={compact ? 34 : 42} height={compact ? 26 : 32} />
      <span className="wordmark">
        Cau<em>ce</em>
      </span>
      <span className="playground-badge">play</span>
    </div>
  )
}
