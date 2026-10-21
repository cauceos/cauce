import { useEffect, useState, type ReactNode } from 'react'
import type { ApiClient, BusinessDocumentKind } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { AgendaResponse, BusinessProfileResponse, CatalogResponse } from '../../api/types'
import { toFormError, type FormError } from '../../lib/formError'

type BusinessDocument = BusinessProfileResponse | CatalogResponse | AgendaResponse

/** The server-owned fields stripped before editing: they are not part of the PUT body. */
const SERVER_FIELDS = ['id', 'tenant_id', 'created_at', 'updated_at']

/**
 * One business document edited as JSON (the API loads and replaces it whole),
 * next to how it reads once stored. A document that does not exist yet starts
 * from the template; the server validates and says what is wrong.
 */
export function DocumentEditor<T extends BusinessDocument>({
  client,
  tenantId,
  kind,
  template,
  preview,
}: {
  client: ApiClient
  tenantId: string
  kind: BusinessDocumentKind
  template: string
  preview: (stored: T) => ReactNode
}) {
  const [text, setText] = useState('')
  const [stored, setStored] = useState<T | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'saving'>('loading')
  const [error, setError] = useState<FormError | null>(null)
  const [savedAt, setSavedAt] = useState<string | null>(null)

  useEffect(() => {
    const abort = new AbortController()
    setStatus('loading')
    setError(null)
    setSavedAt(null)
    client
      .getBusinessDocument<T>(tenantId, kind, abort.signal)
      .then((doc) => {
        setStored(doc)
        setText(editable(doc))
        setStatus('ready')
      })
      .catch((cause: unknown) => {
        if (abort.signal.aborted) return
        setStored(null)
        // Not loaded yet is the normal first state: start from the template.
        if (cause instanceof ApiError && cause.status === 404) {
          setText(template)
        } else {
          setError(toFormError(cause))
        }
        setStatus('ready')
      })
    return () => abort.abort()
  }, [client, tenantId, kind, template])

  async function save() {
    setStatus('saving')
    setError(null)
    setSavedAt(null)
    try {
      JSON.parse(text)
    } catch (cause) {
      setError({ fields: [], message: `not valid JSON — ${cause instanceof Error ? cause.message : ''}` })
      setStatus('ready')
      return
    }
    try {
      const doc = await client.putBusinessDocument<T>(tenantId, kind, text)
      setStored(doc)
      setText(editable(doc))
      setSavedAt(new Date().toLocaleTimeString())
    } catch (cause) {
      setError(toFormError(cause))
    }
    setStatus('ready')
  }

  return (
    <div className="biz-split">
      <div className="biz-editor">
        <textarea
          className="input"
          aria-label={`${kind} JSON`}
          value={text}
          onChange={(event) => setText(event.target.value)}
          spellCheck={false}
          disabled={status === 'loading'}
        />
        <div className="biz-actions">
          <button className="btn" type="button" onClick={() => void save()} disabled={status !== 'ready'}>
            {status === 'saving' ? 'Saving…' : 'Save'}
          </button>
          <button className="btn-ghost" type="button" onClick={() => setText(template)} disabled={status !== 'ready'}>
            Start from the template
          </button>
          {savedAt !== null && <span className="biz-saved">saved · {savedAt}</span>}
        </div>
        {error !== null && (
          <div className="error-note" role="alert">
            <div className="t">The server did not store it</div>
            {error.fields.map((field) => (
              <div className="f" key={`${field.field}:${field.message}`}>
                {field.field} · {field.message}
              </div>
            ))}
            {error.message !== null && <div className="d">{error.message}</div>}
          </div>
        )}
      </div>
      <aside className="panel">
        {stored === null ? (
          <p className="panel-empty">Nothing stored yet. Save the JSON to see how the agents read it.</p>
        ) : (
          preview(stored)
        )}
      </aside>
    </div>
  )
}

function editable(doc: BusinessDocument): string {
  const copy: Record<string, unknown> = { ...doc }
  for (const field of SERVER_FIELDS) delete copy[field]
  return JSON.stringify(copy, null, 2)
}
