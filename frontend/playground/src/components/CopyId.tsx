import { useClipboard } from '../lib/useClipboard'

/**
 * The `⧉` copy affordance from the mockups: copies the full id (not the
 * truncated display) and flips to a checkmark briefly. Ids are the currency
 * between areas until a "who am I" endpoint exists, so every id gets one.
 */
export function CopyId({ value, label = 'Copy id' }: { value: string; label?: string }) {
  const { copied, copy } = useClipboard()
  return (
    <button
      type="button"
      className={copied ? 'copy copied' : 'copy'}
      title={copied ? 'Copied' : label}
      aria-label={copied ? 'Copied' : label}
      onClick={() => copy(value)}
    >
      {copied ? '✓' : '⧉'}
    </button>
  )
}
