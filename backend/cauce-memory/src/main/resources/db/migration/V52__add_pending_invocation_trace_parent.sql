-- V52: the trace of the request that brought a message in, kept with the invocation that answers it
-- (ADR 0011, T2). trace_parent is the W3C traceparent of the ingest's span (55 characters); the worker
-- resumes it, so a message's input, orchestration and reply are one trace. Null when nothing was
-- being traced. An id, never content.
ALTER TABLE pending_invocations ADD COLUMN trace_parent VARCHAR(55);
