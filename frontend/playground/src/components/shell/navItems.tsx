import type { ReactNode } from 'react'

/**
 * Single source for the navigation: 4 groups, 7 entries. The icons are the
 * exact inline stroke SVGs from the mockup. Everything except Session
 * requires an active session.
 */
export interface NavItem {
  label: string
  path: string
  requiresSession: boolean
  icon: ReactNode
}

export interface NavGroup {
  eyebrow: string
  items: NavItem[]
}

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round',
} as const

export const NAV_GROUPS: NavGroup[] = [
  {
    eyebrow: 'Session',
    items: [
      {
        label: 'Session',
        path: '/',
        requiresSession: false,
        icon: (
          <svg className="ico" viewBox="0 0 24 24" {...stroke}>
            <circle cx="12" cy="12" r="9" />
            <path d="M12 8v4l2.5 2.5" />
          </svg>
        ),
      },
    ],
  },
  {
    eyebrow: 'Workspace',
    items: [
      {
        label: 'Tenants',
        path: '/tenants',
        requiresSession: true,
        icon: (
          <svg className="ico" viewBox="0 0 24 24" {...stroke}>
            <path d="M12 3v18M5 8l7-5 7 5M5 16l7 5 7-5" />
          </svg>
        ),
      },
      {
        label: 'Agents',
        path: '/agents',
        requiresSession: true,
        icon: (
          <svg className="ico" viewBox="0 0 24 24" {...stroke}>
            <rect x="4" y="6" width="16" height="12" rx="2" />
            <path d="M9 11h.01M15 11h.01M9 15h6" />
          </svg>
        ),
      },
      {
        label: 'API keys',
        path: '/api-keys',
        requiresSession: true,
        icon: (
          <svg className="ico" viewBox="0 0 24 24" {...stroke}>
            <circle cx="8" cy="14" r="4" />
            <path d="M11 11l8-8M17 4l3 3M14 7l2 2" />
          </svg>
        ),
      },
    ],
  },
  {
    eyebrow: 'Runtime',
    items: [
      {
        label: 'Conversation',
        path: '/conversation',
        requiresSession: true,
        icon: (
          <svg className="ico" viewBox="0 0 24 24" {...stroke}>
            <path d="M4 6h16v10H8l-4 4z" />
          </svg>
        ),
      },
      {
        label: 'Invocations',
        path: '/invocations',
        requiresSession: true,
        icon: (
          <svg className="ico" viewBox="0 0 24 24" {...stroke}>
            <path d="M3 12h4l3-7 4 14 3-7h4" />
          </svg>
        ),
      },
    ],
  },
  {
    eyebrow: 'Trust',
    items: [
      {
        label: 'Audit chain',
        path: '/audit',
        requiresSession: true,
        icon: (
          <svg className="ico" viewBox="0 0 24 24" {...stroke}>
            <path d="M8 8a4 4 0 118 0v2M8 16a4 4 0 108 0v-2" />
            <path d="M8 10h8v4H8z" />
          </svg>
        ),
      },
    ],
  },
]
