/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.event.logger.Event;
import org.wildfly.event.logger.JsonEventFormatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link FileEventWriter} and {@link LoggerEventWriter}.
 *
 * <p>These tests exercise the writers directly — no MSC context, no subsystem boot.
 *
 * <p>Coverage:
 * <ul>
 *   <li>F2/W1 — {@link FileEventWriter}: writes a JSON record to a file</li>
 *   <li>F2/W2 — {@link FileEventWriter}: rotation on day change (forced via reflection)</li>
 *   <li>F2/W3 — {@link FileEventWriter}: no rotation when {@code rotateSuffix} is empty</li>
 *   <li>F2/W4 — {@link LoggerEventWriter}: passes the formatted JSON string to the named logger</li>
 * </ul>
 */
public class AccessLogWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Event makeEvent(final Map<String, Object> data) {
        return new Event() {
            @Override
            public String getSource() {
                return "ejb-access";
            }

            @Override
            public Instant getInstant() {
                return Instant.EPOCH;
            }

            @Override
            public Map<String, Object> getData() {
                return data;
            }
        };
    }

    private static JsonEventFormatter plainFormatter() {
        return JsonEventFormatter.builder().build();
    }

    // -------------------------------------------------------------------------
    // F2/W1 — FileEventWriter writes a record
    // -------------------------------------------------------------------------

    @Test
    public void w1_fileWriter_writesJsonRecord() throws Exception {
        final Path file = tmp.newFile("ejb-access.log").toPath();
        final JsonEventFormatter formatter = plainFormatter();

        try (FileEventWriter writer = FileEventWriter.open(file, formatter, "")) {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("bean", "OrderBean");
            data.put("method", "placeOrder(String)");
            data.put("outcome", "success");
            data.put("duration", 5L);
            writer.write(makeEvent(data));
        }

        final List<String> lines = Files.readAllLines(file);
        assertEquals("expected exactly one line", 1, lines.size());

        final JsonObject obj = Json.createReader(new StringReader(lines.get(0))).readObject();
        assertEquals("OrderBean",          obj.getString("bean"));
        assertEquals("placeOrder(String)", obj.getString("method"));
        assertEquals("success",            obj.getString("outcome"));
        assertEquals(5L, obj.getJsonNumber("duration").longValue());
        // JsonEventFormatter always prepends eventSource and timestamp
        assertEquals("ejb-access", obj.getString("eventSource"));
        assertNotNull(obj.getString("timestamp"));
    }

    // -------------------------------------------------------------------------
    // F2/W2 — FileEventWriter rotates on day change
    // -------------------------------------------------------------------------

    @Test
    public void w2_fileWriter_rotatesOnDayChange() throws Exception {
        final Path file = tmp.newFile("access.log").toPath();
        final JsonEventFormatter formatter = plainFormatter();
        final String rotateSuffix = ".yyyy-MM-dd";

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("bean", "RotateBean");
        data.put("outcome", "success");
        data.put("duration", 1L);

        try (FileEventWriter writer = FileEventWriter.open(file, formatter, rotateSuffix)) {
            // Write a first record — base file exists
            writer.write(makeEvent(data));
            assertTrue("base file should exist after first write", Files.exists(file));

            // Force currentDate to yesterday via reflection so rotation fires on next write
            final java.lang.reflect.Field dateField = FileEventWriter.class.getDeclaredField("currentDate");
            dateField.setAccessible(true);
            final LocalDate yesterday = LocalDate.now().minusDays(1);
            dateField.set(writer, yesterday);

            // Write a second record — rotation should fire
            writer.write(makeEvent(data));

            // Rotated file: base.log.yyyy-MM-dd (yesterday's date)
            final java.time.format.DateTimeFormatter suffixFmt =
                    java.time.format.DateTimeFormatter.ofPattern(rotateSuffix);
            final Path rotated = file.resolveSibling(file.getFileName() + suffixFmt.format(yesterday));

            assertTrue("rotated file should exist: " + rotated, Files.exists(rotated));
            assertTrue("base file should still exist after rotation", Files.exists(file));

            // Rotated file contains first record; base file contains second record
            final List<String> rotatedLines = Files.readAllLines(rotated);
            final List<String> baseLines = Files.readAllLines(file);
            assertEquals("rotated file should have 1 line", 1, rotatedLines.size());
            assertEquals("base file should have 1 line",    1, baseLines.size());
        }
    }

    // -------------------------------------------------------------------------
    // F2/W3 — FileEventWriter does not rotate when rotateSuffix is empty
    // -------------------------------------------------------------------------

    @Test
    public void w3_fileWriter_noRotationWhenSuffixEmpty() throws Exception {
        final Path file = tmp.newFile("norate.log").toPath();
        final JsonEventFormatter formatter = plainFormatter();

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("bean", "Bean");
        data.put("outcome", "success");
        data.put("duration", 0L);

        try (FileEventWriter writer = FileEventWriter.open(file, formatter, "")) {
            writer.write(makeEvent(data));

            // Force currentDate to yesterday — even so, no rotation should occur
            final java.lang.reflect.Field dateField = FileEventWriter.class.getDeclaredField("currentDate");
            dateField.setAccessible(true);
            dateField.set(writer, LocalDate.now().minusDays(1));

            writer.write(makeEvent(data));
        }

        final List<String> lines = Files.readAllLines(file);
        assertEquals("both records should be in the base file — no rotation", 2, lines.size());

        // No sibling files should have been created
        final long siblings = Files.list(tmp.getRoot().toPath())
                .filter(p -> !p.equals(file))
                .count();
        assertEquals("no rotation file should exist", 0, siblings);
    }

    // -------------------------------------------------------------------------
    // F2/W4 — LoggerEventWriter routes formatted JSON to the named logger
    // -------------------------------------------------------------------------

    @Test
    public void w4_loggerWriter_passesFormattedLineToNamedLogger() {
        final String category = "org.jboss.as.ejb3.access-log.test." + System.nanoTime();
        final JsonEventFormatter formatter = plainFormatter();
        final LoggerEventWriter writer = LoggerEventWriter.of(category, formatter);

        // Attach a capturing handler to the JUL logger for this category
        final Logger jul = Logger.getLogger(category);
        final CapturingHandler handler = new CapturingHandler();
        jul.addHandler(handler);
        jul.setUseParentHandlers(false);
        jul.setLevel(Level.ALL);

        try {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("bean", "LogBean");
            data.put("outcome", "success");
            data.put("duration", 2L);
            writer.write(makeEvent(data));
        } finally {
            jul.removeHandler(handler);
        }

        assertEquals("expected exactly one log record", 1, handler.records.size());
        final String message = handler.records.get(0).getMessage();
        assertNotNull("log record message must not be null", message);

        // The message should be valid JSON containing the bean field
        final JsonObject obj = Json.createReader(new StringReader(message)).readObject();
        assertEquals("LogBean",  obj.getString("bean"));
        assertEquals("success",  obj.getString("outcome"));
        assertEquals(Level.INFO, handler.records.get(0).getLevel());

        // close() must not throw
        try {
            writer.close();
        } catch (Exception e) {
            throw new AssertionError("LoggerEventWriter.close() must not throw", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helper handler
    // -------------------------------------------------------------------------

    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord r) {
            records.add(r);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
