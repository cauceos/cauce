import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import type { ApiClient } from '../../api/client'
import type { AgentResponse, CreateAgentBody, TenantResponse } from '../../api/types'
import { BottomSheet } from '../../components/BottomSheet'
import { MOBILE_QUERY, useMediaQuery } from '../../lib/useMediaQuery'
import { useSession } from '../../session/SessionContext'
import { AgentCard } from './AgentCard'
import { AgentCreatePanel } from './AgentCreatePanel'
import { useAgentList } from './useAgentList'

/** How long the teal wash on a just-created card stays on. */
const FRESH_MS = 1600

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
  const isMobile = useMediaQuery(MOBILE_QUERY)

  const [tenantInput, setTenantInput] = useState(tenantId)
  const [loadedTenantId, setLoadedTenantId] = useState('')
  const [tenantMeta, setTenantMeta] = useState<TenantResponse | null>(null)
  // Below 700px the create panel is a bottom sheet behind a floating button.
  const [sheetOpen, setSheetOpen] = useState(false)
  const fabRef = useRef<HTMLButtonElement>(null)
  // The id whose card is still washing teal after an optimistic add.
  const [freshId, setFreshId] = useState<string | null>(null)
  const freshTimer = useRef<number | null>(null)
  useEffect(() => () => {
    if (freshTimer.current !== null) window.clearTimeout(freshTimer.current)
  }, [])

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

  async function createAgent(body: CreateAgentBody): Promise<AgentResponse> {
    const created = await client.createAgent(loadedTenantId, body)
    // Optimistic add of the 201 body: a fresh UUIDv7 sorts last and can land
    // on an unfetched page, so refetching would hide what was just made.
    agents.add(created)
    setFreshId(created.id)
    if (freshTimer.current !== null) window.clearTimeout(freshTimer.current)
    freshTimer.current = window.setTimeout(() => setFreshId(null), FRESH_MS)
    if (isMobile) setSheetOpen(false)
    return created
  }

  const create =
    loadedTenantId === '' ? (
      <p className="panel-empty">Load a client tenant to create agents under it.</p>
    ) : (
      <AgentCreatePanel
        tenantName={tenantMeta?.name ?? 'this tenant'}
        existingAgents={agents.agents}
        onCreate={createAgent}
      />
    )

  return (
    <div className="workspace">
      <div className="page-head">
        <div className="eyebrow">
          <span className="rule" />
          Playground · Workspace
        </div>
        <h1>Agents</h1>
        <p className="page-sub">
          Agents belong to a <b>CLIENT</b> tenant. Names are not unique — the provider · model chip
          and the id tell them apart.
        </p>
      </div>

      <div className="rootrow">
        <div className="f">
          <label htmlFor="agents-tenant">
            Client tenant id <span className="tag">· from your key</span>
          </label>
          <input
            className="input"
            id="agents-tenant"
            type="text"
            placeholder="a client tenant id (or arrive via Tenants → agents)"
            value={tenantInput}
            onChange={(event) => setTenantInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void load(tenantInput)
            }}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
          />
        </div>
        <button
          className="btn"
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
        <div>
          <div className="cards">
            {agents.status === 'error' && <div className="list-empty">{agents.error}</div>}
            {agents.status === 'loading' && (
              <div className="list-empty" aria-label="Loading agents">
                <div className="tree-skel">
                  <div className="skel" style={{ width: '58%' }} />
                  <div className="skel" style={{ width: '82%' }} />
                  <div className="skel" style={{ width: '44%' }} />
                </div>
              </div>
            )}
            {agents.status === 'loaded' && agents.agents.length === 0 && (
              <div className="list-empty">
                No agents visible for this tenant id. A wrong id and an empty tenant look the same
                — the API returns 200 with an empty list either way.
              </div>
            )}
            {agents.agents.map((agent) => (
              <AgentCard
                key={agent.id}
                agent={agent}
                fresh={freshId === agent.id}
                onOpenConversation={openConversation}
              />
            ))}
          </div>
          {/* Keyset, page by page — a full walk is the tree's job, not this list's. */}
          {agents.nextCursor !== null && (
            <div className="loadmore">
              <button
                className="btn-ghost mono"
                type="button"
                onClick={() => void agents.loadMore()}
                disabled={agents.loadingMore}
              >
                {agents.loadingMore ? 'Loading…' : 'Load more'}
              </button>
            </div>
          )}
        </div>

        <aside className="panel">{create}</aside>
      </div>

      {isMobile && (
        <>
          <button
            ref={fabRef}
            className="fab"
            type="button"
            onClick={() => setSheetOpen(true)}
            disabled={loadedTenantId === ''}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
            New agent
          </button>
          <BottomSheet
            open={sheetOpen}
            onClose={() => setSheetOpen(false)}
            label="New agent"
            returnFocusTo={fabRef}
          >
            {create}
          </BottomSheet>
        </>
      )}
    </div>
  )
}
