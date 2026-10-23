package dev.cauce.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import dev.cauce.core.LogKeys;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class OtlpLogExportTest {

    private final LoggerContext context = new LoggerContext();
    private final InMemoryLogRecordExporter exported = InMemoryLogRecordExporter.create();
    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setLoggerProvider(SdkLoggerProvider.builder()
                    .addLogRecordProcessor(SimpleLogRecordProcessor.create(exported)).build())
            .build();

    @BeforeEach
    void startContext() {
        context.setMDCAdapter(MDC.getMDCAdapter()); // the application's context has one; a bare one does not
        context.start();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        openTelemetry.close();
        context.stop();
    }

    @Test
    void install_exportsEachLine_withTheIdsOfItsLoggingContext() {
        OtlpLogExport.install(context, openTelemetry);
        Logger logger = context.getLogger("dev.cauce.orchestration.worker");

        MDC.put(LogKeys.INVOCATION_ID, "0192f000-0000-7000-8000-000000000001");
        MDC.put("unlisted", "never exported");
        logger.warn("Invocation failed: LlmProviderUnavailableException[provider=anthropic]");

        assertThat(exported.getFinishedLogRecordItems()).singleElement().satisfies(record -> {
            LogRecordData data = record;
            assertThat(data.getBodyValue().asString()).contains("LlmProviderUnavailableException");
            assertThat(data.getAttributes().get(AttributeKey.stringKey(LogKeys.INVOCATION_ID)))
                    .isEqualTo("0192f000-0000-7000-8000-000000000001");
            assertThat(data.getAttributes().get(AttributeKey.stringKey("unlisted"))).isNull();
        });
    }

    @Test
    void install_twice_attachesOneAppender() {
        OtlpLogExport.install(context, openTelemetry);
        OtlpLogExport.install(context, openTelemetry);

        context.getLogger("dev.cauce").info("once");

        assertThat(exported.getFinishedLogRecordItems()).hasSize(1);
    }
}
