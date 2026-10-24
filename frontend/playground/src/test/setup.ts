import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Without Vitest's globals, Testing Library cannot register its own cleanup.
afterEach(() => {
  cleanup()
})
