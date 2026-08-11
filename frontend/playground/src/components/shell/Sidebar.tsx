import { Fragment } from 'react'
import { NavLink } from 'react-router'
import { useSession } from '../../session/SessionContext'
import { LogoLockup } from './LogoLockup'
import { NAV_GROUPS } from './navItems'
import { SessionStatusFoot } from './SessionStatusFoot'

/**
 * The navigation column. Desktop: full 248px sidebar. Tablet: 68px icon
 * rail (CSS hides labels; the `title` attribute doubles as the tooltip, as
 * the mockup documents). Mobile: the same element becomes the slide-in
 * drawer (`open` toggles the transform).
 *
 * Locked entries render as inert spans, not links — same look as the
 * mockup's `pointer-events: none` items, correct semantics.
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
            <div className="nav-eyebrow">{group.eyebrow}</div>
            {group.items.map((item) =>
              item.requiresSession && !unlocked ? (
                <span
                  key={item.path}
                  className="nav-item locked"
                  aria-disabled="true"
                  title={`${item.label} — connect to unlock`}
                >
                  {item.icon}
                  <span className="lbl">{item.label}</span>
                </span>
              ) : (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.path === '/'}
                  className={({ isActive }) => (isActive ? 'nav-item active' : 'nav-item')}
                  title={item.label}
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
