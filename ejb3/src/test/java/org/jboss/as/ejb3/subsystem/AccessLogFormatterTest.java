/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;

import org.junit.Test;
import org.wildfly.event.logger.Event;
import org.wildfly.event.logger.JsonEventFormatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link JsonEventFormatter} produces records whose shape matches the
 * §4 field vocabulary settled for EJB access logging (WFLY-6892).
 *
 * <p>Checks:
 * <ol>
 *   <li>A full record with every field in the default attributes list — names match §4.</li>
 *   <li>A record from a local invocation where several fields are genuinely unavailable —
 *       those keys are <em>absent</em>, not null and not {@code -}.</li>
 *   <li>A record with two metadata properties merged in as top-level keys.</li>
 *   <li>A record produced with a reduced attributes list, showing only requested fields.</li>
 * </ol>
 */
public class AccessLogFormatterTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Formats {@code data} through a {@link JsonEventFormatter} (with optional metadata)
     * and returns the parsed {@link JsonObject}.
     */
    private static JsonObject format(final Map<String, Object> data,
                                     final Map<String, Object> metadata) {
        final JsonEventFormatter.Builder b = JsonEventFormatter.builder();
        if (metadata != null && !metadata.isEmpty()) {
            b.addMetaData(metadata);
        }
        final JsonEventFormatter formatter = b.build();

        // Build a minimal Event that carries the data map (mirrors what
        // AbstractEventLogger.log(Map) does before handing off to the formatter).
        final Event event = new Event() {
            @Override
            public String getSource() {
                return "ejb-access";
            }

            @Override
            public java.time.Instant getInstant() {
                return java.time.Instant.EPOCH;
            }

            @Override
            public Map<String, Object> getData() {
                return data;
            }
        };

        final String json = formatter.format(event);
        assertNotNull("formatter returned null", json);
        return Json.createReader(new java.io.StringReader(json)).readObject();
    }

    private static JsonObject format(final Map<String, Object> data) {
        return format(data, null);
    }

    // -------------------------------------------------------------------------
    // Sample 1 — full record with every §4 field
    // -------------------------------------------------------------------------

    @Test
    public void sample1_fullRecord_allFieldsMatchSection4() {
        final Map<String, Object> data = new LinkedHashMap<>();
        // §4 camelCase JSON names (kebab-case is the management-model token):
        data.put("app",            "my-ear");            // app
        data.put("module",         "my-ejb");            // module
        data.put("bean",           "GreeterBean");       // bean
        data.put("beanClass",      "com.example.GreeterBean"); // bean-class
        data.put("view",           "com.example.Greeter");     // view
        data.put("method",         "greet(String)");    // method
        data.put("user",           "alice");             // user
        data.put("remoteAddress",  "10.0.0.42");        // remote-address
        data.put("remotePort",     49152);               // remote-port
        data.put("localAddress",   "10.0.0.1");         // local-address
        data.put("localPort",      8080);                // local-port
        data.put("protocol",       "remote+http");       // protocol
        data.put("invocationType", "REMOTE");            // invocation-type
        data.put("sessionId",      "DEADBEEF");          // session-id  (SFSB)
        data.put("outcome",        "success");           // outcome
        data.put("duration",       3L);                  // duration
        data.put("threadName",     "default-threads-1"); // thread-name  (was "thread" in C1, renamed)
        data.put("nodeName",       "node1");             // node-name

        final JsonObject obj = format(data);

        // §4 field names — camelCase in JSON, kebab-case in management model.
        // Side-by-side:
        //  management token      JSON key           value
        //  app                   app                "my-ear"
        //  module                module             "my-ejb"
        //  bean                  bean               "GreeterBean"
        //  bean-class            beanClass          "com.example.GreeterBean"
        //  view                  view               "com.example.Greeter"
        //  method                method             "greet(String)"
        //  user                  user               "alice"
        //  remote-address        remoteAddress      "10.0.0.42"
        //  remote-port           remotePort         49152
        //  local-address         localAddress       "10.0.0.1"
        //  local-port            localPort          8080
        //  protocol              protocol           "remote+http"
        //  invocation-type       invocationType     "REMOTE"
        //  session-id            sessionId          "DEADBEEF"
        //  outcome               outcome            "success"
        //  duration              duration           3
        //  thread-name           threadName         "default-threads-1"
        //  node-name             nodeName           "node1"

        assertEquals("my-ear",                       obj.getString("app"));
        assertEquals("my-ejb",                       obj.getString("module"));
        assertEquals("GreeterBean",                  obj.getString("bean"));
        assertEquals("com.example.GreeterBean",      obj.getString("beanClass"));
        assertEquals("com.example.Greeter",          obj.getString("view"));
        assertEquals("greet(String)",                obj.getString("method"));
        assertEquals("alice",                        obj.getString("user"));
        assertEquals("10.0.0.42",                    obj.getString("remoteAddress"));
        assertEquals(49152,                          obj.getInt("remotePort"));
        assertEquals("10.0.0.1",                     obj.getString("localAddress"));
        assertEquals(8080,                           obj.getInt("localPort"));
        assertEquals("remote+http",                  obj.getString("protocol"));
        assertEquals("REMOTE",                       obj.getString("invocationType"));
        assertEquals("DEADBEEF",                     obj.getString("sessionId"));
        assertEquals("success",                      obj.getString("outcome"));
        assertEquals(3L,                             obj.getJsonNumber("duration").longValue());
        assertEquals("default-threads-1",            obj.getString("threadName"));
        assertEquals("node1",                        obj.getString("nodeName"));
    }

    // -------------------------------------------------------------------------
    // Sample 2 — local invocation: network, user, session, app absent
    // -------------------------------------------------------------------------

    @Test
    public void sample2_localInvocation_absentFieldsOmitted() {
        // A local (in-VM) invocation: no Request object, no security identity,
        // no SFSB session, single-module deployment (no distinct app name).
        // The interceptor puts NOTHING in the map for absent fields — they are simply
        // not keyed, not null-keyed.
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("module",         "my-ejb");
        data.put("bean",           "LocalBean");
        data.put("beanClass",      "com.example.LocalBean");
        data.put("view",           "com.example.ILocalBean");
        data.put("method",         "doWork()");
        data.put("invocationType", "LOCAL");
        data.put("outcome",        "success");
        data.put("duration",       1L);
        data.put("threadName",     "default-threads-2");

        final JsonObject obj = format(data);

        // Present fields
        assertTrue(obj.containsKey("module"));
        assertTrue(obj.containsKey("bean"));
        assertTrue(obj.containsKey("invocationType"));
        assertTrue(obj.containsKey("outcome"));
        assertTrue(obj.containsKey("duration"));
        assertTrue(obj.containsKey("threadName"));

        // Absent fields — must NOT appear (not null, not "-")
        assertFalse("app key must be absent",           obj.containsKey("app"));
        assertFalse("user key must be absent",          obj.containsKey("user"));
        assertFalse("remoteAddress key must be absent", obj.containsKey("remoteAddress"));
        assertFalse("remotePort key must be absent",    obj.containsKey("remotePort"));
        assertFalse("localAddress key must be absent",  obj.containsKey("localAddress"));
        assertFalse("localPort key must be absent",     obj.containsKey("localPort"));
        assertFalse("protocol key must be absent",      obj.containsKey("protocol"));
        assertFalse("sessionId key must be absent",     obj.containsKey("sessionId"));
        assertFalse("nodeName key must be absent",      obj.containsKey("nodeName"));
        assertFalse("exception key must be absent",     obj.containsKey("exception"));

        // No null values anywhere
        for (Map.Entry<String, JsonValue> entry : obj.entrySet()) {
            assertFalse("field " + entry.getKey() + " must not be null",
                    entry.getValue() == JsonValue.NULL);
        }
    }

    // -------------------------------------------------------------------------
    // Sample 3 — two metadata properties merged in
    // -------------------------------------------------------------------------

    @Test
    public void sample3_metadataPropertiesMergedIn() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("bean",     "PingBean");
        data.put("method",   "ping()");
        data.put("outcome",  "success");
        data.put("duration", 0L);

        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("@version", "1");
        metadata.put("env",      "staging");

        final JsonObject obj = format(data, metadata);

        // Metadata keys appear as top-level peers of the data fields — merged uniformly.
        assertEquals("1",       obj.getString("@version"));
        assertEquals("staging", obj.getString("env"));

        // Data fields still present
        assertEquals("PingBean", obj.getString("bean"));
        assertEquals("ping()",   obj.getString("method"));
        assertEquals("success",  obj.getString("outcome"));
    }

    // -------------------------------------------------------------------------
    // Sample 4 — reduced attributes list (only outcome, duration, bean)
    // -------------------------------------------------------------------------

    @Test
    public void sample4_reducedAttributesList_onlyRequestedFieldsPresent() {
        // Operator configured: attributes=[bean, outcome, duration]
        // The interceptor only populates those three keys.
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("bean",     "AuditBean");
        data.put("outcome",  "success");
        data.put("duration", 12L);

        final JsonObject obj = format(data);

        // Requested fields are present
        assertEquals("AuditBean", obj.getString("bean"));
        assertEquals("success",   obj.getString("outcome"));
        assertEquals(12L,         obj.getJsonNumber("duration").longValue());

        // All other §4 fields absent — not in the map, not emitted
        assertFalse(obj.containsKey("app"));
        assertFalse(obj.containsKey("module"));
        assertFalse(obj.containsKey("beanClass"));
        assertFalse(obj.containsKey("view"));
        assertFalse(obj.containsKey("method"));
        assertFalse(obj.containsKey("user"));
        assertFalse(obj.containsKey("remoteAddress"));
        assertFalse(obj.containsKey("threadName"));
        assertFalse(obj.containsKey("nodeName"));
    }
}
