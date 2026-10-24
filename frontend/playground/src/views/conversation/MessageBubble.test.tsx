import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { message } from '../../test/messages'
import { MessageBubble } from './MessageBubble'

describe('MessageBubble', () => {
  it('shows a text message as its text, with no voice-note label', () => {
    render(<MessageBubble item={{ kind: 'user', message: message('USER', 'Hola') }} identityRef="user-1" agentName="Bot" />)

    expect(screen.getByText('Hola')).toBeTruthy()
    expect(screen.queryByText(/voice note/)).toBeNull()
  })

  it('shows a transcribed voice note as its transcript, labelled with its length', () => {
    const note = message('USER', 'Mesa para cuatro', [
      { type: 'audio', media_type: 'audio/ogg', duration_seconds: 4, transcript: 'Mesa para cuatro' },
    ])

    render(<MessageBubble item={{ kind: 'user', message: note }} identityRef="user-1" agentName="Bot" />)

    expect(screen.getByText(/voice note · 4 s · transcribed/)).toBeTruthy()
    expect(screen.getByText('Mesa para cuatro', { exact: false })).toBeTruthy()
  })

  it('says so when a voice note is not transcribed yet', () => {
    const note = message('USER', '[voice note]', [{ type: 'audio', media_type: 'audio/ogg' }])

    render(<MessageBubble item={{ kind: 'user', message: note }} identityRef="user-1" agentName="Bot" />)

    expect(screen.getByText(/not transcribed yet/)).toBeTruthy()
    expect(screen.queryByText(/ s ·/)).toBeNull()
  })

  it('shows the agent reply as body text', () => {
    render(
      <MessageBubble item={{ kind: 'agent', message: message('AGENT', 'Claro, ¿a qué hora?'), meta: null }} identityRef="user-1" agentName="Camarero" />,
    )

    expect(screen.getByText('Camarero')).toBeTruthy()
    expect(screen.getByText('Claro, ¿a qué hora?')).toBeTruthy()
  })
})
