package dev.cauce.orchestration.worker;

import dev.cauce.core.UuidGenerator;
import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stable identifier for this worker process, written to
 * {@code pending_invocations.claimed_by} whenever the worker claims a row.
 *
 * <p>Format: {@code <hostname>:<pid>:<uuid8>} where {@code uuid8} is the first 8 chars of a
 * UUIDv7 generated at startup. The hostname and PID make it easy to find the owning
 * machine and process when investigating a stuck claim; the UUID suffix disambiguates
 * multiple workers on the same host, and avoids collisions if a process is restarted with
 * the same PID. The whole string fits within {@code claimed_by VARCHAR(255)} with a wide
 * margin.
 *
 * <p>Singleton: generated once at construction and never changes for the lifetime of the
 * process.
 */
@Component
public class WorkerIdentity {

    private static final Logger log = LoggerFactory.getLogger(WorkerIdentity.class);
    private static final int UUID_SUFFIX_LENGTH = 8;
    private static final String UNKNOWN_HOSTNAME = "unknown";

    private final String id;

    public WorkerIdentity() {
        this.id = "%s:%d:%s".formatted(resolveHostname(), resolvePid(), shortUuid());
    }

    @PostConstruct
    void logIdentity() {
        log.info("Worker identity initialized: {}", id);
    }

    public String getId() {
        return id;
    }

    private static String resolveHostname() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return (name == null || name.isBlank()) ? UNKNOWN_HOSTNAME : name;
        } catch (UnknownHostException e) {
            return UNKNOWN_HOSTNAME;
        }
    }

    private static long resolvePid() {
        return ProcessHandle.current().pid();
    }

    private static String shortUuid() {
        return UuidGenerator.newV7().toString().substring(0, UUID_SUFFIX_LENGTH);
    }
}
