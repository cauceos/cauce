package dev.cauce.api.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import dev.cauce.api.web.RequestIdFilter;
import dev.cauce.core.LogKeys;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends the application's log lines over OTLP as well as to the console (ADR 0011, T1), when an
 * endpoint is configured ({@code management.otlp.logging.endpoint}); without one nothing is installed.
 * Each record carries the ids of the logging context ({@link #MDC_KEYS}) and, inside a trace, its
 * trace and span ids, so a line leads to its message's trace. The lines are the console's: what
 * L2 keeps out of the console stays out of here.
 */
@Component
@ConditionalOnProperty("management.otlp.logging.endpoint")
public class OtlpLogExport {

    static final String APPENDER_NAME = "OTLP";

    /** The logging-context keys a record carries: ids only. */
    static final List<String> MDC_KEYS = List.of(LogKeys.TENANT_ID, LogKeys.AGENT_ID, LogKeys.CONVERSATION_ID,
            LogKeys.INVOCATION_ID, RequestIdFilter.REQUEST_ID_MDC_KEY);

    public OtlpLogExport(OpenTelemetry openTelemetry) {
        install((LoggerContext) LoggerFactory.getILoggerFactory(), openTelemetry);
    }

    /** Attaches the OTLP appender to {@code context}'s root logger, bound to {@code openTelemetry}. */
    static void install(LoggerContext context, OpenTelemetry openTelemetry) {
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) != null) {
            return;
        }
        OpenTelemetryAppender appender = new OpenTelemetryAppender();
        appender.setName(APPENDER_NAME);
        appender.setContext(context);
        appender.setCaptureMdcAttributes(String.join(",", MDC_KEYS));
        appender.setOpenTelemetry(openTelemetry);
        appender.start();
        root.addAppender(appender);
    }
}
