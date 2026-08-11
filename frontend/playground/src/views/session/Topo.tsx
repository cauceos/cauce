/**
 * Estrato topography banner — the session screen's decorative signature
 * (mockup: "wide and quiet"). Purely presentational.
 */
export function Topo() {
  return (
    <div className="topo" aria-hidden="true">
      <svg viewBox="0 0 1200 340" preserveAspectRatio="xMidYMin slice" fill="none">
        <path d="M -40 60 Q 600 560 1240 60" stroke="var(--color-stone-600)" strokeWidth="1" opacity="0.5" />
        <path d="M 80 60 Q 600 480 1120 60" stroke="var(--color-slate-500)" strokeWidth="1" opacity="0.32" />
        <path d="M 200 60 Q 600 400 1000 60" stroke="var(--color-moss-400)" strokeWidth="1" opacity="0.32" />
        <path d="M 320 60 Q 600 320 880 60" stroke="var(--color-teal-500)" strokeWidth="1.2" opacity="0.4" />
        <circle className="nadir" cx="600" cy="256" r="3" fill="var(--color-teal-500)" />
      </svg>
    </div>
  )
}
