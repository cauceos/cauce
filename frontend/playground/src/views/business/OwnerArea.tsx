import { useCallback, useEffect, useState } from 'react'
import type { ApiClient } from '../../api/client'
import { ApiError } from '../../api/errors'
import type {
  BookingResponse,
  DailyDigestResponse,
  HandoffResponse,
  OwnerLinkCodeResponse,
  OwnerNotificationResponse,
  OwnerResponse,
  UnansweredQuestionResponse,
} from '../../api/types'
import { toFormError } from '../../lib/formError'
import { formatUtc } from '../../lib/format'

/** What the agents left for the owner: the cases, the bookings, the questions nobody could answer. */
export function CasesArea({ client, tenantId }: { client: ApiClient; tenantId: string }) {
  const [handoffs, setHandoffs] = useState<HandoffResponse[]>([])
  const [questions, setQuestions] = useState<UnansweredQuestionResponse[]>([])
  const [bookings, setBookings] = useState<BookingResponse[]>([])
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const [h, q, b] = await Promise.all([
        client.listHandoffs(tenantId),
        client.listUnansweredQuestions(tenantId),
        client.listBookings(tenantId),
      ])
      setHandoffs(h.data)
      setQuestions(q.data)
      setBookings(b.data.filter((booking) => booking.status === 'CONFIRMED'))
    } catch (cause) {
      setError(toFormError(cause).message)
    }
  }, [client, tenantId])

  useEffect(() => {
    void load()
  }, [load])

  async function resolve(id: string) {
    try {
      await client.resolveHandoff(id)
      await load()
    } catch (cause) {
      setError(toFormError(cause).message)
    }
  }

  return (
    <div className="biz-cols3">
      <div>
        <div className="biz-sec">Cases handed to the owner</div>
        {error !== null && <div className="error-note"><div className="d">{error}</div></div>}
        <div className="biz-list">
          {handoffs.length === 0 && <p className="biz-empty">No cases.</p>}
          {handoffs.map((handoff) => (
            <div className="biz-row" key={handoff.id}>
              <div className="top">
                <span className="chip teal">{handoff.category}</span>
                <span className="name">{handoff.contact_name}</span>
                <span className={handoff.status === 'OPEN' ? 'chip' : 'chip moss'}>{handoff.status}</span>
              </div>
              <div className="meta">
                ref {handoff.code}
                {handoff.contact_phone !== null && ` · ${handoff.contact_phone}`} · {formatUtc(handoff.created_at)}
              </div>
              <div className="body">{handoff.summary}</div>
              {handoff.status === 'OPEN' && (
                <button className="btn-ghost" type="button" onClick={() => void resolve(handoff.id)}>
                  Mark resolved
                </button>
              )}
            </div>
          ))}
        </div>
      </div>
      <div>
        <div className="biz-sec">Confirmed bookings</div>
        <div className="biz-list">
          {bookings.length === 0 && <p className="biz-empty">No confirmed bookings.</p>}
          {bookings.map((booking) => (
            <div className="biz-row" key={booking.id}>
              <div className="top">
                <span className="name">{booking.contact_name}</span>
                <span className="price">
                  {booking.date} · {booking.shift}
                  {booking.time !== null && ` ${booking.time.slice(0, 5)}`} · {booking.party_size}
                </span>
              </div>
              <div className="meta">
                ref {booking.code}
                {booking.contact_phone !== null && ` · ${booking.contact_phone}`}
                {booking.notes !== null && ` · ${booking.notes}`}
              </div>
            </div>
          ))}
        </div>
        <div className="biz-sec">Questions the agents could not answer</div>
        <div className="biz-list">
          {questions.length === 0 && <p className="biz-empty">None.</p>}
          {questions.map((question) => (
            <div className="biz-row" key={question.id}>
              <div className="body">{question.question}</div>
              <div className="meta">{formatUtc(question.created_at)}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

const STATUS_CHIP: Record<OwnerNotificationResponse['status'], string> = {
  SENT: 'chip moss',
  PENDING: 'chip quiet',
  NOT_SENT: 'chip slate',
  FAILED: 'chip slate',
}

/**
 * The owner: the link that binds their chat on the owner bot, every notice
 * with what happened to it, and the nightly summaries.
 */
export function OwnerArea({ client, tenantId }: { client: ApiClient; tenantId: string }) {
  const [owner, setOwner] = useState<OwnerResponse | null>(null)
  const [notices, setNotices] = useState<OwnerNotificationResponse[]>([])
  const [digests, setDigests] = useState<DailyDigestResponse[]>([])
  const [botId, setBotId] = useState('')
  const [issued, setIssued] = useState<OwnerLinkCodeResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      setOwner(await client.getOwner(tenantId))
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.status === 404)) setError(toFormError(cause).message)
      setOwner(null)
    }
    try {
      const [n, d] = await Promise.all([client.listOwnerNotifications(tenantId), client.listDailyDigests(tenantId)])
      setNotices([...n.data].reverse())
      setDigests([...d.data].reverse())
    } catch (cause) {
      setError(toFormError(cause).message)
    }
  }, [client, tenantId])

  useEffect(() => {
    void load()
  }, [load])

  async function issue() {
    setError(null)
    try {
      setIssued(await client.issueOwnerLinkCode(tenantId, botId.trim()))
    } catch (cause) {
      setError(toFormError(cause).message)
    }
  }

  async function runDigest() {
    setError(null)
    try {
      await client.runDailyDigest(tenantId)
      await load()
    } catch (cause) {
      setError(toFormError(cause).message)
    }
  }

  return (
    <div className="biz-cols3">
      <div>
        <div className="biz-sec">Owner</div>
        {error !== null && <div className="error-note"><div className="d">{error}</div></div>}
        {owner !== null ? (
          <p className="biz-empty">
            Linked through bot config <code>{owner.channel_config_id}</code> since {formatUtc(owner.linked_at)}.
          </p>
        ) : (
          <p className="biz-empty">No owner linked: notices are recorded as not sent.</p>
        )}
        <div className="biz-sec">Link code for the owner bot</div>
        <div className="field">
          <input
            className="input"
            type="text"
            aria-label="Owner bot channel config id"
            placeholder="channel config id of the owner bot"
            value={botId}
            onChange={(event) => setBotId(event.target.value)}
            spellCheck={false}
          />
        </div>
        <button className="btn" type="button" onClick={() => void issue()} disabled={botId.trim() === ''}>
          Issue a link code
        </button>
        {issued !== null && (
          <div className="biz-row" style={{ marginTop: 14 }}>
            <div className="biz-code">{issued.code}</div>
            <div className="meta">
              Shown once · expires {formatUtc(issued.expires_at)} · the owner sends it to the owner bot (Telegram:
              open t.me/&lt;bot&gt;?start={issued.code})
            </div>
          </div>
        )}
        <div className="biz-sec">Nightly summaries</div>
        <button className="btn-ghost" type="button" onClick={() => void runDigest()}>
          Run today’s summary now
        </button>
        <div className="biz-list" style={{ marginTop: 10 }}>
          {digests.length === 0 && <p className="biz-empty">None yet.</p>}
          {digests.map((digest) => (
            <div className="biz-row" key={digest.id}>
              <div className="top">
                <span className="name">{digest.local_date}</span>
                <span className={digest.notified ? 'chip moss' : 'chip quiet'}>
                  {digest.notified ? 'queued' : 'not sent: empty day'}
                </span>
              </div>
              <div className="body">{digest.content}</div>
            </div>
          ))}
        </div>
      </div>
      <div>
        <div className="biz-sec">Notices to the owner</div>
        <div className="biz-list">
          {notices.length === 0 && <p className="biz-empty">No notices.</p>}
          {notices.map((notice) => (
            <div className="biz-row" key={notice.id}>
              <div className="top">
                <span className="chip quiet">{notice.kind}</span>
                <span className={STATUS_CHIP[notice.status]}>{notice.status}</span>
                {notice.reason !== null && <span className="meta">{notice.reason}</span>}
              </div>
              <div className="meta">
                {formatUtc(notice.created_at)} · {notice.attempts} attempt{notice.attempts === 1 ? '' : 's'}
              </div>
              <div className="body">{notice.content}</div>
            </div>
          ))}
        </div>
        <p className="biz-empty" style={{ marginTop: 12 }}>
          The dispatcher sends pending notices every few seconds. Outside a channel’s reply window (WhatsApp, 24 h)
          a notice is not sent and says so; it stays here.
        </p>
      </div>
    </div>
  )
}
