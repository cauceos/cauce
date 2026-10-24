import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/**
 * The playground's component tests: rendered in jsdom, no instance behind
 * them. Part of `npm run build`, the playground's gate.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.tsx'],
    setupFiles: ['src/test/setup.ts'],
  },
})
