import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router'
import type { ApiClient } from '../../api/client'
import { useSession } from '../../session/SessionContext'
import { Composer } from './Composer'
import { ConvoBar } from './ConvoBar'
import { Thread } from './Thread'
import { useAgents } from './useAgents'
import { shortId, useConversationMachine } from './useConversationMachine'

export function ConversationView() {
  const { client } = useSession()
  // RequireSession guarantees a connected session; this guard is for the
  // type system (and the disconnect unmount race).
  if (client === null) return null
  return <ConnectedConversation client={client} />
}

const DEFAULT_IDENTITY_REF = 'playground-user-1'

function ConnectedConversation({ client }: { client: ApiClient }) {
  const { tenantId, setTenantId } = useSession()
  const location = useLocation()
  const agents = useAgents(client)
  const { state, send, resume, loadEarlier } = useConversationMachine(client)

  const [selectedAgentId, setSelectedAgentId] = useState('')
  const [identityRef, setIdentityRef] = useState(DEFAULT_IDENTITY_REF)

  // If a workspace tenant already sits in the session (set here earlier, or
  // by the Agents/Tenants areas), load its agents on mount. Idempotent GET —
  // safe under StrictMode's double mount (useAgents aborts the first).
  const loadRef = useRef(agents.load)
  loadRef.current = agents.load
  useEffect(() => {
    if (tenantId.trim() !== '') void loadRef.current(tenantId.trim())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Preselect the agent handed over by "Open conversation →" (Agents area),
  // once its list has loaded and contains the id. Consumed once so a manual
  // reselect afterwards is not overridden.
  const preselectId = (location.state as { agentId?: string } | null)?.agentId
  const preselectDone = useRef(false)
  useEffect(() => {
    if (preselectDone.current || preselectId == null) return
    if (agents.agents.some((agent) => agent.id === preselectId)) {
      setSelectedAgentId(preselectId)
      preselectDone.current = true
    }
  }, [preselectId, agents.agents])

  const busy = state.phase !== 'idle'
  const selectedAgent = agents.agents.find((agent) => agent.id === selectedAgentId) ?? null
  // Who-line resolution: the conversation names its agent by id; the loaded
  // list resolves it to a name, an unknown id falls back truncated.
  const threadAgentName =
    state.conversation !== null
      ? (agents.agents.find((agent) => agent.id === state.conversation?.agent_id)?.name ??
        shortId(state.conversation.agent_id))
      : (selectedAgent?.name ?? 'agent')
  const threadIdentityRef = state.conversation?.external_identity_ref ?? identityRef

  return (
    <div className="convo-screen">
      <ConvoBar
        tenantId={tenantId}
        onTenantIdChange={setTenantId}
        onLoadAgents={() => void agents.load(tenantId.trim())}
        agents={agents}
        selectedAgentId={selectedAgentId}
        onSelectAgent={setSelectedAgentId}
        identityRef={identityRef}
        onIdentityRefChange={setIdentityRef}
        conversation={state.conversation}
        disabled={busy}
      />

      <Thread
        items={state.visibleItems}
        hiddenMessageCount={state.hiddenMessageCount}
        identityRef={threadIdentityRef}
        agentName={threadAgentName}
        live={state.live}
        stopped={state.phase === 'stopped'}
        stopReason={state.stopReason}
        fatalNote={state.fatalNote}
        hasConversation={state.conversation !== null}
        onLoadEarlier={loadEarlier}
        onResume={resume}
      />

      <Composer
        agentName={selectedAgent?.name ?? null}
        canSend={!busy && selectedAgent !== null && identityRef.trim() !== ''}
        sendError={state.sendError}
        wireLeft={state.wireLeft}
        wireRight={state.wireRight}
        onSend={(content) => send(selectedAgentId, identityRef.trim(), content)}
      />
    </div>
  )
}
