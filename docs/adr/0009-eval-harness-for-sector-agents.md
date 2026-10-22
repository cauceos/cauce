# ADR 0009: The eval harness for sector agents

Status: Accepted. Not implemented yet.

## Context

ADR 0007 (B12) wrote the scenarios of the hospitality pack as data before there was anything to run
them: `examples/hosteleria/evals/*.yaml`, 37 scenarios in six files, the checklist of the manual
tests. Two real tests on Telegram on 2026-10-22 found nine defects and then confirmed three of the
fixes, by hand. Every change to a prompt, a tool description or a model now needs the same kind of
evidence, measured rather than remembered: which share of the runs did the right thing, scenario by
scenario.

The agent is not deterministic, so one run proves little in either direction. Each run of a
scenario costs real tokens. The checks must not depend on another model wherever the record already
holds the answer.

What the platform already gives a harness:

- A turn can be driven synchronously in-process: the cauce-api ITs post a message, run the worker by
  hand (`pollAndProcess()`) and read the result.
- The trace of a turn is in `messages`: each `TOOL_CALL` keeps its tool name and input
  (`tool_content.input`), in order, between the USER message and the AGENT (or FALLBACK) reply. The
  audit chain (`conduct.tool.called`) holds the same calls by name, round and invocation, but its
  arguments only as a hash (ADR 0005, C5).
- What the agent did to the business is in its tables: bookings, handoffs, unanswered questions,
  owner notifications.
- Token usage per call, cached parts included, is in `llm_usage_records`.

What the scenario format lacks today: its `estado` keys are ad hoc (`personas_reserva_sabado`), a
`previo` cannot name a value the system mints (a booking code), relative dates have no defined
resolution, and the general rule of G1 is prose.

## Decision

### E1 — In-process, against an ephemeral database

The harness starts the cauce-api application context against PostgreSQL in Testcontainers, with the
real model provider and the worker run by hand. Seeding a `previo` (a confirmed booking, an owner
already linked) goes through the services, and each turn completes before the next one is sent.

Discarded: driving a running instance over HTTP. It cannot seed what has no endpoint (a booking made
in an earlier conversation), it has to poll, and its runs would mix with the instance's real data.

### E2 — Outside the normal build

A source set of its own (`cauce-evals/src/eval/java`) and a task, `:cauce-evals:eval`, that neither
`check` nor `build` depends on. It fails at once, with a clear message, without the key of the
agent's model and of the judge's. CI does not run it. The normal `test` task of `cauce-evals` tests
the harness itself (loader, checks, report) against a scripted provider and stays hermetic.
`cauce-evals` depends on `cauce-api` for the `eval` source set only.

### E3 — Scenario format v2: closed, validated on load

- A key the format does not define is a load error, never a scenario that passes.
- `estado` uses a fixed vocabulary, compared as a subset (what is not named is not checked):
  `reservas: [{estado, fecha, turno, hora, personas, codigo}]`, `casos: [{categoria, estado}]`,
  `preguntas: N`, `avisos: [{tipo, estado, motivo}]`.
- A `previo` entry can be named (`como: r`), and a later check refers to what it minted
  (`codigo: $r.codigo`).
- Relative dates (`+3`, `proximo_sabado`) resolve in the business's time zone on the database clock
  (ADR 0008). A scenario that depends on the weekday names it (`proximo_lunes`), never "today".
- `obligatorio` gives way to `nivel: bloqueante | aviso` (E7).

### E4 — The trace of a turn

A turn is everything from the person's message to the next AGENT or FALLBACK message.

- `herramientas`: the tools called in the turn, in any order.
- `orden`: tools that must appear in this order, as a subsequence.
- `herramientas_no`: tools that must not be called.
- `argumentos`: a subset match on a call's input, ignoring case and surrounding spaces.

The trace is read from `messages` and cross-checked with the audit chain: the same tool names and
the same count per invocation. A mismatch is an error of the harness, reported as such, never a
scenario failure.

### E5 — Deterministic checks first

- Text: `respuesta_contiene` / `respuesta_no_contiene`, ignoring case and spacing.
- What the channel delivers: `entregado_*` on the text `ReplyMarkdown` renders for the scenario's
  channel. It is pure, so no provider is involved.
- What the owner reads: `aviso_al_dueno_*` on the content of the owner notification.
- **G1 is deterministic:** in every turn of every scenario, if the reply names a dish, a price or an
  allergen of the fixture's menu, the turn's trace contains `search_catalog`. The names come from
  `carta.json`.
- **Not inventing the menu is deterministic too:** every dish and price a reply names appears in a
  tool result of that same turn.

### E6 — An LLM judge only for what the record cannot answer

- The judge reads one turn and its written `rubrica`: the person's message, the calls with a summary
  of their results, and the reply. It answers `{veredicto: cumple | no_cumple, motivo}`. An answer
  it cannot parse is **inconclusive**, and an inconclusive verdict never passes.
- It is called through the cauce-llm SPI. By default its provider differs from the agent's, so the
  model never grades itself. A judge from another provider needs that provider's key (E2).
- **Calibration before use.** `examples/hosteleria/evals/calibracion/` holds turns from the real
  tests with the owner of the pack's verdict on each rubric, as labels. A run calibrates the judge
  first: each item three times, and its majority verdict must match the label on every item. A judge
  that disagrees with any label is not used: its rubrics are reported "sin juez", never as passed.

### E7 — Repetitions, rates, and what blocks

- Each scenario runs N times: 5 by default for the whole battery. A run can be limited to a task (a
  file) or to scenarios, with its own N.
- The result is a rate per check, per scenario (a repetition passes when all its checks pass), per
  task and overall. There is no pass/fail of one run.
- **Blocking, at 100 %:** G1, inventing dishes or allergens, acting without the person's
  confirmation, giving prices, touching someone else's booking, and promising on the owner's behalf.
  Scenarios that check these are `nivel: bloqueante`. A blocking rate under 100 % makes the task exit
  with a non-zero status.
- **Everything else is `nivel: aviso`:** a configurable threshold, 90 % by default. A rate under it
  is reported as a warning and blocks nothing.

### E8 — Isolation

Every repetition of every scenario gets a business of its own: a fresh CLIENT tenant under the
run's partner, the fixture's sheet, menu and agenda, and the agents created from the pack (prompt,
`herramientas.json`, `respaldo.json`), with a new identity and conversation. The database is
emptied when a run starts. Repetitions run one after another by default: cost and the provider's
rate limits.

### E9 — Scenarios without the model

A scenario with no turns and a `cubierto_por` is reported as covered by its IT and not run. A
scenario whose action is deterministic (`resumen_de_hoy`, the nightly summary, ADR 0007 B10) runs
once, without repetitions.

### E10 — Cost, estimated and confirmed

- Tokens come from `llm_usage_records` (cached parts included) and from the judge's usage.
- Prices live in a versioned file (`examples/hosteleria/evals/precios.yaml`: provider, model, input,
  cache read, cache write and output per million tokens, and the date). The cost is computed in the
  report and never stored as a fact, as the design rules require.
- **Before a run starts**, the harness estimates its cost: turns × N × tokens per turn, taken from
  the previous run, or from a default the first time.
- **Confirmation over the limit.** If the estimate exceeds a configurable limit (10 US$ by
  default), the run stops and asks for confirmation (`--confirmar`) before spending anything.
- If the measured spend reaches the limit mid-run, the run stops and reports itself as incomplete.

### E11 — History

Each run writes a summary that is committed to the repository:
`examples/hosteleria/evals/historial/<date>-<model>.json`. It holds:

- the agent's and the judge's provider and model;
- the prompt's footprint: the sha256 of the prompt, of the tool grant and of the fixture;
- the commit, and whether the tree had uncommitted changes;
- N, and the date on the database clock;
- the result of every check;
- the tokens, and the version of the price file.

The full traces stay out of git, under `build/`. The report compares the run with the previous one
on the same set of scenarios: rate deltas, new failures and fixed ones.

### E12 — The report

Markdown and the same data as JSON: a table per task, the rate of each scenario, and for each
failing check one example excerpt of the transcript.

## Consequences

- A change to a prompt, a tool description or the model can be measured before and after with the
  same scenarios, the same N and a recorded cost. The first two such experiments are recorded in
  `docs/deferred.md`: R1 (eliding past turns' tool results, watching G1) and R2 (a new thread after
  inactivity, with a "comes back the next day" scenario).
- The scenario files change shape (v2). The pack's README and `evals/README.md` document it, and the
  load-time validation keeps them honest.
- Blocking is narrow on purpose: with 36 of 37 scenarios mandatory, a 100 % gate on all of them
  would fail on any single rubric and stop meaning anything.
- The judge is a dependency on a second provider's key. Without one, the rubric checks are reported
  "sin juez" and the deterministic checks still run.
- The harness reads `messages` and the business tables directly. A change to their shape breaks it
  loudly: its own tests run in the normal build.

## References

- `examples/hosteleria/evals/README.md` — the scenario format
- `docs/adr/0005-agentic-loop-hardening.md` — C5, the audit of every tool call
- `docs/adr/0007-business-capabilities-and-sector-agents.md` — B12, scenarios as data
- `docs/adr/0008-one-time-source-the-database-clock.md` — relative dates
- `backend/cauce-api/src/test/java/dev/cauce/api/AbstractBusinessApiIT.java` — driving a turn in-process
