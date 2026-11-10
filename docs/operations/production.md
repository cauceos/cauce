# Production runbook

What an instance needs before its first start with `SPRING_PROFILES_ACTIVE=prod`. Every value comes
from the environment (`backend/cauce-api/src/main/resources/application-prod.properties`); nothing
is hardcoded and nothing here goes into version control or into the database.

## Required secrets

The instance does not start without any of these, or with one that is malformed: there is no
fallback in prod. Outside prod, the keys have public development defaults that protect nothing.

| Variable | What it is | How to generate | If it changes |
|---|---|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | The runtime connection, as the least-privilege `cauce_app` role (subject to RLS) | Provisioned with the role (below) | — |
| `ADMIN_DATABASE_USERNAME`, `ADMIN_DATABASE_PASSWORD` (`ADMIN_DATABASE_URL` defaults to `DATABASE_URL`) | The owner connection, for Flyway and the operator bootstrap | The database owner | — |
| `API_KEY_PEPPER` | HMAC key of the API keys | `openssl rand -base64 32` | Every API key stops working |
| `SECRETS_ENCRYPTION_KEY` | AES-256 key sealing provider secrets at rest (ADR 0006): base64 of exactly 32 bytes | `openssl rand -base64 32` | Every sealed secret (channel tokens, WhatsApp app secrets) becomes unreadable; there is no rotation yet |
| `WEB_IP_HASH_KEY` | HMAC key of the visitor IPs counted by the web chat's per-IP limits (ADR 0014): base64 of exactly 32 bytes | `openssl rand -base64 32` | Per-IP limit counters start again; nothing stored is lost, because an IP is never stored, only counted under its HMAC in Redis |

The three keys are handled the same way:

- **Outside the database it protects.** An actor holding the database alone must not be able to
  open a sealed secret, forge an API key's hash or recover an IP from its HMAC.
- **Shared by every instance.** All instances of one deployment use the same value; the per-IP
  limits only add up across instances if they hash under the same key.
- **Stable.** Generate once, keep in the secret store, and change only deliberately, knowing the
  consequence in the table.
- **Never logged, never committed.** `.env` is gitignored; a value pasted in a ticket or a chat is
  a value to replace.

## Before the first start

1. Provision the `cauce_app` login role out of band; migration `V10` grants it on first start.
2. Set every variable above in the environment of each instance.
3. Start the instance. On an empty database, `OperatorKeyBootstrapRunner` creates the root operator
   and logs its API key **once** (`WARN`): copy it, it cannot be recovered.

## Optional

Model provider keys (`ANTHROPIC_API_KEY`, the OpenAI-compatible ones), audit signing
(`AUDIT_SIGNING_*`) and the web chat's `WEB_TRUSTED_PROXIES` and `WEB_WIDGET_DIR` are optional: the
instance starts without them, with the feature they enable off. Monitoring is in
[observability.md](observability.md).
