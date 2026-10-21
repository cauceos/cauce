import type {
  AgendaResponse,
  BusinessProfileResponse,
  CatalogAttribute,
  CatalogResponse,
} from '../../api/types'

/** How the agents read the profile: the facts, and nothing else is known. */
export function ProfilePreview({ profile }: { profile: BusinessProfileResponse }) {
  const facts = Object.entries(profile.facts)
  return (
    <>
      <h3>{profile.name}</h3>
      <p className="why">
        <code>{profile.time_zone}</code> · <code>{profile.locale}</code> — what is not a fact here, the agent
        says it does not know.
      </p>
      <div className="biz-sec">Facts</div>
      {facts.length === 0 ? (
        <p className="biz-empty">No facts yet.</p>
      ) : (
        <div className="biz-kv">
          {facts.map(([key, value]) => (
            <Fact key={key} k={key} v={value} />
          ))}
        </div>
      )}
      <div className="biz-sec">Cases handed to the owner as</div>
      <p className="biz-empty">
        {profile.handoff_categories.length === 0 ? 'any category' : profile.handoff_categories.join(' · ')}
      </p>
      <div className="biz-sec">Nightly summary</div>
      <p className="biz-empty">
        {profile.daily_digest_time === null
          ? 'off'
          : `at ${profile.daily_digest_time.slice(0, 5)} local${profile.daily_digest_skip_when_empty ? ', empty days skipped' : ''}`}
      </p>
    </>
  )
}

function Fact({ k, v }: { k: string; v: string }) {
  return (
    <>
      <div className="k">{k}</div>
      <div className="v">{v}</div>
    </>
  )
}

/**
 * How the agents read the catalog: by section, with every declared attribute
 * either stated or marked “not declared” — never shown as empty.
 */
export function CatalogPreview({ catalog }: { catalog: CatalogResponse }) {
  const sections = [...new Set(catalog.items.map((item) => item.section))]
  return (
    <>
      <h3>Catalog</h3>
      <p className="why">
        {catalog.items.length} items · declared attributes:{' '}
        {catalog.declared_attributes.length === 0 ? 'none' : catalog.declared_attributes.join(', ')}
      </p>
      {sections.map((section) => (
        <div key={section}>
          <div className="biz-sec">{section}</div>
          <div className="biz-list">
            {catalog.items
              .filter((item) => item.section === section)
              .map((item) => (
                <div className="biz-row" key={item.ref}>
                  <div className="top">
                    <span className="name">{item.name}</span>
                    {item.price !== null && <span className="price">{item.price.toFixed(2)}</span>}
                  </div>
                  <div className="chips">
                    {catalog.declared_attributes.map((name) =>
                      name in item.attributes ? (
                        <span className="chip moss" key={name}>
                          {name}: {formatAttribute(item.attributes[name])}
                        </span>
                      ) : (
                        <span className="chip slate" key={name}>
                          ? {name}: not declared
                        </span>
                      ),
                    )}
                  </div>
                </div>
              ))}
          </div>
        </div>
      ))}
    </>
  )
}

function formatAttribute(value: CatalogAttribute | undefined): string {
  if (Array.isArray(value)) return value.length === 0 ? 'none' : value.join(', ')
  return String(value)
}

/** How the agents read the agenda: the shifts and their limits. */
export function AgendaPreview({ agenda }: { agenda: AgendaResponse }) {
  return (
    <>
      <h3>Agenda</h3>
      <p className="why">
        Up to {agenda.max_party_size} {agenda.unit} per booking · a proposal holds {agenda.hold_minutes} min ·
        bookable {agenda.horizon_days} days ahead{agenda.require_phone ? ' · phone required' : ''}
      </p>
      <div className="biz-sec">Shifts</div>
      <div className="biz-list">
        {agenda.shifts.map((shift) => (
          <div className="biz-row" key={shift.id}>
            <div className="top">
              <span className="name">{shift.id}</span>
              <span className="price">
                {shift.start}–{shift.end}
              </span>
            </div>
            <div className="meta">
              {shift.days.join(' ')} · {shift.capacity} {agenda.unit}
            </div>
          </div>
        ))}
      </div>
      <div className="biz-sec">Closed</div>
      <p className="biz-empty">{agenda.closed_dates.length === 0 ? 'no dates' : agenda.closed_dates.join(' · ')}</p>
    </>
  )
}

/** Starting points for a business that has loaded nothing yet: the shapes the API takes. */
export const TEMPLATES = {
  profile: JSON.stringify(
    {
      name: 'Business name',
      time_zone: 'Europe/Madrid',
      locale: 'es',
      facts: { opening_hours: '…', address: '…' },
      handoff_categories: ['group', 'event', 'complaint', 'other'],
      daily_digest_time: '22:30',
      daily_digest_skip_when_empty: false,
    },
    null,
    2,
  ),
  catalog: JSON.stringify(
    {
      declared_attributes: ['allergens'],
      items: [{ ref: 'item-1', section: 'Section', name: 'Item', price: 5.5, attributes: { allergens: [] } }],
    },
    null,
    2,
  ),
  agenda: JSON.stringify(
    {
      unit: 'covers',
      max_party_size: 8,
      hold_minutes: 10,
      horizon_days: 60,
      require_phone: true,
      shifts: [{ id: 'dinner', days: ['tue', 'wed', 'thu', 'fri', 'sat'], start: '20:30', end: '23:00', capacity: 50 }],
      closed_dates: [],
    },
    null,
    2,
  ),
}
