# Cauce Playground

A developer testing tool for a running Cauce instance: the REST surface your clients use,
exercised from the browser, authenticated with an API key and scoped to its tenant. This is
**not** the future product dashboard (`frontend/cauce-dashboard`, Angular, not started) —
it is a playground for poking at instances during development.

## Prerequisites

- Node.js 20.19+ or 22.12+ (Vite 7 requirement)
- A running Cauce instance — see the repo root [QUICKSTART.md](../../QUICKSTART.md), or
  start the Docker Compose stack and run `./gradlew :cauce-api:bootRun` from `backend/`

## Run

```bash
npm install
npm run dev        # serves http://localhost:5173 (Vite picks the next port if busy)
```

Enter the instance URL (the local default is `http://localhost:8080`) and an API key, then
Connect. On a fresh database the root operator key is printed **once** as a WARN line in
the API log by `OperatorKeyBootstrapRunner`; it cannot be recovered later.

The session lives in memory only: the API key is never written to localStorage,
sessionStorage, or cookies, so refreshing the page forgets it. That is deliberate.

## How requests reach the instance

The backend has no CORS configuration (see the deferred register in the root `CLAUDE.md`),
so the browser cannot call it cross-origin. The dev server bridges this: the app calls
same-origin `/proxy/...` paths and names the real target in the `X-Cauce-Target` header; a
small Vite plugin (`vite.config.ts`) routes each request there. Consequence: the playground
runs through `npm run dev` — there is no standalone production hosting target.

## Verify

```bash
npm run build      # tsc -b && vite build — the type-check + build gate
```

## Troubleshooting

- **"Nothing answered at http://localhost:8080" with the instance clearly up** — on some
  Windows/Node setups `localhost` resolves to IPv6 `::1` while the instance listens on
  IPv4. Use `http://127.0.0.1:8080` as the instance URL.

## Reference mockups

`referencias/` holds static HTML mockups — the visual specification the screens are built
against. They are documentation, not code.
