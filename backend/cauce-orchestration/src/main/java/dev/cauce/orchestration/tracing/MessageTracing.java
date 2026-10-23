package dev.cauce.orchestration.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The trace of a message, end to end (ADR 0011, T2, T3). The request that brings a message in is a
 * trace already (the HTTP server's); {@link #currentTraceParent()} takes its W3C {@code traceparent}
 * so the pending invocation can carry it, and {@link #continueTrace} resumes it on the worker, and on
 * the thread that delivers the reply, so the whole path is one trace.
 *
 * <p>A span is tagged only with the keys of {@link #ALLOWED_KEYS}: ids and closed vocabularies,
 * never content. An unknown key is a programming error. An error is recorded by its type, never its
 * message. Without a tracer on the classpath (tests of a single module) every span is a no-op.
 */
@Component
public class MessageTracing {

    /** ADR 0011, T3: what a Cauce span may say. */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "tenant_id", "agent_id", "conversation_id", "invocation_id", "message_id", "channel_config_id",
            "provider", "model", "tool", "channel_type", "round",
            "input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens", "finish_reason",
            "outcome", "failure_type", "error.type");

    private static final String TRACEPARENT = "traceparent";
    private static final TracedSpan NOOP_SPAN = new NoopSpan();

    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public MessageTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this(tracer.getIfAvailable(), propagator.getIfAvailable());
    }

    private MessageTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** A tracing that records nothing: for tests that build the services by hand. */
    public static MessageTracing noop() {
        return new MessageTracing((Tracer) null, null);
    }

    /** The W3C {@code traceparent} of the current span, if a trace is being recorded. */
    public Optional<String> currentTraceParent() {
        if (tracer == null || propagator == null) {
            return Optional.empty();
        }
        Span current = tracer.currentSpan();
        if (current == null) {
            return Optional.empty();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return Optional.ofNullable(carrier.get(TRACEPARENT));
    }

    /**
     * A span named {@code name}, the child of the trace {@code traceParent} names (a new trace when
     * it is null), current until it is closed.
     */
    public TracedSpan continueTrace(String traceParent, String name) {
        if (tracer == null || propagator == null) {
            return NOOP_SPAN;
        }
        Span.Builder builder = traceParent == null ? tracer.spanBuilder()
                : propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get);
        return new LiveSpan(tracer, builder.name(name).start());
    }

    /** A span named {@code name}, the child of the current one, current until it is closed. */
    public TracedSpan childSpan(String name) {
        if (tracer == null) {
            return NOOP_SPAN;
        }
        return new LiveSpan(tracer, tracer.nextSpan().name(name).start());
    }

    /** A span in scope; close it to end it. */
    public interface TracedSpan extends AutoCloseable {

        /** Tags the span; {@code key} must be one of {@link #ALLOWED_KEYS}. */
        TracedSpan tag(String key, String value);

        TracedSpan tag(String key, long value);

        /** Marks the span failed by {@code e}'s type; its message is never recorded. */
        void error(Throwable e);

        @Override
        void close();
    }

    private static void requireAllowed(String key) {
        if (!ALLOWED_KEYS.contains(key)) {
            throw new IllegalArgumentException("Span attribute '" + key + "' is not allowed (ADR 0011, T3)");
        }
    }

    private static final class LiveSpan implements TracedSpan {

        private final Span span;
        private final Tracer.SpanInScope scope;

        LiveSpan(Tracer tracer, Span span) {
            this.span = span;
            this.scope = tracer.withSpan(span);
        }

        @Override
        public TracedSpan tag(String key, String value) {
            requireAllowed(key);
            if (value != null) {
                span.tag(key, value);
            }
            return this;
        }

        @Override
        public TracedSpan tag(String key, long value) {
            requireAllowed(key);
            span.tag(key, value);
            return this;
        }

        @Override
        public void error(Throwable e) {
            span.tag("outcome", "error");
            span.tag("error.type", e.getClass().getSimpleName());
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }

    private static final class NoopSpan implements TracedSpan {

        @Override
        public TracedSpan tag(String key, String value) {
            requireAllowed(key);
            return this;
        }

        @Override
        public TracedSpan tag(String key, long value) {
            requireAllowed(key);
            return this;
        }

        @Override
        public void error(Throwable e) {
        }

        @Override
        public void close() {
        }
    }
}
