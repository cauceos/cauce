import type { VerificationScope } from '../../api/types'

/**
 * What the verification covers, and what it cannot — at the same visual
 * rank as the verdict, because a VALID is only credible next to what
 * VALID cannot mean.
 *
 * Every string here comes from the response, rendered verbatim. None of it
 * is hardcoded and none of it is paraphrased: this is the product's
 * honesty surface, and it has to say what the backend says it does. When
 * the backend's capability changes, this panel changes with it and the
 * layout does not.
 *
 * It renders identically for VALID and BROKEN. A limit that only appears
 * when things go wrong is not disclosure.
 */
export function ScopePanel({ scope }: { scope: VerificationScope }) {
  return (
    <aside className="panel scope">
      <h3>What this verifies</h3>
      <p className="method">{scope.method}</p>

      <div className="sb det">
        <span className="k">Detects</span>
        <ul>
          {scope.detects.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      </div>

      <div className="sb lim">
        <span className="k">Does not detect</span>
        <p>{scope.does_not_detect}</p>
      </div>

      <span className="scope-foot">
        This text comes verbatim from the API. It is part of the verdict, not a footnote.
      </span>
    </aside>
  )
}
