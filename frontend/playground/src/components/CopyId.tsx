import { useClipboard } from '../lib/useClipboard'

/**
 * The copy affordance from the mockups: copies the FULL id (never the
 * truncated display) and flips to a moss check for a moment. Ids are the
 * currency between areas, so every one of them gets one. Styled by `.copy`
 * in primitives.css; grows into a bordered touch target below 700px.
 */
export function CopyId({ value, label = 'Copy id' }: { value: string; label?: string }) {
  const { copied, copy } = useClipboard(1500)
  return (
    <button
      type="button"
      className={copied ? 'copy copied' : 'copy'}
      title={copied ? 'Copied' : label}
      aria-label={copied ? 'Copied' : label}
      onClick={(event) => {
        // Rows that host this button are themselves clickable (a tree row
        // selects); copying must never double as a select.
        event.stopPropagation()
        copy(value)
      }}
    >
      {copied ? (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" aria-hidden="true">
          <path d="M5 13l4 4L19 7" />
        </svg>
      ) : (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" aria-hidden="true">
          <rect x="9" y="9" width="11" height="11" rx="2" />
          <path d="M5 15V5a2 2 0 0 1 2-2h10" />
        </svg>
      )}
    </button>
  )
}
