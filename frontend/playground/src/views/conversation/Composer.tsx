import { useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import type { SendFailure } from './useConversationMachine'

const MAX_TEXTAREA_HEIGHT_PX = 140

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
    <div className="composer-wrap">
      <div className="composer">
        {sendError !== null && (
          <div className="composer-error" role="alert">
            {sendError.message}
            {sendError.fields.map((violation) => (
              <span key={violation.field}>
                {' '}
                · {violation.field}: {violation.message}
              </span>
            ))}
          </div>
        )}
        <div className="composer-box">
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
          <button
            className="btn-send"
            type="button"
            disabled={!canSend || text.trim() === ''}
            onClick={() => void submit()}
          >
            Send
          </button>
        </div>
        <div className="wire">
          <span>
            {wireLeft !== null ? (
              wireLine(wireLeft)
            ) : (
              <span className="path">POST /v1/agents/{'{id}'}/messages</span>
            )}
          </span>
          {wireRight !== null && <span>{wireLine(wireRight)}</span>}
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
