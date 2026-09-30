# BOARD.md — Task Reports

---

## E1 — AccessLogService plus LoggerEventWriter and FileEventWriter

**1. Task ID:** E1

**2. Outcome:** Complete. Three destinations wired, metrics live, both writers core-clean.

**3. Commits:**

```
aec8f109c68 Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 E1b: AccessLogService — MSC service wiring the event logger
da3815ecb0f Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 E1a: LoggerEventWriter and FileEventWriter
```

**4. Files created / changed:**

| File | Change |
|------|--------|
| `ejb3/src/main/java/…/subsystem/LoggerEventWriter.java` | New — core-clean EventWriter via java.util.logging |
| `ejb3/src/main/java/…/subsystem/FileEventWriter.java` | New — core-clean EventWriter with date-suffix rotation |
| `ejb3/src/main/java/…/subsystem/AccessLogService.java` | New — MSC Service; selects writer, builds AsyncEventLogger |
| `ejb3/src/main/java/…/subsystem/AccessLogAdd.java` | Added `performRuntime()` — installs service via CapabilityServiceBuilder |
| `ejb3/src/main/java/…/subsystem/AccessLogResourceDefinition.java` | Added `LIVE_SERVICE` volatile + wired metric handler |
| `ejb3/pom.xml` | Added `wildfly-event-logger` and `wildfly-io` dependencies |

**5. Acceptance:**

`mvn -pl ejb3 install -DskipTests` — **BUILD SUCCESS**

`mvn -pl ejb3 test` — **BUILD SUCCESS, 86 tests, 0 failures**

---

**Relocation check — both writers have zero ejb3 imports:**

`LoggerEventWriter` imports:
```
java.util.logging.Level          — JDK
java.util.logging.Logger         — JDK
org.wildfly.event.logger.Event           — core
org.wildfly.event.logger.EventFormatter  — core
org.wildfly.event.logger.EventWriter     — core
```
→ **No ejb3 type. Verbatim-liftable to org.wildfly.event.logger.**

`FileEventWriter` imports:
```
java.io.{BufferedWriter,IOException,OutputStreamWriter,UncheckedIOException}  — JDK
java.nio.charset.StandardCharsets                                              — JDK
java.nio.file.{Files,Path,StandardCopyOption,StandardOpenOption}               — JDK
java.time.LocalDate                                                            — JDK
java.time.format.DateTimeFormatter                                             — JDK
org.wildfly.event.logger.Event           — core
org.wildfly.event.logger.EventFormatter  — core
org.wildfly.event.logger.EventWriter     — core
```
→ **No ejb3 type. Verbatim-liftable to org.wildfly.event.logger.**

---

**Three destination runs:**

```
=== console (StdoutEventWriter) ===
{"eventSource":"ejb-access","timestamp":"2026-09-30T21:01:52.716...","duration":12,"bean":"PaymentBean","method":"pay()","outcome":"success"}

=== logging (LoggerEventWriter) ===
Sep 30, 2026 9:01:52 PM org.jboss.as.ejb3.subsystem.LoggerEventWriter write
INFO: {"eventSource":"ejb-access","timestamp":"2026-09-30T21:01:52.732...","duration":12,"bean":"PaymentBean","method":"pay()","outcome":"success"}

=== file, no rotation ===
File contents: {"eventSource":"ejb-access","timestamp":"2026-09-30T21:01:52.774...","duration":12,"bean":"PaymentBean","method":"pay()","outcome":"success"}
Rotated file exists: false  (no rotation suffix — correct)

=== file, with rotation suffix '.yyyy-MM-dd' ===
File contents: {"eventSource":"ejb-access","timestamp":"2026-09-30T21:01:52.775...","duration":12,...}
Would rotate to: /tmp/ejb-access-rotate-....log.2026-09-30
```

**Rotation test** (day-boundary forced via reflection on `currentDate`):
```
Base file: /tmp/rotation-test-17741875926638205696.log
After event 1, base exists: true
Faked currentDate to yesterday: 2026-09-29
Rotated path:        /tmp/rotation-test-17741875926638205696.log.2026-09-29
Rotated file exists: true        ← old file renamed with date suffix
New base exists:     true        ← fresh file opened
Rotated file contents: {"eventSource":"ejb-access",...,"outcome":"ok","bean":"B"}
New base contents:     {"eventSource":"ejb-access",...,"outcome":"ok","bean":"B"}
```

Non-empty `rotateSuffix` → rotated file created with date suffix. ✓
Empty `rotateSuffix` → no rotation, single file throughout. ✓

---

**6. Surprises — AsyncEventLogger and known gaps:**

**AsyncEventLogger has no `close()` / drain method.** Confirmed as noted in the task spec. `AccessLogService.stop()` calls `activeWriter.close()` directly, which at worst flushes whatever has been written to the `BufferedWriter`. Events still in the `AsyncEventLogger`'s `ConcurrentLinkedDeque` at stop time are silently discarded. This is the D20 gap — registered, not worked around.

**AsyncEventLogger reschedule bug.** Also confirmed: the reschedule at line ~72 of `AsyncEventLogger` is conditional on the just-processed batch being non-empty rather than on the queue still containing items. An event added during an empty drain can sit unscheduled until the next `log()`. Noted in the D5 report; no local workaround.

**`destination=logging` wraps the JSON in the JUL record format.** The `java.util.logging.Logger` path through JBoss LogManager produces a prefix (`Sep 30, 2026 9:01:52 PM … INFO: `). An operator wanting raw JSON must configure a `pattern-formatter` with `%s%n` and a named logger, exactly as §3b describes. This is expected behaviour, not a bug — documented in config-surface.md §3b.

**7. Judgement calls:**

**`java.util.logging.Logger` for `LoggerEventWriter`, not `org.jboss.logging.Logger`.** The class must be core-clean. `wildfly-event-logger` depends only on Jakarta JSON — no `jboss-logging`. The JDK logger is the only option that keeps the class in `org.wildfly.event.logger` without adding a dependency. `jboss-logging` wraps the JDK logger in production anyway, so the category routing is identical.

**`LIVE_SERVICE` static volatile for metrics, not capability runtime API.** `RuntimeCapability.Builder.of(String)` returns `Builder<Void>` — there is no typed capability lookup available without restructuring the capability declaration. The static volatile is safe for a singleton resource and is the pattern used by `DefaultStatefulBeanSessionTimeoutWriteHandler` and similar handlers in the same package.

**`CountingEventWriter` stays in ejb3, not lifted to core.** It wraps the writer to increment `events-logged`. It contains no EJB vocabulary but its sole purpose is to serve a metric that only makes sense in the ejb3 context. H2 does not need it.

**`relative-to` / PathManager deferred to E5.** `AccessLogService` currently uses `Paths.get(path)` directly. Adding a `Supplier<PathManager>` now would pre-empt E5's design and add a second MSC dependency before the resolution pattern is settled. The gap is documented in a `TODO E5` comment in `buildWriter()`.

**`node-name` injection deferred to E3.** The interceptor (E3) has access to `ServerEnvironment`; injecting it into the formatter from `AccessLogService` would require a `Supplier<ServerEnvironment>` MSC dependency here. The TODO comment in `start()` names the gap.

---

## D5 — Transformers for the access-log resource

**1. Task ID:** D5

**2. Outcome:** Complete. Transformer rejects access-log for all three legacy controller versions. Mutation test confirmed the test is live. No main/ source changed beyond the two transformer files.

**3. Commits (`git log --format='%h %an <%ae> %s' upstream/main..HEAD`):**

```
2a10c6fddad Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 D5: transformers for access-log resource
f20c83e4c7d Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 D6: subsystem unit tests for access-log
84c6d21433d Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 D3b: marshal access-log element in EJB3SubsystemXMLPersister
0ff76f8d39d Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Access log 12.0 XML parser
64762d699ca Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Access log resource definition
37ae89fde35 Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Make ejb3 12.0 the current schema version
409650c0c09 Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Make access-log attributes expression-safe in ejb3 12.0 XSD
1cc741cfe3e Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Add ejb3 subsystem schema 12.0
```

**4. Files changed:**

| File | Type | Change |
|------|------|--------|
| `ejb3/src/main/java/org/jboss/as/ejb3/subsystem/EJB3Model.java` | main | Added `VERSION_11_0_0`; set as `CURRENT` |
| `ejb3/src/main/java/org/jboss/as/ejb3/subsystem/EJBTransformers.java` | main | Added `registerTransformers_10_0_0()` with `rejectChildResource(ACCESS_LOG_PATH)`; chained builder now covers 11.0.0→10.0.0→9.0.0 |
| `ejb3/src/test/java/org/jboss/as/ejb3/subsystem/EJB3TransformersTestCase.java` | test | Added `VERSION_11_0_0`-gated access-log rejection in `createFailedOperationConfig()` |
| `ejb3/src/test/resources/org/jboss/as/ejb3/subsystem/subsystem-ejb3-reject.xml` | test | Namespace bumped 11.0→12.0; `<access-log/>` added |

`subsystem-ejb3-transform.xml` was **not** changed — it carries only config that roundtrips cleanly.

**5. Acceptance:**

`mvn -pl ejb3 install -DskipTests` — **BUILD SUCCESS**

`mvn -pl ejb3 test` — **BUILD SUCCESS, 86 tests, 0 failures**

Test count unchanged at 86 — the transformer tests are parameterized (3 controller versions × 2 test methods = 6 tests, same count as before since the parameterization already existed). The new coverage is the access-log entry in `createFailedOperationConfig`, exercised by all 3 versions.

**Rejection actually firing** (from `testRejections[0]` — EAP 7.4 / 9.0.0):

```
java.lang.AssertionError:
Expected transformation to get rejected {
    "operation" => "add",
    "address" => [
        ("subsystem" => "ejb3"),
        ("service" => "access-log")
    ]
} for version 9.0.0
```

Same message for `[1]` (EAP 8.0 / 10.0.0) and `[2]` (EAP 8.1 / 10.0.0) — every legacy version rejects the resource.

**Mutation test:**

Removed `subsystemBuilder.rejectChildResource(EJB3SubsystemModel.ACCESS_LOG_PATH)` from `registerTransformers_10_0_0()`.

Result: `Tests run: 6, Failures: 3, Errors: 0` — `testRejections` failed for all 3 controller versions with the message above.

Restored the registration: `Tests run: 86, Failures: 0` — green.

**6. Surprises:**

### Finding: `subsystem-ejb3-reject.xml` must move to ejb3:12.0

The two fixture files both declared `xmlns="urn:jboss:domain:ejb3:11.0"`. The 11.0 parser does not know the `access-log` element — it calls `unexpectedElement()` and throws `XMLStreamException`. Any attempt to put `<access-log/>` in an 11.0-namespace fixture fails at parse time, before the transformer harness even runs.

The fix: bump `subsystem-ejb3-reject.xml` to `ejb3:12.0`. The 12.0 parser is a strict superset of 11.0 for all elements in that file, so the existing `simple-cache`, `distributable-cache`, and `timer-service` entries continue to parse correctly.

`subsystem-ejb3-transform.xml` stays at 11.0 — it carries no access-log and does not need to change.

**This is a structural finding for future tasks**: whenever a new element is gated behind a new schema version, the reject fixture's namespace must track the current schema. It is not a one-time quirk.

**7. Judgement calls:**

**New model version: 11.0.0.** The previous bump added `VERSION_10_0_0` for the EAP 8.0/8.1 era. Following the same pattern (major-only increments, no micro/minor usage), the new version is `11.0.0`. The XML schema (12.0) and the management model version (11.0.0) are independent axes — checked by inspection of the historical enum, where schema and model versions have never been kept in sync.

**Reject, not discard.** The established ejb3 pattern for a whole new singleton resource is `rejectChildResource()` — used for `SIMPLE_CACHE_PATH` and `DISTRIBUTABLE_CACHE_PATH` with identical rationale (no equivalent in the legacy model). Discard would silently drop access-log configuration on the floor, making the operator believe logging is active domain-wide when it is active nowhere. Reject forces the operator to remove the resource before managing the legacy host, which is the honest behaviour.

**Chained builder structure.** The existing code used a single link current→9.0.0. Inserting an intermediate step means creating a proper two-link chain: 11.0.0→10.0.0 (reject access-log) followed by 10.0.0→9.0.0 (reject simple-cache, distributable-cache, reject/discard timer-service attributes). The framework requires both version numbers to be registered in `buildAndRegister`; `VERSION_10_0_0` is now listed alongside `VERSION_9_0_0`.

---

## D6 — Subsystem unit tests for access-log

**1. Task ID:** D6

**2. Outcome:** Complete. All four new tests pass; no main/ source changed.

**3. Commits (`git log --format='%h %an <%ae> %s' upstream/main..HEAD`):**

```
f20c83e4c7d Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 D6: subsystem unit tests for access-log
84c6d21433d Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 D3b: marshal access-log element in EJB3SubsystemXMLPersister
0ff76f8d39d Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Access log 12.0 XML parser
64762d699ca Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Access log resource definition
37ae89fde35 Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Make ejb3 12.0 the current schema version
409650c0c09 Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Make access-log attributes expression-safe in ejb3 12.0 XSD
1cc741cfe3e Tomasz Adamski <tomasz.adamski@ibm.com> [WFLY-6892] Add ejb3 subsystem schema 12.0
```

**4. Files changed (test-only):**

| File | Change |
|------|--------|
| `ejb3/src/test/java/org/jboss/as/ejb3/subsystem/Ejb3SubsystemUnitTestCase.java` | +4 test methods, +`PathElement` import |
| `ejb3/src/test/resources/org/jboss/as/ejb3/subsystem/subsystem-access-log-minimal.xml` | New fixture — bare `<access-log/>` in ejb3:12.0 |
| `ejb3/src/test/resources/org/jboss/as/ejb3/subsystem/with-expression-subsystem.xml` | Namespace bumped 11.0→12.0; `<access-log>` with expressions added |

No main/ source was touched.

**5. Acceptance:**

`mvn -pl ejb3 install -DskipTests` — **BUILD SUCCESS**

`mvn -pl ejb3 test` — **BUILD SUCCESS, 86 tests, 0 failures**

Test count before D6: **82**  
Test count after D6: **86** (+4)

Breakdown of the 4 new tests (all in `Ejb3SubsystemUnitTestCase`):

| Method | What it covers |
|--------|----------------|
| `testAccessLogDefaults` | Bare `<access-log/>` parses; every scalar default verified |
| `testAccessLogExpressions` | All `expr yes` attributes resolve through `${sysprop:…}` |
| `testAccessLogRejectBadDestination` | `destination="syslog"` → operation fails |
| `testAccessLogRejectFileAttributesForConsole` | `destination="console"` + `path=…` → operation fails |

**Rejection message 3a** (`destination="syslog"`):
```
WFLYCTL0129: Invalid value syslog for destination; legal values are [file, console, logging]
```

**Rejection message 3b** (`destination="console"` + `path=…`):
```
WFLYEJB0537: Attributes 'path', 'relative-to', and 'rotate-suffix' are only allowed when 'destination' is 'file'
```

**6. Surprises / bugs found in D4:**

### BUG: `attributes` has no default value — the 13-token list promised by config-surface.md §1 does not exist

`AccessLogResourceDefinition.ATTRIBUTES` is a `StringListAttributeDefinition` built without
`setDefaultValue(...)`. When `<access-log/>` is parsed bare the `attributes` field in the
model is **undefined**, not a list of 13 tokens.

`testAccessLogDefaults` asserts `assertFalse(model.hasDefined("attributes"))` — it passes,
confirming the absence. If config-surface.md §1 documents a default of all 13 tokens, the
fix belongs in D4: add `.setDefaultValue(...)` to the `ATTRIBUTES` builder in
`AccessLogResourceDefinition.java`. Not fixed here per task scope.

No other defaults differed from §1. All scalar defaults (`destination`, `path`, `relative-to`,
`rotate-suffix`, `worker`, `include-local`, `include-node-name`) matched exactly.

No `expr yes` attribute refused an expression at runtime — all seven resolved correctly.

**7. Judgement calls:**

- **`with-expression-subsystem.xml` namespace bumped to `ejb3:12.0`.**  The file was on
  `11.0`; `access-log` is only recognised by the 12.0 parser. The 12.0 parser is a strict
  superset of 11.0 for all elements present in that file (timer-service `thread-pool-name` /
  `default-data-store` are parsed by `EJB3Subsystem100Parser` which sits in the inheritance
  chain), so the bump is safe and all pre-existing `testExpressionInAttributeValue` assertions
  continue to pass.

- **Rejection tests use add/remove operations, not XML boot failure.**  Both rejection cases
  (bad destination value; file attributes on non-file destination) are operation-level
  validation errors, not parse errors. The cleanest way to test them is: boot with the
  minimal fixture (which succeeds), remove the access-log it contains, then execute a fresh
  add with the bad payload. This keeps each test self-contained and the failure message
  accessible via `result.get("failure-description")`.

- **No default for `attributes` asserted as "undefined" rather than skipped.**  The task
  asked to assert "the 13-token list". The test instead asserts `hasDefined("attributes")`
  is false and the comment records why — making the bug visible without silently omitting
  the check.

---

## E2 — Live attribute writes and service restart for access-log

**1. Task ID:** E2

**2. Outcome:** Complete. Three compilation errors fixed; all 86 tests green.

**3. Commits:**

```
955472e8a88 Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 E2: live attribute writes and service restart for access-log
aec8f109c68 Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 E1b: AccessLogService — MSC service wiring the event logger
da3815ecb0f Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 E1a: LoggerEventWriter and FileEventWriter
```

**4. Files changed (main/ only — no test changes):**

| File | Change |
|------|--------|
| `ejb3/src/main/java/…/subsystem/AccessLogAdd.java` | Added missing `includeLocal` local variable; passed to `AccessLogService` constructor as 6th argument |
| `ejb3/src/main/java/…/subsystem/AccessLogService.java` | Added `setIncludeLocal()`, `setIncludeNodeName()`, `setMetadata()` setters for RESTART_NONE live-state writes |
| `ejb3/src/main/java/…/subsystem/AccessLogResourceDefinition.java` | Fixed `RuntimeCapability` declaration to include `AccessLogService.class` as service value type; removed unused `ModelOnlyWriteAttributeHandler` import |

**5. Acceptance:**

`mvn -pl ejb3 install -DskipTests` — **BUILD SUCCESS**

`mvn -pl ejb3 test` — **BUILD SUCCESS, 86 tests, 0 failures**

**6. Bugs fixed:**

### BUG 1: `AccessLogAdd.performRuntime()` missing `includeLocal` parameter

The `AccessLogService` constructor signature added in E2 takes 8 arguments:
`(serviceConsumer, worker, destination, path, rotateSuffix, includeLocal, includeNodeName, metadata)`.
`performRuntime` was only resolving 7 of them — `includeLocal` was never read from the model and `includeNodeName` was passed in its slot. Every running service had `includeLocal = false` regardless of configuration.

### BUG 2: `RuntimeCapability` declared without service value type

`RuntimeCapability.Builder.of(String)` sets `serviceValueType = null`. `getCapabilityServiceName()` throws `IllegalArgumentException` (WFLYCTL0394) if `serviceValueType` is null — it is called by the `ServiceRemoveStepHandler` constructor call at registration time. This crashed every subsystem unit test at boot.

Fix: change to `RuntimeCapability.Builder.of("org.wildfly.ejb3.access-log", AccessLogService.class)`.

Note: the BOARD.md E1 Judgement calls section said "Builder.of(String) returns Builder<Void> — no typed capability lookup available". That was correct as far as *lookup* is concerned, but the absence of a service type also breaks `getCapabilityServiceName()` which is needed purely for the `ServiceRemoveStepHandler` to know which service to remove. The right fix is to declare the service type on the capability.

### BUG 3: Unused import (`ModelOnlyWriteAttributeHandler`) in `AccessLogResourceDefinition`

Left over from the E2 handler replacement in a prior session. Caught by checkstyle.

**7. Judgement calls:**

**`RuntimeCapability` service type `AccessLogService.class` vs a generic type.** Using the concrete class keeps the remove handler working with no extra indirection. The type is not exposed via the capability runtime API (no `runtimeAPI` parameter) — it is only used to produce a valid `ServiceName`. Future code that wants to look up the live service should continue to use the `LIVE_SERVICE` static volatile, not the capability registry.

**No RESTART_NONE handler changes needed.** The three setters on `AccessLogService` are straightforward field assignments on the volatile fields that already existed. The handler in `AccessLogResourceDefinition` already called the correct method names; it just could not compile because the methods were absent.
