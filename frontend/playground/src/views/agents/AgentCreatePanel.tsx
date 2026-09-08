import { useState } from 'react'
import type { AgentResponse, CreateAgentBody } from '../../api/types'
import { toFormError } from '../../lib/formError'
import type { FormError } from '../../lib/formError'

// Mirrors AgentService.SUPPORTED_PROVIDERS (cauce-tenancy) — validated
// server-side. Hardcoded here because the set is not exposed over the API;
// it moves to the cauce-llm SPI eventually. `ollama` is the dev default.
const PROVIDERS = ['ollama', 'openai', 'anthropic', 'mistral'] as const

/**
 * Create an agent under the loaded CLIENT tenant. Six fields: the four the
 * DTO requires, plus optional temperature / max_response_tokens — blank
 * means the server decides, and the card then shows what it decided.
 *
 * Rendered inside a `.panel` (desktop, tablet) or a bottom sheet (mobile)
 * by the view — this component is the content only.
 *
 * A soft warning fires when the typed name already exists in the loaded
 * list: duplicate names are legal, the chip and the id disambiguate, but a
 * nudge helps. It informs; it never blocks.
 */
export function AgentCreatePanel({
  tenantName,
  existingAgents,
  onCreate,
}: {
  tenantName: string
  existingAgents: AgentResponse[]
  onCreate(body: CreateAgentBody): Promise<AgentResponse>
}) {
  const [name, setName] = useState('')
  const [provider, setProvider] = useState<string>('ollama')
  const [modelName, setModelName] = useState('')
  const [systemPrompt, setSystemPrompt] = useState('')
  const [temperature, setTemperature] = useState('')
  const [maxTokens, setMaxTokens] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError | null>(null)

  const trimmedName = name.trim()
  const duplicate = trimmedName !== '' && existingAgents.some((a) => a.name === trimmedName)
  const canSubmit =
    trimmedName !== '' && modelName.trim() !== '' && systemPrompt.trim() !== '' && !submitting
  // Which fields the envelope pointed at, so the slate lands on them.
  const rejected = new Set(error?.fields.map((f) => f.field) ?? [])
  const cls = (field: string) => (rejected.has(field) ? 'input err' : 'input')

  async function submit() {
    if (!canSubmit) return
    setSubmitting(true)
    setError(null)
    const body: CreateAgentBody = {
      name: trimmedName,
      system_prompt: systemPrompt.trim(),
      model_provider: provider,
      model_name: modelName.trim(),
    }
    if (temperature.trim() !== '') body.temperature = Number(temperature)
    if (maxTokens.trim() !== '') body.max_response_tokens = Number(maxTokens)
    try {
      await onCreate(body)
      setName('')
      setModelName('')
      setSystemPrompt('')
      setTemperature('')
      setMaxTokens('')
    } catch (cause) {
      setError(toFormError(cause))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <h3>New agent</h3>
      <p className="why">
        Under <b>{tenantName}</b>. Two fields are optional — blank means the server decides, and
        the card shows what it decided.
      </p>

      {/* The envelope's errors[].field lands at the field, in slate. Wire
          field names are rendered as-is. */}
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
          <label htmlFor="agent-name">Name</label>
        </div>
        <input
          className={cls('name')}
          id="agent-name"
          type="text"
          placeholder="support-agent"
          value={name}
          onChange={(event) => setName(event.target.value)}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          disabled={submitting}
        />
      </div>

      {duplicate && (
        <div className="error-note soft">
          An agent named <b>{trimmedName}</b> already exists in this list. Duplicate names are
          legal — this only informs.
        </div>
      )}

      <div className="field">
        <div className="f-top">
          <label htmlFor="agent-provider">Model provider</label>
        </div>
        <select
          className={cls('modelProvider')}
          id="agent-provider"
          value={provider}
          onChange={(event) => setProvider(event.target.value)}
          disabled={submitting}
        >
          {PROVIDERS.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>
        <p className="helper">Mirrors the backend&apos;s supported set. The API validates it too.</p>
      </div>

      <div className="field">
        <div className="f-top">
          <label htmlFor="agent-model">Model name</label>
        </div>
        <input
          className={cls('modelName')}
          id="agent-model"
          type="text"
          placeholder="qwen2.5:3b"
          value={modelName}
          onChange={(event) => setModelName(event.target.value)}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          disabled={submitting}
        />
        <p className="helper">
          Stored as written — the provider rejects a wrong one at invocation time, not here.
        </p>
      </div>

      <div className="field">
        <div className="f-top">
          <label htmlFor="agent-prompt">System prompt</label>
        </div>
        <textarea
          className={cls('systemPrompt')}
          id="agent-prompt"
          placeholder="You are a helpful support assistant…"
          value={systemPrompt}
          onChange={(event) => setSystemPrompt(event.target.value)}
          disabled={submitting}
        />
      </div>

      <div className="fld2">
        <div className="field">
          <div className="f-top">
            <label htmlFor="agent-temp">Temperature</label>
            <span className="req">optional</span>
          </div>
          <input
            className={cls('temperature')}
            id="agent-temp"
            type="text"
            inputMode="decimal"
            placeholder="0.0 – 1.0"
            value={temperature}
            onChange={(event) => setTemperature(event.target.value)}
            autoCapitalize="off"
            autoCorrect="off"
            disabled={submitting}
          />
        </div>
        <div className="field">
          <div className="f-top">
            <label htmlFor="agent-max">Max tokens</label>
            <span className="req">optional</span>
          </div>
          <input
            className={cls('maxResponseTokens')}
            id="agent-max"
            type="text"
            inputMode="numeric"
            placeholder="server default"
            value={maxTokens}
            onChange={(event) => setMaxTokens(event.target.value)}
            autoCapitalize="off"
            autoCorrect="off"
            disabled={submitting}
          />
        </div>
      </div>
      <p className="helper" style={{ marginBottom: 16 }}>
        Blank → server defaults apply. The created card shows the returned values.
      </p>

      <button className="btn" type="button" onClick={() => void submit()} disabled={!canSubmit}>
        {submitting ? 'Creating…' : 'Create agent'}
      </button>
      <p className="p-note">
        Agents can only belong to a CLIENT tenant — a partner or operator id above answers 422.
      </p>
      <div className="gap-note">
        No update · no delete — recreate to change; the API doesn&apos;t expose them yet.
      </div>
    </>
  )
}
