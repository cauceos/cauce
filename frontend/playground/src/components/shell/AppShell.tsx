import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { Sidebar } from './Sidebar'
import { Topbar } from './Topbar'

/**
 * Application shell: topbar (mobile), sidebar/drawer, backdrop, and the
 * main outlet. Drawer contract from the mockup: hamburger toggles,
 * backdrop click closes, Escape closes, aria-expanded tracked. Beyond the
 * mockup (which never navigates): the drawer closes on route change, and
 * closing via Escape returns focus to the toggle.
 */
export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const location = useLocation()

  useEffect(() => {
    // Close on navigation; if the drawer was open, focus was inside it, so
    // return focus to the toggle (mirrors the Escape branch). Keyed on the
    // route only — must not re-run when drawerOpen itself changes.
    if (drawerOpen) {
      setDrawerOpen(false)
      menuButtonRef.current?.focus()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname])

  useEffect(() => {
    if (!drawerOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setDrawerOpen(false)
        menuButtonRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [drawerOpen])

  return (
    <div className="app">
      <Topbar open={drawerOpen} onToggle={() => setDrawerOpen((open) => !open)} menuRef={menuButtonRef} />
      <Sidebar open={drawerOpen} />
      <div
        className={drawerOpen ? 'backdrop show' : 'backdrop'}
        onClick={() => setDrawerOpen(false)}
        aria-hidden="true"
      />
      <main className="main">
        <Outlet />
      </main>
    </div>
  )
}
