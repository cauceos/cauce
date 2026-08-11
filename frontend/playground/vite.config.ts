import { defineConfig } from 'vite'
import type { Connect, Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { createProxyMiddleware } from 'http-proxy-middleware'
import { ServerResponse } from 'node:http'

const TARGET_HEADER = 'x-cauce-target'

/**
 * Same-origin `/proxy/*` -> the Cauce instance the client names per request.
 *
 * The backend has no CORS configuration (deliberate — see the deferred
 * register in the root CLAUDE.md), so the browser blocks direct cross-origin
 * fetches from the dev server to the instance. The API client instead calls
 * same-origin `/proxy/...` paths and names the real target in the
 * X-Cauce-Target header; this plugin routes each request there. Vite's
 * built-in `server.proxy` cannot do this (its target is frozen at startup),
 * hence http-proxy-middleware, whose `router` resolves the target per
 * request.
 *
 * Proxy-synthesized errors (400 bad target, 502 unreachable upstream) are
 * shaped like the backend's error envelope so the client parses one format.
 * Dev-only by construction: the plugin lives in `configureServer`, which
 * `vite build` never runs.
 */
function cauceDynamicProxy(): Plugin {
  return {
    name: 'cauce-dynamic-proxy',
    configureServer(server) {
      const proxy = createProxyMiddleware({
        // Fallback only; the router wins whenever the header is present,
        // and the guard below rejects requests without it.
        target: 'http://localhost:8080',
        router: (req) => {
          const raw = req.headers[TARGET_HEADER]
          const value = Array.isArray(raw) ? raw[0] : raw
          // The guard middleware has already validated the header.
          return new URL(value!).origin
        },
        changeOrigin: true,
        proxyTimeout: 20_000,
        on: {
          error: (err, _req, res) => {
            // `res` is a Socket for upgrade requests; only plain HTTP gets a body.
            if (res instanceof ServerResponse) {
              if (!res.headersSent) {
                res.writeHead(502, { 'content-type': 'application/json' })
              }
              // Node's connection failures often carry an empty message and
              // put the useful bit in `code` (e.g. ECONNREFUSED). A reached-
              // but-idle upstream surfaces as a proxyTimeout abort (ECONNRESET
              // / "socket hang up") — that instance is alive, so it must not
              // be reported as unreachable.
              const code = (err as NodeJS.ErrnoException).code
              const timedOut = code === 'ECONNRESET' || err.message === 'socket hang up'
              const detail = err.message || code || 'connection failed'
              res.end(
                JSON.stringify(
                  timedOut
                    ? {
                        error: 'upstream_timeout',
                        message:
                          'The instance was reached but sent no response before the proxy timeout.',
                        request_id: null,
                      }
                    : {
                        error: 'upstream_unreachable',
                        message: `The dev proxy could not reach the instance: ${detail}`,
                        request_id: null,
                      },
                ),
              )
            }
          },
        },
      })

      const guard: Connect.NextHandleFunction = (req, res, next) => {
        const raw = req.headers[TARGET_HEADER]
        const value = Array.isArray(raw) ? raw[0] : raw
        let valid = false
        try {
          valid = value != null && /^https?:$/.test(new URL(value).protocol)
        } catch {
          valid = false
        }
        if (!valid) {
          res.writeHead(400, { 'content-type': 'application/json' })
          res.end(
            JSON.stringify({
              error: 'proxy_target_missing',
              message: 'X-Cauce-Target header absent or not a valid http(s) URL.',
              request_id: null,
            }),
          )
          return
        }
        proxy(req, res, next)
      }

      // connect strips the mount prefix: /proxy/actuator/health is forwarded
      // upstream as /actuator/health.
      server.middlewares.use('/proxy', guard)
    },
  }
}

export default defineConfig({
  plugins: [react(), tailwindcss(), cauceDynamicProxy()],
})
