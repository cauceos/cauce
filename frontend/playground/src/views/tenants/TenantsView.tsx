import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import type { ApiClient } from '../../api/client'
import type { TenantResponse } from '../../api/types'
import { BottomSheet } from '../../components/BottomSheet'
import { MOBILE_QUERY, useMediaQuery } from '../../lib/useMediaQuery'
import { useSession } from '../../session/SessionContext'
import { TenantDetailPanel } from './TenantDetailPanel'
import { TenantTree } from './TenantTree'
import { useTenantTree } from './useTenantTree'
import type { TreeNode } from './useTenantTree'

/** How long the teal wash on a just-created row stays on. */
const FRESH_MS = 1600

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
  const isMobile = useMediaQuery(MOBILE_QUERY)
  // Prefilled with the session's workspace tenant (the key's own tenant
  // after connect, or whatever the user set elsewhere) — still editable to
  // walk from any other visible root.
  const [rootInput, setRootInput] = useState(tenantId)
  // Below 700px the detail panel is a bottom sheet, opened by tapping a row.
  const [sheetOpen, setSheetOpen] = useState(false)
  // The id whose row is still washing teal after an optimistic insert.
  const [freshId, setFreshId] = useState<string | null>(null)
  const freshTimer = useRef<number | null>(null)
  useEffect(() => () => {
    if (freshTimer.current !== null) window.clearTimeout(freshTimer.current)
  }, [])

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

  function select(id: string) {
    tree.select(id)
    if (isMobile) setSheetOpen(true)
  }

  function openAgents(clientId: string) {
    setTenantId(clientId)
    void navigate('/agents')
  }

  async function createChild(name: string): Promise<TenantResponse> {
    if (selected === null) throw new Error('no tenant selected')
    const parentId = selected.tenant.id
    const created =
      selected.tenant.tier === 'OPERATOR'
        ? await client.createPartner({ name, operator_id: parentId })
        : await client.createClient({ name, partner_id: parentId })
    // The 201 body goes into the parent's loaded children: a fresh UUIDv7
    // sorts last and can land on an unfetched page, so a blind refetch
    // would make the new tenant vanish.
    tree.insertChild(parentId, created)
    setFreshId(created.id)
    if (freshTimer.current !== null) window.clearTimeout(freshTimer.current)
    freshTimer.current = window.setTimeout(() => setFreshId(null), FRESH_MS)
    // On a phone the sheet closes so the wash is seen where it happened.
    if (isMobile) setSheetOpen(false)
    return created
  }

  const detail =
    selected === null ? (
      <p className="panel-empty">Select a tenant to see its detail and create under it.</p>
    ) : (
      <TenantDetailPanel selected={selected} parent={selectedParent} onCreate={createChild} />
    )

  return (
    <div className="workspace">
      <div className="page-head">
        <div className="eyebrow">
          <span className="rule" />
          Playground · Workspace
        </div>
        <h1>Tenants</h1>
        <p className="page-sub">
          The three-tier hierarchy: <b>operator → partner → client</b>. Agents live only under
          clients. Discovery walks children from a known root — there is no global listing.
        </p>
      </div>

      <div className="rootrow">
        <div className="f">
          <label htmlFor="tenants-root">
            Root tenant id <span className="tag">· from your key</span>
          </label>
          <input
            className="input"
            id="tenants-root"
            type="text"
            placeholder="an operator or partner tenant id"
            value={rootInput}
            onChange={(event) => setRootInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void tree.loadRoot(rootInput)
            }}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
          />
        </div>
        <button
          className="btn"
          type="button"
          onClick={() => void tree.loadRoot(rootInput)}
          disabled={tree.rootStatus === 'loading' || rootInput.trim() === ''}
        >
          {tree.rootStatus === 'loading' ? 'Loading…' : 'Load tree'}
        </button>
        {tree.rootStatus === 'error' && (
          <span className="note">
            {/* Both a wrong id and one outside the key's scope answer 404 —
                the API never confirms existence beyond your visibility
                (anti-enumeration). The copy owns that instead of pretending
                to know more. */}
            {isNotFound(tree.rootError) ? (
              <>
                No tenant is visible at that id.
                <br />
                <span className="d">
                  A wrong id and one outside your key&apos;s scope look the same on purpose.
                </span>
              </>
            ) : (
              tree.rootError
            )}
          </span>
        )}
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
            freshId={freshId}
            onSelect={select}
            onToggle={tree.toggle}
            onLoadMore={tree.loadMoreChildren}
            onOpenAgents={openAgents}
          />
        )}

        <aside className="panel">{detail}</aside>
      </div>

      {isMobile && (
        <BottomSheet open={sheetOpen} onClose={() => setSheetOpen(false)} label="Tenant detail">
          {detail}
        </BottomSheet>
      )}
    </div>
  )
}

/** The tree hook words a 404 as "no tenant visible…"; that is the case the copy owns. */
function isNotFound(error: string | null): boolean {
  return error !== null && /no tenant visible/i.test(error)
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
