import { useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import type { SendFailure } from './useConversationMachine'

const MAX_TEXTAREA_HEIGHT_PX = 180

export function Composer({
  agentName,
  canSend,
  sendError,
  wireLeft,
  wireRight,
  onSend,
}: {
  agentName: string | null
  canSend: boolean
  sendError: SendFailure | null
  wireLeft: string | null
  wireRight: string | null
  onSend(content: string): Promise<boolean>
}) {
  const [text, setText] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const submit = async () => {
    if (!canSend || text.trim() === '') return
    const accepted = await onSend(text)
    if (accepted) {
      setText('')
      const textarea = textareaRef.current
      if (textarea !== null) textarea.style.height = 'auto'
    }
    // On failure the text stays: retrying the identical submission reuses
    // the same Idempotency-Key, so a lost 202 cannot duplicate the message.
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    // Enter sends, Shift+Enter breaks the line; never fire mid-IME
    // composition (Enter there confirms the composed text).
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      void submit()
    }
  }

  return (
    <div className="composer">
      <div className="c-in">
        {sendError !== null && (
          <div className="c-error" role="alert">
            {sendError.message}
            {sendError.fields.map((violation) => (
              <span key={violation.field}>
                {' '}
                · {violation.field}: {violation.message}
              </span>
            ))}
          </div>
        )}

        <div className="c-row">
          <div className="c-box">
            <textarea
              ref={textareaRef}
              rows={1}
              placeholder={agentName !== null ? `Message ${agentName}…` : 'Select an agent first…'}
              value={text}
              disabled={agentName === null}
              onChange={(event) => {
                setText(event.target.value)
                const textarea = event.target
                textarea.style.height = 'auto'
                textarea.style.height = `${Math.min(textarea.scrollHeight, MAX_TEXTAREA_HEIGHT_PX)}px`
              }}
              onKeyDown={onKeyDown}
            />
          </div>
          <button
            className="send"
            type="button"
            aria-label="Send"
            disabled={!canSend || text.trim() === ''}
            onClick={() => void submit()}
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2.2}
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M5 12h13M13 6l6 6-6 6" />
            </svg>
          </button>
        </div>

        <div className="c-hints">
          <span>
            <span className="kbd">⏎</span> send
          </span>
          <span>
            <span className="kbd">⇧⏎</span> newline
          </span>
          {/* The state slot carries the live wire echo instead of a label:
              what the screen is actually doing beats a description of it. */}
          <span className="state">
            {wireLeft !== null ? wireLine(wireLeft) : 'idle · one Idempotency-Key per send'}
            {wireRight !== null && (
              <>
                <br />
                {wireLine(wireRight)}
              </>
            )}
          </span>
        </div>
      </div>
    </div>
  )
}

/** Render "PATH → RESULT" with the mockup's coloring (202 in moss). */
function wireLine(line: string) {
  const arrow = line.lastIndexOf(' → ')
  if (arrow === -1) return <span className="path">{line}</span>
  const path = line.slice(0, arrow)
  const result = line.slice(arrow + 3)
  return (
    <>
      <span className="path">{path}</span> →{' '}
      {result === '202' ? <span className="code-ok">{result}</span> : result}
    </>
  )
}
