# ADR 0005: Hardening of the agentic tool loop

Status: Accepted (not yet implemented)

## Context

The bounded agentic loop exists and works: the orchestrator offers tools to the model,
executes the calls it returns, feeds the results back and repeats until a final reply or a
hard cap of ten rounds (`OrchestratorService`). Context assembly already carries the tool
roles, both LLM adapters map tool calls and results in both directions, and the tool
messages are persisted and readable over the API.

What it lacks are its edges, and they matter the moment an agent talks to someone on a real
channel rather than to a developer polling the API:

- A final reply with no text and no tool calls cannot be persisted (`Message` rejects blank
  content). The invocation fails as a generic setup error, with no trace in the conversation.
- The context window is cut by size alone. A cut can leave a tool result without its call,
  or start the window on an assistant turn, both of which providers reject.
- The iteration cap is per attempt. A retry after a provider failure starts counting from
  zero, so the real bound per inbound message is the cap times the attempt budget.
- A tool that throws feeds its exception message back to the model verbatim.
- When the invocation fails, the person on the channel receives nothing at all.
- Every registered tool is offered to every agent.
- Tool calls and results are persisted, but the audit chain records only the final reply.

## Decision

One unit, six pieces, in this order: C1, C2, C6, C3, C4, C5.

### C1 — An empty reply is a failure

A final reply with no text is a failure of the invocation, never a message. Nothing empty is
ever sent to a channel. It is recorded as an orchestration error (the internal SYSTEM message,
as for any other failure) with its own failure type, and it then follows C3.

### C2 — The context window keeps tool turns whole

The window is still cut by size, newest first, but it always starts at a user turn. Within the
window everything is contiguous, so a tool call is never separated from its results and no
assistant turn opens the window. If the current turn itself cannot fit from its user message
onwards, that is the existing "too large for the context" failure, not a silent truncation.

### C6 — The cap is per inbound message; tool errors are sanitized

The iteration cap bounds the whole response to one inbound message, across retries: rounds
already persisted for the trigger message count against it. The cap is a platform setting
with a default. Reaching it follows C3.

A tool that fails returns to the model as an error result whose text is sanitized: no
exception message, no stack trace, no internal detail. The full error goes to the log only.

### C3 — The interlocutor gets a fallback, never internals

On any terminal failure (LLM, empty reply, cap) the person who wrote receives a fallback
text, configurable per agent, with a platform default. It never carries internal detail.
Internally the SYSTEM error message is recorded as today.

### C4 — Tools are granted per agent, empty by default

Each agent has an explicit list of the tools it may use. The list is empty by default, not
even the clock. Every name is validated against the tool registry when the list is set. The
model is offered only the agent's tools, and a call to a tool outside the list is an error
result, like an unknown tool.

### C5 — Every tool call and result is audited

Each tool call and its result is an audited event in the chain: tool name, call id, duration,
outcome, and a hash of the arguments and of the output. The content never enters in clear.
This is the same rule as ADR 0004's PC-3: audit payloads carry opaque ids, metadata and
hashes, so that erasing the business rows never touches the chain.

## Consequences

- A new failure type for the empty reply. The failure-type column has no CHECK, so no
  migration is needed for it.
- C2 can turn a window that fit before into an explicit context failure, when the current
  turn alone exceeds the window. This is preferred to sending a malformed history.
- C3 needs a per-agent column plus a platform property. C4 needs per-agent storage and a
  write path. Both are migrations.
- C4 breaks every existing agent's tool access, deliberately: after it lands, an agent uses
  no tools until it is granted some. The quickstart, which demonstrates the clock tool, must
  grant it explicitly.
- C5 adds two audit event types and one audit write per tool call and per tool result, on
  the hot path of the loop.

## Notes

The criteria above were set before implementation began, in reply to the diagnosis of the
loop. This record is their home in the repository.

## References

- `backend/cauce-orchestration/src/main/java/dev/cauce/orchestration/service/OrchestratorService.java` — the loop
- `backend/cauce-orchestration/src/main/java/dev/cauce/orchestration/context/ContextBuilder.java` — context windowing
- `backend/cauce-orchestration/src/main/java/dev/cauce/orchestration/worker/PendingInvocationWorker.java` — failure handling and retries
- `backend/cauce-tools/src/main/java/dev/cauce/tools/spi/ToolRegistry.java` — the global registry C4 scopes
- `docs/adr/0004-identity-message-parts-and-channel-capabilities.md` — PC-3, the rule C5 applies
