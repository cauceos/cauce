import { useEffect, useRef } from 'react'
import type { RefObject } from 'react'

/**
 * The contract every dismissable overlay shares — the mobile drawer, the
 * bottom sheets — stated once so it cannot drift between them:
 *
 * · Escape closes it.
 * · The page behind it does not scroll while it is open.
 * · Focus returns to where it came from on close: the given trigger when
 *   there is one (a hamburger, a FAB), else whatever element was focused
 *   at the moment the overlay opened (a tree row). The sheet that just
 *   left must never strand focus on a hidden element.
 *
 * Purely reactive on `open`; the caller owns the state and the close.
 */
export function useOverlay(
  open: boolean,
  onClose: () => void,
  returnFocusTo?: RefObject<HTMLElement | null>,
): void {
  const openerRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, onClose])

  useEffect(() => {
    if (!open) return
    // Restored on close, so a body overflow set elsewhere keeps its value.
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
    }
  }, [open])

  useEffect(() => {
    if (open) {
      const active = document.activeElement
      openerRef.current = active instanceof HTMLElement ? active : null
      return
    }
    const target = returnFocusTo?.current ?? openerRef.current
    openerRef.current = null
    // Only if it is still on the page — a row that was re-rendered away
    // must not throw.
    if (target !== null && target !== undefined && document.contains(target)) target.focus()
  }, [open, returnFocusTo])
}
