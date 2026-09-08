import { useCallback, useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { Sidebar } from './Sidebar'
import { Topbar } from './Topbar'

/**
 * Application shell: grain, topbar (mobile), sidebar/rail/drawer, backdrop,
 * and the main outlet.
 *
 * Drawer contract: the hamburger toggles it; it closes on the backdrop, on
 * Escape, and on picking an item; the page behind it does not scroll; focus
 * returns to the trigger on every close, because focus was inside the panel
 * that just left.
 */
export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const location = useLocation()

  // Every close returns focus to the toggle — the drawer is gone and focus
  // must not be stranded on a hidden element.
  const closeDrawer = useCallback(() => {
    setDrawerOpen(false)
    menuButtonRef.current?.focus()
  }, [])

  useEffect(() => {
    // Close on navigation ("picking an item closes the drawer"). Keyed on the
    // route only — must not re-run when drawerOpen itself changes.
    if (drawerOpen) closeDrawer()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname])

  useEffect(() => {
    if (!drawerOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeDrawer()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [drawerOpen, closeDrawer])

  useEffect(() => {
    if (!drawerOpen) return
    // No scrolling behind the drawer. Restored on close, so an app that
    // already had a body overflow set keeps it.
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
    }
  }, [drawerOpen])

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
