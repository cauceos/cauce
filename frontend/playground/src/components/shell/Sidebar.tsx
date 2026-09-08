import { Fragment } from 'react'
import { NavLink } from 'react-router'
import { useSession } from '../../session/SessionContext'
import { LogoLockup } from './LogoLockup'
import { NAV_GROUPS } from './navItems'
import { SessionStatusFoot } from './SessionStatusFoot'

/** Shown on every locked entry; fades in on hover (always on, on touch). */
function LockGlyph() {
  return (
    <svg
      className="lock"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2.2}
      strokeLinecap="round"
      aria-hidden="true"
    >
      <rect x="5" y="11" width="14" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  )
}

/**
 * The navigation column, in its three regimes. Desktop: full 248px sidebar.
 * Tablet: 68px icon rail, where `data-tip` becomes a real tooltip rendered
 * outside the rail (shell.css) — the labels relocate, they do not vanish.
 * Mobile: the same element is the slide-in drawer (`open` toggles the
 * transform) and both labels and group names come back.
 *
 * Locked entries are inert spans carrying `role="link"` + `aria-disabled`:
 * a real disabled state, neither focusable nor navigable, not a dimmed link.
 * Why they are locked is stated once, in the foot, rather than repeated as a
 * tooltip on all six.
 */
export function Sidebar({ open }: { open: boolean }) {
  const { status } = useSession()
  const unlocked = status === 'connected'

  return (
    <aside className={open ? 'sidebar open' : 'sidebar'} id="drawer">
      <LogoLockup />

      <nav className="nav">
        {NAV_GROUPS.map((group) => (
          <Fragment key={group.eyebrow}>
            {/* At rail width this row is restyled into a hairline divider: the
                grouping survives, only the word goes (it stays for readers). */}
            <div className="nav-eyebrow">{group.eyebrow}</div>
            {group.items.map((item) =>
              item.requiresSession && !unlocked ? (
                <span
                  key={item.path}
                  className="nav-item locked"
                  role="link"
                  aria-disabled="true"
                  data-tip={`${item.label} — locked`}
                >
                  {item.icon}
                  <span className="lbl">{item.label}</span>
                  <LockGlyph />
                </span>
              ) : (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.path === '/'}
                  className={({ isActive }) => (isActive ? 'nav-item active' : 'nav-item')}
                  data-tip={item.label}
                >
                  {item.icon}
                  <span className="lbl">{item.label}</span>
                </NavLink>
              ),
            )}
          </Fragment>
        ))}
      </nav>

      <SessionStatusFoot />
    </aside>
  )
}
