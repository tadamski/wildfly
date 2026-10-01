/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.ejb3.logging.EjbLogger;
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

    /** All 13 tokens enabled — used when the model attribute is UNDEFINED. */
    static final Set<AccessLogResourceDefinition.AttributeVocabulary> ALL_ATTRIBUTES =
            EnumSet.allOf(AccessLogResourceDefinition.AttributeVocabulary.class);

    private final Consumer<AccessLogService> serviceConsumer;
    private final Supplier<XnioWorker> worker;
    private final Supplier<PathManager> pathManager;

    // Configuration — immutable (RESTART_RESOURCE_SERVICES attributes)
    private final String destination;
    /** Relative file path component (e.g. "ejb-access.log"). May be null only when destination != file. */
    private final String path;
    /** Base path name passed to PathManager (e.g. "jboss.server.log.dir"). Null means treat path as absolute. */
    private final String relativeTo;
    private final String rotateSuffix;
    /**
     * True when the operator explicitly supplied at least one of path/relative-to/rotate-suffix.
     * Used to detect the expression-destination case at service start.
     */
    private final boolean fileAttrsExplicitlySet;
    /** Enabled log fields; defaults to all tokens when the model attribute is UNDEFINED. */
    private final Set<AccessLogResourceDefinition.AttributeVocabulary> enabledAttributes;

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
            final Supplier<PathManager> pathManager,
            final String destination,
            final String path,
            final String relativeTo,
            final String rotateSuffix,
            final boolean fileAttrsExplicitlySet,
            final Set<AccessLogResourceDefinition.AttributeVocabulary> enabledAttributes,
            final boolean includeLocal,
            final boolean includeNodeName,
            final java.util.Map<String, Object> metadata) {
        this.serviceConsumer = serviceConsumer;
        this.worker = worker;
        this.pathManager = pathManager;
        this.destination = destination;
        this.path = path;
        this.relativeTo = relativeTo;
        this.rotateSuffix = rotateSuffix;
        this.fileAttrsExplicitlySet = fileAttrsExplicitlySet;
        this.enabledAttributes = enabledAttributes;
        this.includeLocal = includeLocal;
        this.includeNodeName = includeNodeName;
        this.metadata = metadata;
    }

    @Override
    public void start(final StartContext context) throws StartException {
        final JsonEventFormatter.Builder formatterBuilder = JsonEventFormatter.builder();
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

    /** Returns the set of enabled log field tokens. Never null. */
    public Set<AccessLogResourceDefinition.AttributeVocabulary> getEnabledAttributes() {
        return enabledAttributes;
    }

    /** Returns true if local (in-VM) invocations should be logged. */
    public boolean isIncludeLocal() {
        return includeLocal;
    }

    /** Returns true if the node name should be included in every record. */
    public boolean isIncludeNodeName() {
        return includeNodeName;
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

    private EventWriter buildWriter(final JsonEventFormatter formatter) throws IOException, StartException {
        switch (destination) {
            case "console":
                warnFileAttributesIfSet(destination);
                return StdoutEventWriter.of(formatter);
            case "logging":
                warnFileAttributesIfSet(destination);
                return LoggerEventWriter.of(LOG_CATEGORY, formatter);
            case "file":
            default: {
                final String resolved = pathManager.get().resolveRelativePathEntry(path, relativeTo);
                final Path filePath = Paths.get(resolved);
                if (filePath.getParent() != null) {
                    java.nio.file.Files.createDirectories(filePath.getParent());
                }
                return FileEventWriter.open(filePath, formatter, rotateSuffix);
            }
        }
    }

    /**
     * Fails the service start if file-only attributes were explicitly supplied alongside a
     * non-file destination. This catches the expression-destination case that model-time
     * validation cannot see because the destination value was an expression.
     */
    private void warnFileAttributesIfSet(final String resolvedDestination) throws StartException {
        if (fileAttrsExplicitlySet) {
            throw EjbLogger.ROOT_LOGGER.fileAttributesIgnoredForNonFileDestination(resolvedDestination);
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
