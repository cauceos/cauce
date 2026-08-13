import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Copy-to-clipboard with a brief "copied" flag for visual feedback. The
 * flag auto-clears after `resetMs`. Falls back to a hidden-textarea +
 * `execCommand('copy')` when the async Clipboard API is unavailable (it
 * needs a secure context — the playground often runs on plain http).
 */
export function useClipboard(resetMs = 1200): { copied: boolean; copy(text: string): void } {
  const [copied, setCopied] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(
    () => () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current)
    },
    [],
  )

  const flag = useCallback(() => {
    setCopied(true)
    if (timerRef.current !== null) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => setCopied(false), resetMs)
  }, [resetMs])

  const copy = useCallback(
    (text: string) => {
      if (navigator.clipboard?.writeText != null) {
        navigator.clipboard.writeText(text).then(flag, () => fallbackCopy(text, flag))
      } else {
        fallbackCopy(text, flag)
      }
    },
    [flag],
  )

  return { copied, copy }
}

function fallbackCopy(text: string, onDone: () => void): void {
  const area = document.createElement('textarea')
  area.value = text
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  try {
    document.execCommand('copy')
    onDone()
  } catch {
    // Nothing else to try; leave the flag unset so no false "copied".
  } finally {
    document.body.removeChild(area)
  }
}
