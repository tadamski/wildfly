/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jboss.msc.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.wildfly.event.logger.EventLogger;
import org.wildfly.event.logger.EventWriter;
import org.wildfly.event.logger.JsonEventFormatter;
import org.wildfly.event.logger.StdoutEventWriter;
import org.xnio.XnioWorker;

/**
 * MSC service that owns the {@link EventLogger} for EJB access logging.
 *
 * <p>On {@link #start}: selects the {@link EventWriter} from the {@code destination}
 * attribute ({@code console} → {@link StdoutEventWriter}, {@code logging} →
 * {@link LoggerEventWriter}, {@code file} → {@link FileEventWriter}), wraps it in an
 * async {@link EventLogger} backed by the XNIO {@code worker}, and publishes the logger
 * for the interceptor (E3) to read.
 *
 * <p>On {@link #stop}: nulls the published logger and closes the writer cleanly.
 *
 * <p>{@link #getEventsLogged()} and {@link #getEventsDropped()} expose the runtime
 * metrics registered in {@link AccessLogResourceDefinition}.  {@code events-dropped}
 * reads zero until D20 (AsyncEventLogger queue bound) lands in wildfly-core.
 */
public class AccessLogService implements Service {

    static final String LOG_CATEGORY = "org.jboss.as.ejb3.access-log";
    static final String EVENT_SOURCE = "ejb-access";

    private final Consumer<AccessLogService> serviceConsumer;
    private final Supplier<XnioWorker> worker;

    // Configuration — immutable (RESTART_RESOURCE_SERVICES attributes)
    private final String destination;
    private final String path;
    private final String rotateSuffix;

    // Live-mutable configuration (RESTART_NONE attributes)
    private volatile boolean includeLocal;
    private volatile boolean includeNodeName;
    private volatile java.util.Map<String, Object> metadata;

    // Mutable state published for the interceptor
    private volatile EventLogger eventLogger;

    // Metrics
    private final AtomicLong eventsLogged = new AtomicLong();
    private final AtomicLong eventsDropped = new AtomicLong();

    // The writer to close on stop
    private EventWriter activeWriter;

    AccessLogService(
            final Consumer<AccessLogService> serviceConsumer,
            final Supplier<XnioWorker> worker,
            final String destination,
            final String path,
            final String rotateSuffix,
            final boolean includeLocal,
            final boolean includeNodeName,
            final java.util.Map<String, Object> metadata) {
        this.serviceConsumer = serviceConsumer;
        this.worker = worker;
        this.destination = destination;
        this.path = path;
        this.includeLocal = includeLocal;
        this.rotateSuffix = rotateSuffix;
        this.includeNodeName = includeNodeName;
        this.metadata = metadata;
    }

    @Override
    public void start(final StartContext context) throws StartException {
        final JsonEventFormatter.Builder formatterBuilder = JsonEventFormatter.builder();
        if (includeNodeName) {
            // node-name is injected as formatter meta-data by the interceptor (E3);
            // the server environment is not available here without an additional
            // service dependency.  Leave a placeholder — E3 will populate it.
            // TODO E3: inject ServerEnvironment.getNodeName() via metadata.
        }
        if (metadata != null && !metadata.isEmpty()) {
            formatterBuilder.addMetaData(metadata);
        }
        final JsonEventFormatter formatter = formatterBuilder.build();

        final EventWriter writer;
        try {
            writer = buildWriter(formatter);
        } catch (IOException e) {
            throw new StartException("Failed to open access-log writer for destination '" + destination + "'", e);
        }
        this.activeWriter = writer;

        // Wrap with a counting writer so we can report events-logged.
        final EventWriter countingWriter = new CountingEventWriter(writer, eventsLogged);

        this.eventLogger = EventLogger.createAsyncLogger(EVENT_SOURCE, countingWriter, worker.get());
        AccessLogResourceDefinition.LIVE_SERVICE = this;
        serviceConsumer.accept(this);
    }

    @Override
    public void stop(final StopContext context) {
        AccessLogResourceDefinition.LIVE_SERVICE = null;
        serviceConsumer.accept(null);
        this.eventLogger = null;
        final EventWriter w = this.activeWriter;
        this.activeWriter = null;
        if (w != null) {
            try {
                w.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /**
     * Returns the live {@link EventLogger}, or {@code null} if the service is not running.
     * The interceptor (E3) reads this field on every invocation.
     */
    public EventLogger getEventLogger() {
        return eventLogger;
    }

    /** Sets include-local flag (live-mutable, RESTART_NONE). */
    public void setIncludeLocal(final boolean includeLocal) {
        this.includeLocal = includeLocal;
    }

    /** Sets include-node-name flag (live-mutable, RESTART_NONE). */
    public void setIncludeNodeName(final boolean includeNodeName) {
        this.includeNodeName = includeNodeName;
    }

    /** Sets metadata map (live-mutable, RESTART_NONE). */
    public void setMetadata(final java.util.Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /** Running total of events handed to the writer. */
    public long getEventsLogged() {
        return eventsLogged.get();
    }

    /**
     * Running total of events dropped due to queue overflow.
     * Reads zero until D20 (AsyncEventLogger queue bound) lands in wildfly-core.
     */
    public long getEventsDropped() {
        return eventsDropped.get();
    }

    // -------------------------------------------------------------------------

    private EventWriter buildWriter(final JsonEventFormatter formatter) throws IOException {
        switch (destination) {
            case "console":
                return StdoutEventWriter.of(formatter);
            case "logging":
                return LoggerEventWriter.of(LOG_CATEGORY, formatter);
            case "file":
            default:
                // E5 will resolve relative-to via PathManager; for now use the path as-is.
                final Path filePath = Paths.get(path);
                if (filePath.getParent() != null) {
                    java.nio.file.Files.createDirectories(filePath.getParent());
                }
                return FileEventWriter.open(filePath, formatter, rotateSuffix);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Thin wrapper that increments a counter on each successful write.
     * Stays in ejb3; H2 does not need to lift it.
     */
    private static final class CountingEventWriter implements EventWriter {
        private final EventWriter delegate;
        private final AtomicLong counter;

        CountingEventWriter(final EventWriter delegate, final AtomicLong counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public void write(final org.wildfly.event.logger.Event event) {
            delegate.write(event);
            counter.incrementAndGet();
        }

        @Override
        public void close() throws Exception {
            delegate.close();
        }
    }
}
