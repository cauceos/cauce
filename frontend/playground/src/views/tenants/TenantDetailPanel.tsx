import { useState } from 'react'
import { CopyId } from '../../components/CopyId'
import { formatUtc, shortId } from '../../lib/format'
import { toFormError } from '../../lib/formError'
import type { FormError } from '../../lib/formError'
import type { TreeNode } from './useTenantTree'

/**
 * Detail of the selected tenant plus a contextual create form. The child's
 * tier and parent id are BOTH taken from the selected node — the operator
 * offers "New partner", a partner offers "New client", a client offers
 * neither (agents, not tenants, live under it). No UUID is ever pasted.
 */
export function TenantDetailPanel({
  selected,
  parent,
  onCreate,
}: {
  selected: TreeNode
  parent: TreeNode | null
  /** Create a child of `selected` with the given name; throws on failure. */
  onCreate(name: string): Promise<void>
}) {
  const { tenant } = selected
  const childKind = tenant.tier === 'OPERATOR' ? 'partner' : tenant.tier === 'PARTNER' ? 'client' : null

  return (
    <div className="panel">
      <h3>{tenant.name}</h3>
      <div className="tier-line">
        <span className={`tier ${tenant.tier.toLowerCase()}`}>{tenant.tier}</span>
      </div>

      <div className="kv">
        <span className="k">Tenant id</span>
        <span className="v">
          {tenant.id} <CopyId value={tenant.id} />
        </span>
      </div>
      <div className="kv">
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
      <div className="kv">
        <span className="k">Created</span>
        <span className="v">{formatUtc(tenant.created_at)}</span>
      </div>

      <hr />

      {childKind === null ? (
        <p className="rule-note">
          Agents belong to a CLIENT tenant. Open this client's agents from its row in the tree
          (“agents →”). Tenants have no update or delete endpoint, so this client cannot be renamed
          or removed here.
        </p>
      ) : (
        <CreateChildForm
          key={tenant.id}
          childKind={childKind}
          parentName={tenant.name}
          onCreate={onCreate}
        />
      )}
    </div>
  )
}

function CreateChildForm({
  childKind,
  parentName,
  onCreate,
}: {
  childKind: 'partner' | 'client'
  parentName: string
  onCreate(name: string): Promise<void>
}) {
  const [name, setName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError | null>(null)

  const parentField = childKind === 'partner' ? 'operator_id' : 'partner_id'

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
    <>
      <div className="create-title">
        New {childKind} under {parentName}
      </div>
      <div className="create-sub">
        The parent is taken from this node — <code>{parentField}</code> is filled for you. No UUIDs
        to paste.
      </div>

      {error !== null && (error.fields.length > 0 || error.message !== null) && (
        <div className="form-error">
          {error.fields.map((fieldError, index) => (
            <div key={`${fieldError.field}-${index}`}>
              <span className="err-field">{fieldError.field}</span> · {fieldError.message}
            </div>
          ))}
          {error.message !== null && <div>{error.message}</div>}
        </div>
      )}

      <div className="field">
        <label htmlFor="tenant-name">Name</label>
        <input
          className="input"
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
          disabled={submitting}
        />
      </div>
      <button
        className="btn-primary"
        type="button"
        onClick={() => void submit()}
        disabled={submitting || name.trim() === ''}
      >
        {submitting ? 'Creating…' : `Create ${childKind}`}
      </button>

      <p className="rule-note">
        Agents can only belong to a CLIENT tenant — create the client first, then add agents from
        its row.
      </p>
    </>
  )
}
