import { CopyId } from '../../components/CopyId'
import { formatUtc, shortId } from '../../lib/format'
import type { AgentResponse } from '../../api/types'

/**
 * One agent, built for disambiguation: names are not unique, so the model
 * chip + truncated id + created timestamp are always shown. There is no
 * edit or delete endpoint, so there are no such buttons — the absence is
 * stated, not hidden.
 */
export function AgentCard({
  agent,
  onOpenConversation,
}: {
  agent: AgentResponse
  onOpenConversation(agentId: string): void
}) {
  return (
    <div className="agent-card">
      <div className="agent-head">
        <span className="agent-name">{agent.name}</span>
        <span className="badge">{agent.status}</span>
        <span className="model-chip">
          {agent.model_provider} · {agent.model_name}
        </span>
      </div>
      <div className="agent-meta">
        <span>
          id {shortId(agent.id)} <CopyId value={agent.id} />
        </span>
        <span>created {formatUtc(agent.created_at)}</span>
        <span>
          temp {agent.temperature} · max {agent.max_response_tokens}
        </span>
      </div>
      <div className="agent-prompt">{agent.system_prompt}</div>
      <div className="agent-actions">
        <button className="btn-open" type="button" onClick={() => onOpenConversation(agent.id)}>
          Open conversation →
        </button>
        <span className="no-actions-note">no edit/delete — the API has no update endpoint yet</span>
      </div>
    </div>
  )
}
