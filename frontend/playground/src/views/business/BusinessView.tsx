import { useState } from 'react'
import type { ApiClient } from '../../api/client'
import type { AgendaResponse, BusinessProfileResponse, CatalogResponse } from '../../api/types'
import { useSession } from '../../session/SessionContext'
import { DocumentEditor } from './DocumentEditor'
import { CasesArea, OwnerArea } from './OwnerArea'
import { AgendaPreview, CatalogPreview, ProfilePreview, TEMPLATES } from './previews'

type Tab = 'profile' | 'catalog' | 'agenda' | 'cases' | 'owner'

const TABS: { id: Tab; label: string }[] = [
  { id: 'profile', label: 'Profile' },
  { id: 'catalog', label: 'Catalog' },
  { id: 'agenda', label: 'Agenda' },
  { id: 'cases', label: 'Cases & bookings' },
  { id: 'owner', label: 'Owner' },
]

export function BusinessView() {
  const { client } = useSession()
  if (client === null) return null
  return <ConnectedBusiness client={client} />
}

function ConnectedBusiness({ client }: { client: ApiClient }) {
  const { tenantId, setTenantId } = useSession()
  const [tenantInput, setTenantInput] = useState(tenantId)
  const [loaded, setLoaded] = useState(tenantId.trim())
  const [tab, setTab] = useState<Tab>('profile')

  function load() {
    const id = tenantInput.trim()
    if (id === '') return
    setTenantId(id)
    setLoaded(id)
  }

  return (
    <div className="workspace business">
      <div className="page-head">
        <div className="eyebrow">
          <span className="rule" />
          Playground · Workspace
        </div>
        <h1>Business</h1>
        <p className="page-sub">
          What a business tells its agents — the profile, the catalog and the agenda, each loaded and replaced
          whole — and what reaches its owner. The sector lives in this data, not in code.
        </p>
      </div>

      <div className="action-row">
        <div className="f">
          <label htmlFor="business-tenant">Client tenant id</label>
          <input
            className="input"
            id="business-tenant"
            type="text"
            placeholder="the business: a client tenant id"
            value={tenantInput}
            onChange={(event) => setTenantInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') load()
            }}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
          />
        </div>
        <button className="btn" type="button" onClick={load} disabled={tenantInput.trim() === ''}>
          Load business
        </button>
      </div>

      {loaded === '' ? (
        <div className="biz-body">
          <p className="biz-empty">Load a client tenant to see what its agents know.</p>
        </div>
      ) : (
        <>
          <div className="biz-tabs" role="tablist" aria-label="Business areas">
            {TABS.map((t) => (
              <button
                key={t.id}
                className="biz-tab"
                type="button"
                role="tab"
                aria-selected={tab === t.id}
                onClick={() => setTab(t.id)}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div className="biz-body" role="tabpanel">
            {tab === 'profile' && (
              <DocumentEditor<BusinessProfileResponse>
                key={`profile:${loaded}`}
                client={client}
                tenantId={loaded}
                kind="business-profile"
                template={TEMPLATES.profile}
                preview={(profile) => <ProfilePreview profile={profile} />}
              />
            )}
            {tab === 'catalog' && (
              <DocumentEditor<CatalogResponse>
                key={`catalog:${loaded}`}
                client={client}
                tenantId={loaded}
                kind="catalog"
                template={TEMPLATES.catalog}
                preview={(catalog) => <CatalogPreview catalog={catalog} />}
              />
            )}
            {tab === 'agenda' && (
              <DocumentEditor<AgendaResponse>
                key={`agenda:${loaded}`}
                client={client}
                tenantId={loaded}
                kind="agenda"
                template={TEMPLATES.agenda}
                preview={(agenda) => <AgendaPreview agenda={agenda} />}
              />
            )}
            {tab === 'cases' && <CasesArea key={`cases:${loaded}`} client={client} tenantId={loaded} />}
            {tab === 'owner' && <OwnerArea key={`owner:${loaded}`} client={client} tenantId={loaded} />}
          </div>
        </>
      )}
    </div>
  )
}
