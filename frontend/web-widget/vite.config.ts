import { defineConfig } from 'vite'

// One self-contained script, no framework, no CSS file: what a shop embeds (ADR 0014, W4).
export default defineConfig({
  build: {
    lib: {
      entry: 'src/main.ts',
      name: 'CauceWidget',
      formats: ['iife'],
      fileName: () => 'cauce-widget.js',
    },
    target: 'es2020',
    minify: 'esbuild',
    sourcemap: false,
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
