import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import type { ApiClient } from '../../api/client'
import type { CreateAgentBody, TenantResponse } from '../../api/types'
import { useSession } from '../../session/SessionContext'
import { AgentCard } from './AgentCard'
import { AgentCreatePanel } from './AgentCreatePanel'
import { useAgentList } from './useAgentList'

export function AgentsView() {
  const { client } = useSession()
  // RequireSession guarantees a connected session; this guard is for the
  // type system (and the disconnect unmount race).
  if (client === null) return null
  return <ConnectedAgents client={client} />
}

function ConnectedAgents({ client }: { client: ApiClient }) {
  const { tenantId, setTenantId } = useSession()
  const navigate = useNavigate()
  const agents = useAgentList(client)

  const [tenantInput, setTenantInput] = useState(tenantId)
  const [loadedTenantId, setLoadedTenantId] = useState('')
  const [tenantMeta, setTenantMeta] = useState<TenantResponse | null>(null)

  async function load(rawId: string) {
    const id = rawId.trim()
    if (id === '') return
    setTenantId(id) // share the focus with Conversation
    setLoadedTenantId(id)
    setTenantMeta(null)
    void agents.load(id)
    // Best-effort context chip (name · tier). Absent when the tenant is not
    // visible — listing agents for it still returns an empty list, not 404.
    try {
      setTenantMeta(await client.getTenant(id))
    } catch {
      setTenantMeta(null)
    }
  }

  // Prefill + auto-load when arriving from Tenants ("agents →" sets the
  // session tenant). Idempotent GET — safe under StrictMode's double mount.
  const loadRef = useRef(load)
  loadRef.current = load
  useEffect(() => {
    if (tenantId.trim() !== '') void loadRef.current(tenantId.trim())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function openConversation(agentId: string) {
    void navigate('/conversation', { state: { agentId } })
  }

  async function createAgent(body: CreateAgentBody) {
    const created = await client.createAgent(loadedTenantId, body)
    agents.add(created)
  }

  return (
    <div className="workspace">
      <div className="page-eyebrow">Playground · Workspace</div>
      <h1 className="page-title">Agents</h1>
      <p className="page-sub">
        Agents belong to a CLIENT tenant. Names aren't unique — the id and the model chip tell them
        apart.
      </p>

      <div className="load-row">
        <span className="lab">Client tenant id</span>
        <input
          className="mono-input"
          type="text"
          placeholder="a client tenant id (or arrive via Tenants → agents)"
          value={tenantInput}
          onChange={(event) => setTenantInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') void load(tenantInput)
          }}
          spellCheck={false}
        />
        <button
          className="btn-ghost"
          type="button"
          onClick={() => void load(tenantInput)}
          disabled={agents.status === 'loading' || tenantInput.trim() === ''}
        >
          {agents.status === 'loading' ? 'Loading…' : 'Load agents'}
        </button>
        {tenantMeta !== null && (
          <span className="tenant-ctx">
            → <span className="name">{tenantMeta.name}</span> · {tenantMeta.tier}
          </span>
        )}
      </div>

      <div className="cols">
        <div className="agent-list">
          {agents.status === 'error' && <div className="list-empty">{agents.error}</div>}
          {agents.status === 'loading' && <div className="list-empty">Loading agents…</div>}
          {agents.status === 'loaded' && agents.agents.length === 0 && (
            <div className="list-empty">
              No agents visible for this tenant id. A wrong id and an empty tenant look the same —
              the API returns 200 with an empty list either way.
            </div>
          )}
          {agents.agents.map((agent) => (
            <AgentCard key={agent.id} agent={agent} onOpenConversation={openConversation} />
          ))}
          {agents.nextCursor !== null && (
            <button
              className="load-more"
              type="button"
              onClick={() => void agents.loadMore()}
              disabled={agents.loadingMore}
            >
              {agents.loadingMore ? 'Loading…' : 'Load more'}
            </button>
          )}
        </div>

        {loadedTenantId === '' ? (
          <div className="panel">
            <p className="panel-empty">Load a client tenant to create agents under it.</p>
          </div>
        ) : (
          <AgentCreatePanel
            tenantName={tenantMeta?.name ?? 'this tenant'}
            existingAgents={agents.agents}
            onCreate={createAgent}
          />
        )}
      </div>
    </div>
  )
}
