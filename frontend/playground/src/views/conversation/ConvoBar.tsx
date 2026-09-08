import { useState } from 'react'
import type { AgentResponse, ConversationResponse } from '../../api/types'
import type { AgentsState } from './useAgents'
import { shortId } from './useConversationMachine'

/**
 * The context strip: which agent, on behalf of which identity, in which
 * tenant, and which conversation that resolved to.
 *
 * One DOM in three shapes (conversation.css): a single row on desktop, two
 * rows on tablet, and on mobile a scrolling chip row with tenant and
 * identity ref behind the Context disclosure — on a phone you switch agents
 * constantly and retype tenant UUIDs almost never.
 *
 * Everything here is read at send time and disabled while an invocation is
 * in flight.
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
  onNewConversation,
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
  onNewConversation(): void
  conversation: ConversationResponse | null
  disabled: boolean
}) {
  // Mobile only: the disclosure holding tenant + identity ref.
  const [extraOpen, setExtraOpen] = useState(false)
  const loading = agents.status === 'loading'
  // The identity ref has been changed away from the bound conversation, so
  // the next send will resolve (or create) a different one. Derived, not
  // announced by the server.
  const pendingNewConversation =
    conversation !== null && identityRef.trim() !== conversation.external_identity_ref

  return (
    <div className="ctx">
      <div className="ctx-main">
        <div className="ctx-f ctx-agent">
          <label htmlFor="convo-agent">Agent</label>
          <div className="select-wrap">
            <select
              className="ctx-select"
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

        <div className="ctx-f ctx-conv">
          <label>Conversation</label>
          <div className="ctx-chips">
            {conversation === null ? (
              <span className="chip quiet">none yet</span>
            ) : (
              <>
                <span className="chip quiet">conv {shortId(conversation.id)}</span>
                <span className={conversation.status === 'OPEN' ? 'chip ok' : 'chip'}>
                  {conversation.status}
                </span>
                <span className="chip quiet">channel {conversation.channel_type}</span>
              </>
            )}
          </div>
        </div>

        {/* Named for what it does, not for what it achieves: it writes a
            fresh identity ref. Calling it "New conversation" would hide the
            very mechanism this playground exists to teach — there is no
            create-conversation endpoint, the backend resolves or creates the
            OPEN one from the identity. */}
        <button
          className="btn-ghost ctx-new"
          type="button"
          onClick={onNewConversation}
          disabled={disabled}
        >
          New identity ref
        </button>

        <button
          className="btn-ghost ctx-toggle"
          type="button"
          aria-expanded={extraOpen}
          aria-controls="convo-context"
          onClick={() => setExtraOpen((open) => !open)}
        >
          Context
        </button>
      </div>

      <div className={extraOpen ? 'ctx-extra open' : 'ctx-extra'} id="convo-context">
        <div className="ctx-f ctx-tenant">
          <label htmlFor="convo-tenant">
            Tenant <span className="tag">· from your key</span>
          </label>
          <div className="ctx-row">
            <input
              className="input ctx-input"
              id="convo-tenant"
              type="text"
              placeholder="tenant id that owns the agents"
              value={tenantId}
              onChange={(event) => onTenantIdChange(event.target.value)}
              spellCheck={false}
              autoCapitalize="off"
              autoCorrect="off"
              disabled={disabled}
            />
            <button
              className="btn-ghost"
              type="button"
              onClick={onLoadAgents}
              disabled={disabled || loading || tenantId.trim() === ''}
            >
              {loading ? 'Loading…' : 'Load agents'}
            </button>
          </div>
        </div>

        <div className="ctx-f ctx-ident">
          <label htmlFor="convo-ident">Identity ref</label>
          <input
            className="input ctx-input"
            id="convo-ident"
            type="text"
            value={identityRef}
            onChange={(event) => onIdentityRefChange(event.target.value)}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
            disabled={disabled}
          />
        </div>

        <span className="ctx-note">
          A conversation opens by identity, not by button: the backend resolves or creates the
          OPEN one for this agent and identity ref. A new ref starts a new conversation on the
          next send.
        </span>

        {agents.status === 'error' && <span className="ctx-note">{agents.error}</span>}
        {agents.status === 'loaded' && agents.agents.length === 0 && (
          <span className="ctx-note">
            no agents visible for this tenant id (a wrong id and an empty tenant look the same)
          </span>
        )}
        {pendingNewConversation && (
          <span className="ctx-note">
            identity ref changed — the next send resolves a different conversation
          </span>
        )}
      </div>
    </div>
  )
}

/**
 * Option text built for disambiguation — the same criterion as the Agents
 * card: names are not unique, so provider/model/status always show, and the
 * short id is appended only when the name is actually duplicated in the
 * list. Talking to the wrong twin is how this rule was earned.
 */
function agentOptionLabel(agent: AgentResponse, all: AgentResponse[]): string {
  const base = `${agent.name} · ${agent.model_provider} · ${agent.model_name} · ${agent.status}`
  const nameIsDuplicated = all.some((other) => other.id !== agent.id && other.name === agent.name)
  return nameIsDuplicated ? `${base} · ${shortId(agent.id)}` : base
}
