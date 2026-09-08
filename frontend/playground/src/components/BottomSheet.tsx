import type { ReactNode, RefObject } from 'react'
import { useOverlay } from '../lib/useOverlay'

/**
 * A panel that slides up from the bottom edge — where a side column goes
 * when the viewport has none (below 700px). Closes on the backdrop, on
 * Escape, and on the handle; locks body scroll; returns focus on close.
 * That contract lives in useOverlay, shared with the drawer.
 *
 * Always mounted so the slide can animate; `open` drives the transform and
 * the visibility flip that keeps a closed sheet out of the tab order.
 */
export function BottomSheet({
  open,
  onClose,
  label,
  returnFocusTo,
  children,
}: {
  open: boolean
  onClose: () => void
  /** Accessible name of the dialog. */
  label: string
  returnFocusTo?: RefObject<HTMLElement | null>
  children: ReactNode
}) {
  useOverlay(open, onClose, returnFocusTo)
  return (
    <>
      <div className={open ? 'sheet-back show' : 'sheet-back'} onClick={onClose} aria-hidden="true" />
      <aside className={open ? 'sheet open' : 'sheet'} role="dialog" aria-modal="true" aria-label={label}>
        <button className="handle" type="button" aria-label="Close" onClick={onClose} />
        {children}
      </aside>
    </>
  )
}
