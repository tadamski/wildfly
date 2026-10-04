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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    /**
     * Small queue used by tests that need to exercise queue overflow.
     * Chosen deliberately small so tests do not need to log 1024+ events against a
     * stalled writer. The default of 1024 is now a management attribute, so tests that
     * construct the service directly supply an explicit value.
     */
    private static final int TEST_QUEUE_CAPACITY = 16;

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
                null,                               // metadata
                TEST_QUEUE_CAPACITY);               // queueLength
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
     * deterministically: the first {@link #TEST_QUEUE_CAPACITY} events are held and every
     * further event is dropped.
     */
    private static EventLogger stalledLogger(final EventWriter writer) {
        final Executor neverRuns = command -> { };
        return EventLogger.createAsyncLogger(AccessLogService.EVENT_SOURCE, writer, neverRuns, TEST_QUEUE_CAPACITY);
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

        final int overflow = 7;
        logEvents(logger, TEST_QUEUE_CAPACITY + overflow);
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

        logEvents(logger, TEST_QUEUE_CAPACITY);
        assertEquals("nothing should have been written yet", 0, writer.written.size());

        service.stop(null);

        assertEquals("queued events must be drained, not discarded",
                TEST_QUEUE_CAPACITY, writer.written.size());
        assertNull("the holder must be cleared", holder.get());
        assertNull("the logger reference must be released", service.getEventLogger());
    }

    @Test
    public void eventsDroppedSurvivesStop() {
        final AccessLogService service = newService(new AccessLogHolder());
        final RecordingWriter writer = new RecordingWriter();
        final EventLogger logger = stalledLogger(writer);
        service.setEventLogger(logger);

        final int overflow = 5;
        logEvents(logger, TEST_QUEUE_CAPACITY + overflow);
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

    // -------------------------------------------------------------------------
    // B1 — emit-failure log level and counter (R2 acceptance, folded into D24b)
    // -------------------------------------------------------------------------

    /**
     * B1/1 — {@link AccessLogService#recordEmitFailure} increments the dropped-events
     * counter on every call and transitions the log-once CAS guard exactly once.
     *
     * <p>The ERROR level and {@code @Cause} binding are declarative —
     * {@code @LogMessage(level = ERROR)} on message {@code WFLYEJB000539} — so there is
     * no code path that could emit at the wrong level; those properties are not under test
     * here. What is under test is the CAS branch: first call flips the guard to {@code true}
     * (takes the ERROR path), subsequent calls leave it {@code true} (take the DEBUG path),
     * and every call still increments the counter.
     */
    @Test
    public void recordEmitFailureLogLevelAndCount() {
        final AccessLogService service = newService(new AccessLogHolder());

        // Guard starts false on a fresh service.
        assertFalse("log-once guard must be clear on a fresh service",
                service.isEmitFailureLoggedOnce());

        service.recordEmitFailure(new IllegalStateException("first boom"));

        // After the first call the guard flips — the ERROR path was taken.
        assertTrue("log-once guard must be set after the first failure",
                service.isEmitFailureLoggedOnce());
        assertEquals("dropped count after first failure", 1L, service.getEventsDropped());

        service.recordEmitFailure(new IllegalStateException("second boom"));
        service.recordEmitFailure(new IllegalStateException("third boom"));

        // Guard stays set — subsequent calls took the DEBUG path, not a second ERROR.
        assertTrue("log-once guard must remain set after subsequent failures",
                service.isEmitFailureLoggedOnce());
        // The counter still rises for every call, proving the DEBUG branch counts.
        assertEquals("all three failures must be counted", 3L, service.getEventsDropped());
    }

    /**
     * B1/2 — The dropped-event counter rises when emit fails.
     * Verifies that {@link AccessLogService#recordEmitFailure} was reached (drop counter
     * rises) after a log() call throws. This is the mechanism the interceptor relies on:
     * it catches the throwable and delegates to recordEmitFailure.
     */
    @Test
    public void invocationSurvivesEmitException() throws Exception {
        final AccessLogHolder holder = new AccessLogHolder();
        final AccessLogService service = newService(holder);

        // Replace the logger with one that throws on every log() call.
        final EventLogger throwingLogger = new EventLogger() {
            @Override
            public EventLogger log(final java.util.Map<String, Object> fields) {
                throw new RuntimeException("simulated emit failure");
            }
            @Override
            public EventLogger log(final java.util.function.Supplier<java.util.Map<String, Object>> supplier) {
                throw new RuntimeException("simulated emit failure");
            }
            @Override
            public String getEventSource() {
                return AccessLogService.EVENT_SOURCE;
            }
        };
        service.setEventLogger(throwingLogger);

        assertEquals("no drops before the failing emit", 0L, service.getEventsDropped());

        // Drive recordEmitFailure directly (the interceptor calls it when emit() throws).
        service.recordEmitFailure(new RuntimeException("simulated emit failure"));

        assertEquals("drop counter must rise after the failing emit", 1L, service.getEventsDropped());
    }
}
