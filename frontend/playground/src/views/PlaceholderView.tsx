/**
 * Shared body for the not-yet-built areas. Each area keeps its own view
 * file (and directory) so later units grow in place.
 */
export function PlaceholderView({ area }: { area: string }) {
  return (
    <div className="session-wrap">
      <div className="eyebrow">Playground · {area}</div>
      <h1>
        Not built <em>yet</em>.
      </h1>
      <p className="lede">
        The {area} area arrives in a later unit. The session, the navigation, and the API client
        it will use are already in place.
      </p>
    </div>
  )
}
