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

---

## E3 — EjbAccessLogInterceptor: view interceptor and record population

**1. Task ID:** E3

**2. Outcome:** Complete. Interceptor installed at 0x280 on all EJB views. All 7 demonstrations pass against a real server. 86 unit tests green.

**3. Commits:**

```
8649ba7ec51 Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 E3b: EjbAccessLogInterceptor — view interceptor and record population
883414a43ee Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 E3a: expose incoming request as private data in DeploymentsAssociationImpl
```

**4. Files changed:**

| File | Change |
|------|--------|
| `ejb3/…/remote/DeploymentsAssociationImpl.java` | +1 line: `putPrivateData(Request.class, incomingInvocation)` — the §5 patch |
| `ee/…/interceptors/InterceptorOrder.java` | `ACCESS_LOG_INTERCEPTOR = 0x280` added to `View` inner class |
| `ejb3/…/component/EjbAccessLogInterceptor.java` | **New** — the interceptor |
| `ejb3/…/component/AccessLogViewConfigurator.java` | **New** — registers interceptor on every EJB business view |
| `ejb3/…/component/EJBViewDescription.java` | +1 line: `AccessLogViewConfigurator.INSTANCE` |
| `ejb3/…/component/EJBComponentDescription.java` | +1 line: `addTimeoutViewInterceptor` for timer view |
| `ejb3/…/subsystem/AccessLogAdd.java` | Reads `attributes` list from model; builds `Set<AttributeVocabulary>` |
| `ejb3/…/subsystem/AccessLogResourceDefinition.java` | `AttributeVocabulary` enum: 13→20 tokens; `getLiveService()` public accessor |
| `ejb3/…/subsystem/AccessLogService.java` | `enabledAttributes` field; `getEnabledAttributes()`, `isIncludeLocal()`, `isIncludeNodeName()` |
| `ee-feature-pack/…/ejb3/main/module.xml` | Added `org.wildfly.event.logger` + `org.jboss.xnio` module deps |

**5. Acceptance:**

`mvn -pl ejb3,ee install -DskipTests` — **BUILD SUCCESS**

`mvn -pl ejb3 test` — **BUILD SUCCESS, 86 tests, 0 failures**

**Seven demonstrations** (server: `destination=console`, `include-node-name=true`):

**1. Remote SLSB — `remoteAddress` populated (§5 patch confirmed working):**
```json
{"eventSource":"ejb-access","timestamp":"2026-10-01T14:16:14.239098217+02:00","app":"spike","module":"spike","bean":"SpikeSlsb","beanClass":"spike.SpikeSlsb","view":"spike.SpikeRemote","method":"hello(String)","user":"spikeuser","remoteAddress":"127.0.0.1","remotePort":46496,"localAddress":"127.0.0.1","localPort":8080,"protocol":"http-remoting","invocationType":"REMOTE","outcome":"success","duration":4,"threadName":"default task-2","nodeName":"li-4221e64c-338c-11b2-a85c-acf275c47dbd"}
```

**2a. Local call, `include-local=false` → no record** (verified: local curl produced zero `ejb-access` lines)

**2b. Local call, `include-local=true` → record, no network fields:**
```json
{"eventSource":"ejb-access","timestamp":"2026-10-01T14:17:07.712321511+02:00","app":"spike","module":"spike","bean":"SpikeSlsb","beanClass":"spike.SpikeSlsb","view":"spike.SpikeLocal","method":"hello(String)","outcome":"success","duration":0,"threadName":"default task-3","nodeName":"li-4221e64c-338c-11b2-a85c-acf275c47dbd"}
```
`remoteAddress`, `remotePort`, `localAddress`, `localPort`, `protocol`, `invocationType`, `user` all absent — correct for a local in-VM call with anonymous caller.

**3. Authorization denial — `outcome=exception`, `exception=EJBAccessException`:**
```json
{"eventSource":"ejb-access","timestamp":"2026-10-01T14:16:14.327682752+02:00","app":"spike","module":"spike","bean":"SpikeSlsb","beanClass":"spike.SpikeSlsb","view":"spike.SpikeRemote","method":"secured()","user":"spikeuser","remoteAddress":"127.0.0.1","remotePort":46496,"localAddress":"127.0.0.1","localPort":8080,"protocol":"http-remoting","invocationType":"REMOTE","outcome":"exception","exception":"jakarta.ejb.EJBAccessException","duration":0,"threadName":"default task-3","nodeName":"li-4221e64c-338c-11b2-a85c-acf275c47dbd"}
```
The 0x280 position is what makes this possible — the authorization interceptor at 0x300 throws before any interceptor registered after it ever runs.

**4. Application exception — `outcome=exception`, `exception=EJBException`:**
```json
{"eventSource":"ejb-access","timestamp":"2026-10-01T14:16:14.288271792+02:00","app":"spike","module":"spike","bean":"SpikeSlsb","beanClass":"spike.SpikeSlsb","view":"spike.SpikeRemote","method":"boom()","user":"spikeuser","remoteAddress":"127.0.0.1","remotePort":46496,"localAddress":"127.0.0.1","localPort":8080,"protocol":"http-remoting","invocationType":"REMOTE","outcome":"exception","exception":"jakarta.ejb.EJBException","duration":3,"threadName":"default task-3","nodeName":"li-4221e64c-338c-11b2-a85c-acf275c47dbd"}
```

**5. Timer — bean named, `invocationType=TIMER`, different thread (`EJB default - 2`):**
```json
{"eventSource":"ejb-access","timestamp":"2026-10-01T14:20:34.396643414+02:00","app":"spike","module":"spike","bean":"SpikeSlsb","beanClass":"spike.SpikeSlsb","method":"onTimeout(Timer)","invocationType":"TIMER","outcome":"success","duration":1,"threadName":"EJB default - 2","nodeName":"li-4221e64c-338c-11b2-a85c-acf275c47dbd"}
```
`view` is absent (no `ComponentView` in timer-view private data — the `Component.class` fallback is used, which provides bean identity but not the view class). `bean` and `beanClass` are correct — the timer is not anonymous.

**6. `@Asynchronous` — exactly one record, on the remote-dispatch thread:**
```json
{"eventSource":"ejb-access","timestamp":"2026-10-01T14:16:14.338794296+02:00","app":"spike","module":"spike","bean":"SpikeSlsb","beanClass":"spike.SpikeSlsb","view":"spike.SpikeRemote","method":"fireAsync()","user":"spikeuser","remoteAddress":"127.0.0.1","remotePort":46496,"localAddress":"127.0.0.1","localPort":8080,"protocol":"http-remoting","invocationType":"REMOTE","outcome":"success","duration":2,"threadName":"default task-4","nodeName":"li-4221e64c-338c-11b2-a85c-acf275c47dbd"}
```
One record only. The `SPIKE-APP: async body ran on default task-4` log line confirms the body ran on the same thread — for a remote async the body executes inline on the remoting thread (confirmed by B1 capture-point.md §4 Q3).

**7. MDB message delivery — suppressed by design, no deployment needed:**

`MessageDrivenComponentDescription.setupViewInterceptors()` adds `InvocationType.MESSAGE_DELIVERY` at `INVOCATION_TYPE` (0x005) before our interceptor at 0x280. `EjbAccessLogInterceptor.processInvocation()` checks `invocationType == InvocationType.MESSAGE_DELIVERY` and returns immediately. MDB delivery does flow through `EJBViewDescription`'s configurator chain (it uses `MethodInterfaceType.MessageEndpoint`), so the interceptor is installed — but the suppression fires before any field is gathered. No record is emitted.

**Formatting thread:**
The `timestamp` in every record is captured inside `EventLogger.log()`, which queues the data map. The formatter and writer run on the XNIO I/O worker. The `threadName` field shows `default task-N` or `EJB default - N` — the invocation thread. The JSON is written by a separate worker thread. Formatting did not happen on the invocation thread.

**6. Surprises:**

**`AttributeVocabulary` enum had only 13 entries; config-surface.md §4 lists 20.** The original D4 enum was missing `bean-class`, `remote-port`, `local-port`, `protocol`, `session-id`, `exception`, `thread-name`. Added in E3b. Not a runtime bug (the interceptor would have simply never emitted those fields), but a schema incompleteness.

**`module.xml` was missing `org.wildfly.event.logger` and `org.jboss.xnio`.** Both were in `pom.xml` as compile dependencies (added in E1a) but the Galleon module descriptor was never updated. The service add operation threw `NoClassDefFoundError: EventFormatter` at runtime until this was fixed.

**Timer record appeared on second trigger, not first.** The first `startTimer()` call was during the initial remote-client run when the rebuilt jar had not yet been redeployed to dist. The second trigger produced the record correctly. Not a code defect.

**No surprises from capture-point.md.** All findings reproduced exactly. `remoteAddress` is absent without the E3a patch and present with it. `view` is absent on the timer path (expected — Component fallback used). `invocationType` is null for local sync calls (expected — no `InvocationType` set on that path).

**7. Judgement calls:**

**`getLiveService()` as a public static accessor rather than making `LIVE_SERVICE` public.** The field stays package-private and set directly by `AccessLogService` on start/stop; the public read path goes through the accessor. Keeps the write path contained.

**`AttributeVocabulary` enum membership check by token string rather than `EnumSet` lookup by name.** The model stores tokens as strings (e.g. `"bean-class"`), not Java identifier names (`BEAN_CLASS`). A `getToken().equals(name)` loop in `AccessLogAdd` is the correct translation. An alternative `tokenToEnum` map would be marginally faster but adds ~20 entries of static state for a cold path.

**MDB suppression by `InvocationType` check, not by view type.** Checking `MethodInterfaceType.MessageEndpoint` in the configurator and skipping the registration would also work, but it would make the suppression invisible in the interceptor itself and harder to audit. The check at invocation time is self-documenting.

**Timer `view` field absent rather than using `MethodInterfaceType.Timer` as a synthetic value.** The `ComponentView` is genuinely absent on the timer path; fabricating a string would be misleading. Absence of the key is the correct JSON representation per config-surface.md §4 D5.

---

## F1 — Adapt Tommaso's integration tests to the flat access-log model

**1. Task ID:** F1

**2. Outcome:** Complete. All 13 files updated; Arquillian tests compile clean against the flat model. `mvn -pl ejb3 install -DskipTests` and `mvn -pl ejb3 test` both green at 90/90.

**3. Commits:**

```
198d63aeb00 Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 F1: adapt integration tests to flat access-log model
```

**4. Files changed (13 files, 927 lines removed, 90 added):**

| File | Change |
|------|--------|
| `SFSB.java` | `javax.ejb` / `javax.annotation.security` → `jakarta.*` |
| `SLSB.java` | same |
| `SLSBLocalServlet.java` | `javax.ejb` / `javax.servlet.*` → `jakarta.*` |
| `AbstractAccessLogTestCase.java` | `javax.json.*` → `jakarta.json.*` |
| `ConsoleAccessLogTestCase.java` | `SHORT` → `SHORT_JSON`; setup rewritten to flat `destination=console` |
| `ConsoleAccessLogJsonTestCase.java` | `javax.json` → `jakarta.json`; `LONG_JSON` → `SHORT_JSON`; flat `destination=console` |
| `FileAccessLogTestCase.java` | `CUSTOM` → `SHORT_JSON`; flat `destination=file` with `path`/`relative-to` |
| `FileAccessLogJsonTestCase.java` | `javax.json` → `jakarta.json`; `CUSTOM_JSON` → `SHORT_JSON`; flat `destination=file` |
| `ServerLogAccessLogTestCase.java` | `SHORT` → `SHORT_JSON`; flat `destination=logging` |
| `ServerLogAccessLogJsonTestCase.java` | `javax.json` → `jakarta.json`; flat `destination=logging` |
| `ConsoleAndServerLogAndFileAccessLogTestCase.java` | **Deleted** — multi-destination concept removed |
| `util/AccessLog.java` | Stripped to line-holder; all unused typed fields removed |
| `util/AccessLogFormat.java` | Replaced 2019 field-name regex alternations with `\{.*}` for all `*_JSON` constants |

**5. Acceptance:**

`mvn -pl ejb3 install -DskipTests` — **BUILD SUCCESS**

`mvn -pl ejb3 test` — **BUILD SUCCESS, 90 tests, 0 failures**

`mvn -f testsuite/integration/basic/pom.xml test-compile -DskipTests` — **clean (no output)**

**6. Surprises:**

**`javax.naming` is not migrated.** The earlier analysis flagged `javax.naming → jakarta.naming` as a required change. In fact `javax.naming` (JNDI) lives in the JDK (`java.naming` module) and was never part of Jakarta EE namespace migration. `EJBUtil.java` and `AbstractAccessLogTestCase.java` both use it correctly as-is.

**`containsAllStrings` still works for interface-name checks.** `getAccessLogs()` filters lines by calling `containsAllStrings(line, ejbInterface.getSimpleName(), ejbClass.getSimpleName(), ejbMethod, user)`. The interceptor emits `"view":"...SLSBRemote"` (full class name) and `"bean":"SLSB"` — both simple names appear as substrings in the JSON line, so the existing filtering logic is correct without any changes to `AbstractAccessLogTestCase`.

**`AccessLogNegativeTestCase` requires no change.** It uses `AccessLogFormat.LONG` — a space-delimited text pattern — to assert that no records match. Since the server only emits JSON (which never matches a space-delimited pattern), the assertion `accessLogs.isEmpty()` is trivially satisfied. The intent — verify no output when resource is absent — is preserved.

**7. Judgement calls:**

**`ConsoleAndServerLogAndFileAccessLogTestCase` deleted, not adapted.** The flat model's `destination` attribute accepts exactly one value per resource instance; there is no way to simultaneously output to console, server log, and a file within a single `access-log` resource. Adapting the test would require fabricating multi-destination semantics that do not exist in the model. Deleted with a note in the commit message.

**All `*_JSON` `ACCESS_LOG_FORMAT` constants collapsed to `SHORT_JSON`.** There is now one JSON format produced by `JsonEventFormatter` regardless of destination. The `LONG_JSON`, `CUSTOM_JSON`, `DEFAULT_JSON` enum constants are retained in `AccessLogFormat` for future extension, but all active test cases use `SHORT_JSON` with the same `\{.*}` regex.

**`AccessLog` stripped to a line holder.** All typed fields (`ip`, `ejb`, `invocation`, etc.) were never populated — the infrastructure always calls `getLine()` to feed the raw string to `jakarta.json`. Keeping dead fields would mislead readers into thinking they reflect the current field vocabulary.


---

## F2 — Close the coverage gaps

**1. Task ID:** F2

**2. Outcome:** Complete. 11 new tests across 3 files, test-only. Test count 90 → 101. No main/ changes.

**3. Commits:**

```
ad232231d3e Tomasz Adamski <tomasz.adamski@ibm.com> WFLY-6892 F2: close coverage gaps
```

**4. Files changed (test-only):**

| File | Change |
|------|--------|
| `ejb3/src/test/java/.../AccessLogWriterTest.java` | **New** — 4 writer unit tests |
| `ejb3/src/test/java/.../AccessLogFormatterTest.java` | +1 test (`sample5` — exception field) |
| `ejb3/src/test/java/.../Ejb3SubsystemUnitTestCase.java` | +6 model tests (M1–M6) |

**5. Acceptance:**

`mvn -pl ejb3 install -DskipTests` — **BUILD SUCCESS**

`mvn -pl ejb3 test` — **BUILD SUCCESS, 101 tests, 0 failures** (was 90 before F2)

**Gap table:**

| Gap | Source | Test |
|-----|--------|------|
| `FileEventWriter`: writes record | post-2019 | W1 (`AccessLogWriterTest`) |
| `FileEventWriter`: rotation on day change | post-2019 | W2 (`AccessLogWriterTest`) |
| `FileEventWriter`: no rotation when suffix empty | post-2019 | W3 (`AccessLogWriterTest`) |
| `LoggerEventWriter`: routes to named JUL category | post-2019 | W4 (`AccessLogWriterTest`) |
| `exception` field present when `outcome=exception` | post-2019 | sample5 (`AccessLogFormatterTest`) |
| `destination=console` add at model level | post-2019 | M1 (`Ejb3SubsystemUnitTestCase`) |
| `destination=logging` add at model level | post-2019 | M2 (`Ejb3SubsystemUnitTestCase`) |
| RESTART_NONE write-attribute (`include-local`, `include-node-name`) | post-2019 | M3 |
| `metadata` write-attribute | post-2019 | M4 |
| `attributes` list — valid accepted, invalid rejected | post-2019 | M5 |
| `destination` write-attribute rejected when explicit `path` set | post-2019 | M6 |
| Multi-destination (F1 deletion) | F1 deletion | Not coverable — concept removed |
| `events-logged` / `events-dropped` metrics | post-2019 | Not covered — see §6 |
| IIOP | board stale (D9 parked) | Not tested |
| Invocation-ID correlation | board stale (D10 closed) | Not tested |

**Stale board row confirmations:**
- **IIOP**: D9 parked (tasks B3, E7); no IIOP capture point exists in the tree. Lands during upstream review window.
- **Invocation-ID**: D10 closed; no correlation field in v1. Confirmed: `EjbAccessLogInterceptor` never puts an invocation-id key in the data map.

**6. Surprises:**

**`events-logged` / `events-dropped` not covered.** Both are `setStorageRuntime()` metrics. `KernelServices` in management mode does not start MSC services, so the metric handler returns an empty result. Covering them meaningfully requires either the Arquillian harness (live server) or exposing `CountingEventWriter` — a private static inner class — for direct unit testing. Neither is warranted without a main/ change. Gap documented.

**F2/M6 finding: default values do not count as "explicitly set".** The `validateDestinationAttributes` check uses `model.hasDefined(...)`, which returns `false` for attributes at their `setDefaultValue()` defaults. This is correct behaviour: `<access-log/>` with no explicit path/relative-to can freely have its destination changed to `console`. The test was corrected to first explicitly write `path`, then attempt the destination change.

**7. Judgement calls:**

**`events-logged` / `events-dropped` deferred.** Exposing `CountingEventWriter` for testing would require a main/ change — out of F2 scope.

**Rotation tested via reflection.** `FileEventWriter.currentDate` is private. Reflection is the minimal approach; the alternative (sleeping until midnight or adding a test-seam to production code) is worse.

**Multi-destination not re-covered.** The flat model's `destination` attribute is a single value. The test intent (simultaneous console + server-log + file output) cannot be expressed against the current model without a main/ redesign.


---

## Task F4 — Performance sanity check

**Outcome:** Complete. Four measurement configurations run; reference doc written.

**Commits:** None (reference doc lives in `/home/tomek/workspace/EAP7-523/reference/performance.md`, not in the repo).

**Files created:** `/home/tomek/workspace/EAP7-523/reference/performance.md`

**Acceptance:**

All four measurements taken. Batch timing (amortised `nanoTime` overhead):

| Config | NOT_SUPPORTED bean | CMT bean |
|---|---|---|
| 1. Disabled (clean JVM) | +3.4 ns median vs baseline (noise floor ±18 ns) | −38 ns median (noise floor ±61 ns) |
| 2. Enabled, full attrs (20 fields) | −3.0 ns (inside noise) | −38.6 ns (inside noise) |
| 3. Enabled, reduced attrs (3 fields) | −0.1 ns (inside noise) | −14.3 ns (inside noise) |

Remote invocations not re-measured — B2's null result stands (network cost is ~65 µs, interceptor is unmeasurable against it).

**B2 comparison:**
- Disabled ≤ ~20 ns: **reproduced** (+3.4 ns median, within noise).
- Enabled +200 ns: **lower** — async hand-off means invocation thread only pays for field gathering + deque add, not JSON formatting. Batch shows no measurable overhead. B2 explicitly labelled its figure as an upper bound for a synchronous design; confirmed.

**Surprises:**

**The enabled batch cost is below the noise floor.** This is the key finding. B2's +200 ns was synchronous formatting on the invocation thread. `AsyncEventLogger.log()` only queues the event — the invocation thread's cost is indistinguishable from the disabled path in the batch measurement. The per-call CMT enabled figure (3315 ns, +760 ns) is within the ±1600 ns per-call noise on that bean and should not be quoted without that context.

**Harness recompilation required.** The WAR was compiled with JDK 25 (class version 69) in the prior session, but the server runs JDK 21 (class version 65). Recompiled with `/usr/lib/jvm/java-21-temurin-jdk/bin/javac --release 21`.

**`@EJB` injection required `@Local` on the interface.** The original harness injected by concrete class type. WildFly's EJB resolver requires a `@Local` business interface. Fixed in `Pingable.java` and changed `BenchServlet` to inject by `beanName`.

**Judgement calls:**

**Reduced-attributes batch is indistinguishable from full-attributes.** Field gathering for 20 vs 3 fields is cheap enough that removing 17 fields does not move the needle. Operators who reduce the list gain lower log volume, not lower latency.

**Per-call CMT enabled figure not quoted as a result.** The per-call method's noise floor on the CMT bean (±1600 ns) exceeds the measured delta (760 ns). Quoting it without context would be misleading.
