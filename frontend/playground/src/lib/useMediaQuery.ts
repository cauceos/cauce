import { useEffect, useState } from 'react'

/**
 * Whether a media query currently matches, kept live. Used only where the
 * DOM itself has to differ per regime (a side panel that becomes a bottom
 * sheet) — everything CSS alone can express stays in CSS.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches)
  useEffect(() => {
    const list = window.matchMedia(query)
    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches)
    setMatches(list.matches)
    list.addEventListener('change', onChange)
    return () => list.removeEventListener('change', onChange)
  }, [query])
  return matches
}

/** The shell breakpoint below which a side panel has no column of its own. */
export const MOBILE_QUERY = '(max-width: 700px)'
