import { useState } from 'react'
import type { TenantResponse } from '../../api/types'
import { CopyId } from '../../components/CopyId'
import { formatUtc, shortId } from '../../lib/format'
import { toFormError } from '../../lib/formError'
import type { FormError } from '../../lib/formError'
import { tierChipClass } from './TenantTree'
import type { TreeNode } from './useTenantTree'

/**
 * Detail of the selected tenant plus a contextual create form. Rendered
 * inside a `.panel` (desktop, tablet) or a bottom sheet (mobile) by the
 * view — this component is the content only.
 *
 * The child's tier and parent id are BOTH taken from the selected node —
 * the operator offers "New partner", a partner offers "New client", a
 * client offers neither (agents, not tenants, live under it). No UUID is
 * ever pasted.
 */
export function TenantDetailPanel({
  selected,
  parent,
  onCreate,
}: {
  selected: TreeNode
  parent: TreeNode | null
  /** Create a child of `selected` with the given name; throws on failure. */
  onCreate(name: string): Promise<TenantResponse>
}) {
  const { tenant } = selected
  const childKind = tenant.tier === 'OPERATOR' ? 'partner' : tenant.tier === 'PARTNER' ? 'client' : null

  return (
    <>
      <div className="p-name">{tenant.name}</div>
      <span className={tierChipClass(tenant.tier)}>{tenant.tier}</span>

      <div className="p-kv">
        <span className="k">Tenant id</span>
        <span className="v">
          {tenant.id} <CopyId value={tenant.id} />
        </span>
      </div>
      <div className="p-kv">
        <span className="k">Parent</span>
        <span className="v">
          {parent !== null ? (
            <>
              {parent.tenant.name} · {shortId(parent.tenant.id)} <CopyId value={parent.tenant.id} />
            </>
          ) : tenant.parent_tenant_id !== null ? (
            <>
              {shortId(tenant.parent_tenant_id)} <CopyId value={tenant.parent_tenant_id} />
            </>
          ) : (
            '— (hierarchy root)'
          )}
        </span>
      </div>
      <div className="p-kv">
        <span className="k">Created</span>
        <span className="v">{formatUtc(tenant.created_at)}</span>
      </div>

      <div className="p-sep" />

      {childKind === null ? (
        <p className="why">
          Clients are leaves: nothing nests under them. What lives here is agents — open them from
          this row in the tree (<code>agents →</code>).
        </p>
      ) : (
        <CreateChildForm
          key={tenant.id}
          childKind={childKind}
          parentName={tenant.name}
          onCreate={onCreate}
        />
      )}

      <div className="gap-note">No rename · no delete — the API doesn&apos;t expose them yet.</div>
    </>
  )
}

function CreateChildForm({
  childKind,
  parentName,
  onCreate,
}: {
  childKind: 'partner' | 'client'
  parentName: string
  onCreate(name: string): Promise<TenantResponse>
}) {
  const [name, setName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError | null>(null)

  const parentField = childKind === 'partner' ? 'operator_id' : 'partner_id'
  const nameRejected = error !== null && error.fields.some((f) => f.field === 'name')

  async function submit() {
    if (submitting || name.trim() === '') return
    setSubmitting(true)
    setError(null)
    try {
      await onCreate(name.trim())
      setName('')
    } catch (cause) {
      setError(toFormError(cause))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="p-form">
      <h3>
        New {childKind} under {parentName}
      </h3>
      <p className="why">
        The parent is taken from this node — <code>{parentField}</code> is filled for you. No UUIDs
        to paste.
      </p>

      {/* The envelope's errors[].field lands at the field, in slate. */}
      {error !== null && (error.fields.length > 0 || error.message !== null) && (
        <div className="error-note" role="alert">
          {error.fields.map((fieldError, index) => (
            <div key={`${fieldError.field}-${index}`}>
              <div className="f">{fieldError.field}</div>
              <div className="d">{fieldError.message}</div>
            </div>
          ))}
          {error.message !== null && <div className="d">{error.message}</div>}
        </div>
      )}

      <div className="field">
        <div className="f-top">
          <label htmlFor="tenant-name">Name</label>
        </div>
        <input
          className={nameRejected ? 'input err' : 'input'}
          id="tenant-name"
          type="text"
          placeholder="e.g. clinica-sonrisa"
          value={name}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              void submit()
            }
          }}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          disabled={submitting}
        />
      </div>
      <button
        className="btn"
        type="button"
        onClick={() => void submit()}
        disabled={submitting || name.trim() === ''}
      >
        {submitting ? 'Creating…' : `Create ${childKind}`}
      </button>

      <p className="p-note">
        Agents can only belong to a CLIENT tenant — create the client first, then add agents from
        its row.
      </p>
    </div>
  )
}
