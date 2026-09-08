import { EstratoLogo } from '../EstratoLogo'

/**
 * Logo + wordmark + playground badge. `compact` is the mobile-topbar variant
 * (smaller mark; the smaller wordmark and badge come from `.topbar .wordmark`
 * / `.topbar .playground-badge` in shell.css).
 */
export function LogoLockup({ compact = false }: { compact?: boolean }) {
  return (
    <div className="logo-lockup">
      <EstratoLogo width={compact ? 32 : 43} height={compact ? 25 : 33} />
      <span className="wordmark">
        Cau<em>ce</em>
      </span>
      <span className="playground-badge">play</span>
    </div>
  )
}
