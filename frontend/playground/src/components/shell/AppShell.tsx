import { useCallback, useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { useOverlay } from '../../lib/useOverlay'
import { Sidebar } from './Sidebar'
import { Topbar } from './Topbar'

/**
 * Application shell: grain, topbar (mobile), sidebar/rail/drawer, backdrop,
 * and the main outlet.
 *
 * Drawer contract: the hamburger toggles it; it closes on the backdrop, on
 * Escape, and on picking an item; the page behind it does not scroll; focus
 * returns to the hamburger on every close. Escape, the scroll lock and the
 * focus return are useOverlay — the same contract the bottom sheets keep.
 */
export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const location = useLocation()

  const closeDrawer = useCallback(() => setDrawerOpen(false), [])
  useOverlay(drawerOpen, closeDrawer, menuButtonRef)

  useEffect(() => {
    // Close on navigation ("picking an item closes the drawer"). Keyed on the
    // route only — must not re-run when drawerOpen itself changes.
    if (drawerOpen) closeDrawer()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname])

  return (
    <div className="app">
      <div className="grain" aria-hidden="true" />
      <Topbar open={drawerOpen} onToggle={() => setDrawerOpen((open) => !open)} menuRef={menuButtonRef} />
      <Sidebar open={drawerOpen} />
      <div
        className={drawerOpen ? 'backdrop show' : 'backdrop'}
        onClick={closeDrawer}
        aria-hidden="true"
      />
      <main className="main">
        <Outlet />
      </main>
    </div>
  )
}
