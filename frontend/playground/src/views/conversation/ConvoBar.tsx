import type { AgentResponse, ConversationResponse } from '../../api/types'
import type { AgentsState } from './useAgents'
import { shortId } from './useConversationMachine'

/**
 * The context bar: tenant (an addition to the mockup — prefilled from the
 * key's own tenant via /v1/me, still editable to browse child tenants),
 * agent selector, identity ref, and the conversation chips. All inputs are
 * read at send time and disabled while an invocation is in flight.
 */
export function ConvoBar({
  tenantId,
  onTenantIdChange,
  onLoadAgents,
  agents,
  selectedAgentId,
  onSelectAgent,
  identityRef,
  onIdentityRefChange,
  conversation,
  disabled,
}: {
  tenantId: string
  onTenantIdChange(value: string): void
  onLoadAgents(): void
  agents: AgentsState
  selectedAgentId: string
  onSelectAgent(agentId: string): void
  identityRef: string
  onIdentityRefChange(value: string): void
  conversation: ConversationResponse | null
  disabled: boolean
}) {
  const loading = agents.status === 'loading'
  return (
    <div className="convo-bar">
      <div className="convo-field tenant-field">
        <label htmlFor="convo-tenant">Tenant</label>
        <div className="field-row">
          <input
            className="ident-input"
            id="convo-tenant"
            type="text"
            placeholder="tenant id that owns the agents (prefilled from your key)"
            value={tenantId}
            onChange={(event) => onTenantIdChange(event.target.value)}
            spellCheck={false}
            disabled={disabled}
          />
          <button
            className="bar-btn"
            type="button"
            onClick={onLoadAgents}
            disabled={disabled || loading || tenantId.trim() === ''}
          >
            {loading ? 'Loading…' : 'Load agents'}
          </button>
        </div>
        {agents.status === 'error' && <span className="bar-note">{agents.error}</span>}
        {agents.status === 'loaded' && agents.agents.length === 0 && (
          <span className="bar-note">
            no agents visible for this tenant id (a wrong id and an empty tenant look the same)
          </span>
        )}
      </div>

      <div className="convo-field agent-field">
        <label htmlFor="convo-agent">Agent</label>
        <div className="select-wrap">
          <select
            className="select-like"
            id="convo-agent"
            value={selectedAgentId}
            onChange={(event) => onSelectAgent(event.target.value)}
            disabled={disabled || agents.agents.length === 0}
          >
            <option value="" disabled>
              {agents.agents.length === 0 ? 'load agents first' : 'select an agent…'}
            </option>
            {agents.agents.map((agent: AgentResponse) => (
              <option key={agent.id} value={agent.id}>
                {agentOptionLabel(agent, agents.agents)}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="convo-field">
        <label htmlFor="convo-ident">Identity ref</label>
        <input
          className="ident-input"
          id="convo-ident"
          type="text"
          value={identityRef}
          onChange={(event) => onIdentityRefChange(event.target.value)}
          spellCheck={false}
          disabled={disabled}
        />
      </div>

      <div className="convo-meta">
        {conversation !== null && (
          <>
            <span className="chip mono-id">conv {shortId(conversation.id)}</span>
            <span className={conversation.status === 'OPEN' ? 'chip open' : 'chip'}>
              {conversation.status}
            </span>
            <span className="chip">channel {conversation.channel_type}</span>
          </>
        )}
      </div>
    </div>
  )
}

/**
 * Option text built for disambiguation — the same criterion as the Agents
 * card: names are not unique, so the model chip always shows, and the short
 * id is appended only when the name is actually duplicated in the list.
 */
function agentOptionLabel(agent: AgentResponse, all: AgentResponse[]): string {
  const base = `${agent.name} · ${agent.model_provider} · ${agent.model_name} · ${agent.status}`
  const nameIsDuplicated = all.some((other) => other.id !== agent.id && other.name === agent.name)
  return nameIsDuplicated ? `${base} · ${shortId(agent.id)}` : base
}
