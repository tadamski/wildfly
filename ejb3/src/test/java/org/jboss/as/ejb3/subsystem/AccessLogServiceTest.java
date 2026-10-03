/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import org.jboss.as.ejb3.component.AccessLogHolder;
import org.junit.Test;
import org.wildfly.event.logger.Event;
import org.wildfly.event.logger.EventLogger;
import org.wildfly.event.logger.EventWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Verifies the {@code events-dropped} accounting and the stop-time drain added for
 * WFLY-6892.
 *
 * <p>Checks:
 * <ol>
 *   <li>Every emit failure recorded by the interceptor is counted.</li>
 *   <li>{@code events-dropped} is the sum of emit failures and the async logger's own
 *       queue-overflow count.</li>
 *   <li>{@link AccessLogService#stop} drains events still queued in the async logger to
 *       the writer instead of discarding them.</li>
 *   <li>{@code events-dropped} does not fall back after stop, when the logger reference
 *       the count was read from is gone.</li>
 * </ol>
 */
public class AccessLogServiceTest {

    /** Matches {@code AsyncEventLogger.QUEUE_CAPACITY}, which is not visible from here. */
    private static final int QUEUE_CAPACITY = 1024;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * An {@link AccessLogService} that is never started: the test installs the logger
     * directly, because {@link AccessLogService#start} would need a real
     * {@code XnioWorker}.
     */
    private static AccessLogService newService(final AccessLogHolder holder) {
        return new AccessLogService(
                service -> { },                     // serviceConsumer
                () -> holder,                       // holderSupplier
                null,                               // worker — start() is never called
                null,                               // pathManager — ditto
                "console",                          // destination
                null, null, null,                   // path, relative-to, rotate-suffix
                false,                              // fileAttrsExplicitlySet
                AccessLogService.ALL_ATTRIBUTES,
                true,                               // includeLocal
                false,                              // includeNodeName
                null);                              // metadata
    }

    /** Collects everything written to it, so the test can see what a drain produced. */
    private static final class RecordingWriter implements EventWriter {
        private final List<Event> written = new ArrayList<>();
        private boolean closed;

        @Override
        public void write(final Event event) {
            written.add(event);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Builds an async logger whose executor never runs the drain task, so the queue fills
     * deterministically: the first {@link #QUEUE_CAPACITY} events are held and every
     * further event is dropped.
     */
    private static EventLogger stalledLogger(final EventWriter writer) {
        final Executor neverRuns = command -> { };
        return EventLogger.createAsyncLogger(AccessLogService.EVENT_SOURCE, writer, neverRuns);
    }

    private static void logEvents(final EventLogger logger, final int count) {
        for (int i = 0; i < count; i++) {
            logger.log(Collections.singletonMap("i", (Object) i));
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    public void everyEmitFailureIsCounted() {
        final AccessLogService service = newService(new AccessLogHolder());
        assertEquals(0L, service.getEventsDropped());

        service.recordEmitFailure(new IllegalStateException("boom 1"));
        service.recordEmitFailure(new IllegalStateException("boom 2"));
        service.recordEmitFailure(new IllegalStateException("boom 3"));

        // Only the first is logged at ERROR, but all three are counted.
        assertEquals(3L, service.getEventsDropped());
    }

    @Test
    public void eventsDroppedSumsEmitFailuresAndQueueOverflow() {
        final AccessLogService service = newService(new AccessLogHolder());
        final RecordingWriter writer = new RecordingWriter();
        final EventLogger logger = stalledLogger(writer);
        service.setEventLogger(logger);

        final int overflow = 76;
        logEvents(logger, QUEUE_CAPACITY + overflow);
        assertEquals(overflow, logger.getDroppedCount());

        service.recordEmitFailure(new IllegalStateException("boom"));

        assertEquals(overflow + 1L, service.getEventsDropped());
    }

    @Test
    public void stopDrainsQueuedEventsToTheWriter() {
        final AccessLogHolder holder = new AccessLogHolder();
        final AccessLogService service = newService(holder);
        final RecordingWriter writer = new RecordingWriter();
        final EventLogger logger = stalledLogger(writer);
        service.setEventLogger(logger);
        holder.set(service);

        logEvents(logger, QUEUE_CAPACITY);
        assertEquals("nothing should have been written yet", 0, writer.written.size());

        service.stop(null);

        assertEquals("queued events must be drained, not discarded",
                QUEUE_CAPACITY, writer.written.size());
        assertNull("the holder must be cleared", holder.get());
        assertNull("the logger reference must be released", service.getEventLogger());
    }

    @Test
    public void eventsDroppedSurvivesStop() {
        final AccessLogService service = newService(new AccessLogHolder());
        final RecordingWriter writer = new RecordingWriter();
        final EventLogger logger = stalledLogger(writer);
        service.setEventLogger(logger);

        final int overflow = 50;
        logEvents(logger, QUEUE_CAPACITY + overflow);
        service.recordEmitFailure(new IllegalStateException("boom"));

        final long beforeStop = service.getEventsDropped();
        assertEquals(overflow + 1L, beforeStop);

        service.stop(null);

        // The logger is gone, but its share of the count was folded into the local
        // counter rather than silently disappearing from the metric.
        assertEquals(beforeStop, service.getEventsDropped());
    }

    @Test
    public void stopIsSafeWithNoLogger() {
        final AccessLogHolder holder = new AccessLogHolder();
        final AccessLogService service = newService(holder);
        holder.set(service);

        service.recordEmitFailure(new IllegalStateException("boom"));
        service.stop(null);

        assertEquals(1L, service.getEventsDropped());
        assertNull(holder.get());
    }
}
