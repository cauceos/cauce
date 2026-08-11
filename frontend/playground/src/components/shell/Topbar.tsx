import type { RefObject } from 'react'
import { LogoLockup } from './LogoLockup'

/** Mobile-only fixed topbar with the drawer toggle. */
export function Topbar({
  open,
  onToggle,
  menuRef,
}: {
  open: boolean
  onToggle: () => void
  menuRef: RefObject<HTMLButtonElement | null>
}) {
  return (
    <header className="topbar">
      <LogoLockup compact />
      <button
        ref={menuRef}
        className="menu-btn"
        type="button"
        aria-label="Open navigation"
        aria-expanded={open}
        aria-controls="drawer"
        onClick={onToggle}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round">
          <path d="M4 7h16M4 12h16M4 17h16" />
        </svg>
      </button>
    </header>
  )
}
