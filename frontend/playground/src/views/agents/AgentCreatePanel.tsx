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
 * DTO requires, plus optional temperature / max_response_tokens (verified
 * to be accepted by CreateAgentRequest — blank leaves the server default).
 * A soft warning fires when the typed name already exists in the loaded
 * list: names are not unique, the id disambiguates, but a nudge helps.
 */
export function AgentCreatePanel({
  tenantName,
  existingAgents,
  onCreate,
}: {
  tenantName: string
  existingAgents: AgentResponse[]
  onCreate(body: CreateAgentBody): Promise<void>
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
    <div className="panel">
      <h3>New agent</h3>
      <p className="sub">
        Created under <strong>{tenantName}</strong>. Optional fields left blank take the server's
        defaults.
      </p>

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
        <label htmlFor="agent-name">Name</label>
        <input
          className="input"
          id="agent-name"
          type="text"
          placeholder="e.g. support-agent"
          value={name}
          onChange={(event) => setName(event.target.value)}
          disabled={submitting}
        />
      </div>
      {duplicate && (
        <div className="dup-warn">
          An agent named “{trimmedName}” already exists in this tenant. Names aren't unique — the id
          disambiguates — but consider a distinct name.
        </div>
      )}

      <div className="field">
        <label htmlFor="agent-provider">Model provider</label>
        <div className="select-wrap">
          <select
            className="select-input"
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
        </div>
        <div className="helper">Validated server-side against the supported provider set.</div>
      </div>

      <div className="field">
        <label htmlFor="agent-model">Model name</label>
        <input
          className="input mono"
          id="agent-model"
          type="text"
          placeholder="qwen2.5:3b"
          value={modelName}
          onChange={(event) => setModelName(event.target.value)}
          disabled={submitting}
        />
        <div className="helper">
          Not validated against the provider at create time — a typo surfaces as a provider error on
          the first message.
        </div>
      </div>

      <div className="field">
        <label htmlFor="agent-prompt">System prompt</label>
        <textarea
          className="input"
          id="agent-prompt"
          placeholder="You are a helpful assistant…"
          value={systemPrompt}
          onChange={(event) => setSystemPrompt(event.target.value)}
          disabled={submitting}
        />
      </div>

      <div className="field-pair">
        <div className="field">
          <label htmlFor="agent-temp">Temperature (opt)</label>
          <input
            className="input mono"
            id="agent-temp"
            type="number"
            step="0.1"
            min="0"
            max="1"
            placeholder="0.0–1.0"
            value={temperature}
            onChange={(event) => setTemperature(event.target.value)}
            disabled={submitting}
          />
        </div>
        <div className="field">
          <label htmlFor="agent-max">Max tokens (opt)</label>
          <input
            className="input mono"
            id="agent-max"
            type="number"
            min="1"
            placeholder="server default"
            value={maxTokens}
            onChange={(event) => setMaxTokens(event.target.value)}
            disabled={submitting}
          />
        </div>
      </div>

      <button
        className="btn-primary"
        type="button"
        onClick={() => void submit()}
        disabled={!canSubmit}
      >
        {submitting ? 'Creating…' : 'Create agent'}
      </button>

      <p className="server-note">
        The API returns server-managed values on creation — <code>status: DRAFT</code>,{' '}
        <code>temperature</code>, <code>max_response_tokens</code>. They're shown on the card.
      </p>
    </div>
  )
}
