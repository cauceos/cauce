import { CopyId } from '../../components/CopyId'
import { formatUtc, shortId } from '../../lib/format'
import type { AgentResponse, AgentStatus } from '../../api/types'

/**
 * One agent, built for disambiguation: names are not unique, so the
 * provider · model chip, the truncated id and the created timestamp are
 * always shown. The card renders what the API stores — a typo'd model shows
 * as stored; the provider rejects it at invocation time, not here.
 *
 * There is no edit or delete endpoint, so there are no such buttons — the
 * absence is stated in mono, not hidden.
 */
export function AgentCard({
  agent,
  fresh,
  onOpenConversation,
}: {
  agent: AgentResponse
  /** Still washing teal after an optimistic add. */
  fresh: boolean
  onOpenConversation(agentId: string): void
}) {
  return (
    <article className={fresh ? 'acard fresh' : 'acard'}>
      <div className="a-top">
        <span className="a-name">{agent.name}</span>
        <span className={statusChipClass(agent.status)}>{agent.status}</span>
        <span className="chip teal a-chip">
          {agent.model_provider} · {agent.model_name}
        </span>
      </div>
      <div className="a-ids">
        <span>id {shortId(agent.id)}</span>
        <CopyId value={agent.id} />
        <span>created {formatUtc(agent.created_at)}</span>
      </div>
      {/* Whatever the server returned — the defaults it applied included. */}
      <div className="a-params">
        <span>
          <span className="k">temp</span> {agent.temperature}
        </span>
        <span>
          <span className="k">max</span> {agent.max_response_tokens}
        </span>
      </div>
      <p className="a-prompt">{agent.system_prompt}</p>
      <div className="a-foot">
        <button className="a-open" type="button" onClick={() => onOpenConversation(agent.id)}>
          Open conversation
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" aria-hidden="true">
            <path d="M5 12h13M13 6l6 6-6 6" />
          </svg>
        </button>
        <span className="a-gap">no edit · no delete</span>
      </div>
    </article>
  )
}

/**
 * Status verbatim from the wire: ACTIVE moss, DRAFT stone, and anything
 * else neutral — a value this build does not know gets no invented colour.
 */
function statusChipClass(status: AgentStatus): string {
  return status === 'ACTIVE' ? 'chip moss' : 'chip'
}
