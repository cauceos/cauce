import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { AgendaResponse, BusinessProfileResponse, CatalogResponse } from '../../api/types'
import { AgendaPreview, CatalogPreview, ProfilePreview } from './previews'

const STAMPS = { id: 'x', tenant_id: 't', created_at: '2026-10-24T10:00:00Z', updated_at: '2026-10-24T10:00:00Z' }

describe('business previews', () => {
  it('shows the profile as the agents read it: its facts, its handoff categories, its nightly summary', () => {
    const profile: BusinessProfileResponse = {
      ...STAMPS,
      name: 'Bar La Esquina',
      time_zone: 'Europe/Madrid',
      locale: 'es',
      facts: { horario: 'de martes a domingo' },
      handoff_categories: ['grupo', 'queja'],
      daily_digest_time: '22:30:00',
      daily_digest_skip_when_empty: true,
    }

    render(<ProfilePreview profile={profile} />)

    expect(screen.getByText('Bar La Esquina')).toBeTruthy()
    expect(screen.getByText('horario')).toBeTruthy()
    expect(screen.getByText('de martes a domingo')).toBeTruthy()
    expect(screen.getByText('grupo · queja')).toBeTruthy()
    expect(screen.getByText('at 22:30 local, empty days skipped')).toBeTruthy()
  })

  it('shows an attribute an item does not declare as not declared, never as empty', () => {
    const catalog: CatalogResponse = {
      ...STAMPS,
      declared_attributes: ['alergenos'],
      items: [
        { ref: 'bravas', section: 'Tapas', name: 'Patatas bravas', description: null, price: 5.5, attributes: { alergenos: [] } },
        { ref: 'croquetas', section: 'Tapas', name: 'Croquetas', description: null, price: null, attributes: {} },
      ],
    }

    render(<CatalogPreview catalog={catalog} />)

    expect(screen.getByText('alergenos: none')).toBeTruthy()
    expect(screen.getByText('? alergenos: not declared')).toBeTruthy()
    expect(screen.getByText('5.50')).toBeTruthy()
  })

  it('shows the agenda limits and shifts', () => {
    const agenda: AgendaResponse = {
      ...STAMPS,
      unit: 'cubiertos',
      max_party_size: 8,
      hold_minutes: 10,
      horizon_days: 60,
      require_phone: true,
      shifts: [{ id: 'cena', days: ['vie', 'sab'], start: '20:30', end: '23:00', capacity: 40 }],
      closed_dates: [],
    }

    render(<AgendaPreview agenda={agenda} />)

    expect(screen.getByText(/Up to 8 cubiertos per booking/)).toBeTruthy()
    expect(screen.getByText(/phone required/)).toBeTruthy()
    expect(screen.getByText('20:30–23:00')).toBeTruthy()
    expect(screen.getByText('no dates')).toBeTruthy()
  })
})
