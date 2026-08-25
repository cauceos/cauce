import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import type { ApiClient } from '../../api/client'
import { useSession } from '../../session/SessionContext'
import { TenantDetailPanel } from './TenantDetailPanel'
import { TenantTree } from './TenantTree'
import { useTenantTree } from './useTenantTree'
import type { TreeNode } from './useTenantTree'

export function TenantsView() {
  const { client } = useSession()
  // RequireSession guarantees a connected session; this guard is for the
  // type system (and the disconnect unmount race).
  if (client === null) return null
  return <ConnectedTenants client={client} />
}

function ConnectedTenants({ client }: { client: ApiClient }) {
  const { tenantId, setTenantId } = useSession()
  const navigate = useNavigate()
  const tree = useTenantTree(client)
  // Prefilled with the session's workspace tenant (the key's own tenant
  // after connect, or whatever the user set elsewhere) — still editable to
  // walk from any other visible root.
  const [rootInput, setRootInput] = useState(tenantId)

  // Auto-load the tree on mount when a root is already known — same
  // precedent as the Agents area. Idempotent GETs, safe under StrictMode's
  // double mount (useTenantTree aborts the first).
  const loadRootRef = useRef(tree.loadRoot)
  loadRootRef.current = tree.loadRoot
  useEffect(() => {
    if (tenantId.trim() !== '') void loadRootRef.current(tenantId.trim())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const selected = tree.selectedId !== null ? findNode(tree.root, tree.selectedId) : null
  const selectedParent =
    selected !== null && tree.selectedId !== null ? findParent(tree.root, tree.selectedId) : null

  function openAgents(clientId: string) {
    setTenantId(clientId)
    void navigate('/agents')
  }

  async function createChild(name: string) {
    if (selected === null) return
    const parentId = selected.tenant.id
    const created =
      selected.tenant.tier === 'OPERATOR'
        ? await client.createPartner({ name, operator_id: parentId })
        : await client.createClient({ name, partner_id: parentId })
    tree.insertChild(parentId, created)
  }

  return (
    <div className="workspace">
      <div className="page-eyebrow">Playground · Workspace</div>
      <h1 className="page-title">Tenants</h1>
      <p className="page-sub">
        The three-tier hierarchy: operator → partner → client. Agents live only under clients.
        Discovery walks children from a known root — there is no global listing.
      </p>

      <div className="load-row">
        <span className="lab">Root tenant id</span>
        <input
          className="mono-input"
          type="text"
          placeholder="paste an operator or partner tenant id"
          value={rootInput}
          onChange={(event) => setRootInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') void tree.loadRoot(rootInput)
          }}
          spellCheck={false}
        />
        <button
          className="btn-ghost"
          type="button"
          onClick={() => void tree.loadRoot(rootInput)}
          disabled={tree.rootStatus === 'loading' || rootInput.trim() === ''}
        >
          {tree.rootStatus === 'loading' ? 'Loading…' : 'Load tree'}
        </button>
        {tree.rootStatus === 'error' && <span className="load-note">{tree.rootError}</span>}
      </div>

      <div className="cols">
        {tree.root === null ? (
          <div className="tree">
            <div className="tree-empty">
              Load a root tenant id to walk its hierarchy. The root operator id is printed once in
              the API log at first startup (and in the quickstart state file).
            </div>
          </div>
        ) : (
          <TenantTree
            root={tree.root}
            selectedId={tree.selectedId}
            onSelect={tree.select}
            onToggle={tree.toggle}
            onLoadMore={tree.loadMoreChildren}
            onOpenAgents={openAgents}
          />
        )}

        {selected === null ? (
          <div className="panel">
            <p className="panel-empty">Select a tenant to see its detail and create under it.</p>
          </div>
        ) : (
          <TenantDetailPanel selected={selected} parent={selectedParent} onCreate={createChild} />
        )}
      </div>
    </div>
  )
}

function findNode(root: TreeNode | null, id: string): TreeNode | null {
  if (root === null) return null
  if (root.tenant.id === id) return root
  for (const child of root.children ?? []) {
    const found = findNode(child, id)
    if (found !== null) return found
  }
  return null
}

function findParent(root: TreeNode | null, id: string): TreeNode | null {
  if (root === null) return null
  for (const child of root.children ?? []) {
    if (child.tenant.id === id) return root
    const found = findParent(child, id)
    if (found !== null) return found
  }
  return null
}
