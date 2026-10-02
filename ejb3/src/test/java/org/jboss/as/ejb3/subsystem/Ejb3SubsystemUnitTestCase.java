/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.ejb3.subsystem;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.subsystem.test.AbstractSubsystemBaseTest;
import org.jboss.as.subsystem.test.AdditionalInitialization;
import org.jboss.as.subsystem.test.KernelServices;
import org.jboss.dmr.ModelNode;
import org.junit.Test;
import org.wildfly.clustering.ejb.timer.TimerManagementProvider;
import org.wildfly.clustering.singleton.service.ServiceTargetFactory;

/**
 * Test case for testing the integrity of the EJB3 subsystem.
 *
 * This checks the following features:
 * - basic subsystem testing (i.e. current model version boots successfully)
 * - registered transformers transform model and operations correctly between different API model versions
 * - expressions appearing in XML configurations are correctly rejected if so required
 * - bad attribute values are correctly rejected
 *
 * @author Emanuel Muckenhuber
 */

public class Ejb3SubsystemUnitTestCase extends AbstractSubsystemBaseTest {

    private static final AdditionalInitialization ADDITIONAL_INITIALIZATION = AdditionalInitialization.withCapabilities(
            RuntimeCapability.resolveCapabilityName(TimerManagementProvider.SERVICE_DESCRIPTOR, "transient"),
            RuntimeCapability.resolveCapabilityName(TimerManagementProvider.SERVICE_DESCRIPTOR, "persistent"),
            ServiceTargetFactory.DEFAULT_SERVICE_DESCRIPTOR.getName()
            );

    public Ejb3SubsystemUnitTestCase() {
        super(EJB3Extension.SUBSYSTEM_NAME, new EJB3Extension());
    }

    @Override
    protected AdditionalInitialization createAdditionalInitialization() {
        return ADDITIONAL_INITIALIZATION;
    }

    @Override
    protected Set<PathAddress> getIgnoredChildResourcesForRemovalTest() {
        Set<PathAddress> ignoredChildren = new HashSet<PathAddress>();
       // ignoredChildren.add(PathAddress.pathAddress(PathElement.pathElement("subsystem", "ejb3"), PathElement.pathElement("passivation-store", "infinispan")));
        return ignoredChildren;
    }

    @Override
    protected String getSubsystemXml() throws IOException {
        return readResource("subsystem.xml");
    }

    @Override
    protected String getSubsystemXsdPath() throws Exception {
        return "schema/wildfly-ejb3_12_0.xsd";
    }

    // -------------------------------------------------------------------------
    // D6: access-log unit tests
    // -------------------------------------------------------------------------

    /**
     * D6/1 — A bare &lt;access-log/&gt; must parse successfully and every attribute
     * in the resulting model must carry its documented default value.
     */
    @Test
    public void testAccessLogDefaults() throws Exception {
        final String subsystemXml = readResource("subsystem-access-log-minimal.xml");
        final KernelServices ks = createKernelServicesBuilder(createAdditionalInitialization())
                .setSubsystemXml(subsystemXml).build();
        assertTrue("Subsystem boot failed: " + ks.getBootError(), ks.isSuccessfulBoot());

        final ModelNode model = ks.readWholeModel()
                .get("subsystem", "ejb3", "service", "access-log");

        // destination default: "file"
        assertEquals("destination default", "file", model.get("destination").asString());

        // path default: "ejb-access.log"
        assertEquals("path default", "ejb-access.log", model.get("path").asString());

        // relative-to default: "jboss.server.log.dir"
        assertEquals("relative-to default", "jboss.server.log.dir", model.get("relative-to").asString());

        // rotate-suffix default: ".yyyy-MM-dd"
        assertEquals("rotate-suffix default", ".yyyy-MM-dd", model.get("rotate-suffix").asString());

        // worker default: "default"
        assertEquals("worker default", "default", model.get("worker").asString());

        // include-local default: false
        assertFalse("include-local default", model.get("include-local").asBoolean());

        // include-node-name default: true
        assertTrue("include-node-name default", model.get("include-node-name").asBoolean());

        // attributes: no default set in AttributeDefinition — must be undefined
        assertFalse("attributes should be undefined when not specified",
                model.hasDefined("attributes"));
    }

    /**
     * D6/2 — Expression-capable attributes in access-log must resolve correctly.
     * Exercises destination, path, relative-to, rotate-suffix, include-local,
     * include-node-name, and metadata (the §1 "expr yes" set).
     * worker and attributes are expr=no and are NOT tested here.
     */
    @Test
    public void testAccessLogExpressions() throws Exception {
        final String subsystemXml = readResource("with-expression-subsystem.xml");
        final KernelServices ks = createKernelServicesBuilder(createAdditionalInitialization())
                .setSubsystemXml(subsystemXml).build();
        assertTrue("Subsystem boot failed: " + ks.getBootError(), ks.isSuccessfulBoot());

        final ModelNode accessLog = ks.readWholeModel()
                .get("subsystem", "ejb3", "service", "access-log");

        assertEquals("file", accessLog.get("destination").resolve().asString());
        assertEquals("ejb-access.log", accessLog.get("path").resolve().asString());
        assertEquals("jboss.server.log.dir", accessLog.get("relative-to").resolve().asString());
        assertEquals(".yyyy-MM-dd", accessLog.get("rotate-suffix").resolve().asString());
        assertTrue("include-local resolved", accessLog.get("include-local").resolve().asBoolean());
        assertFalse("include-node-name resolved", accessLog.get("include-node-name").resolve().asBoolean());

        final ModelNode metadata = accessLog.get("metadata");
        assertTrue("metadata defined", metadata.isDefined());
        assertEquals("prod", metadata.get("env").resolve().asString());
    }

    /**
     * D6/3a — destination="syslog" must be rejected (outside allowed values).
     * The failure message must identify the bad value.
     */
    @Test
    public void testAccessLogRejectBadDestination() throws Exception {
        final String subsystemXml = readResource("subsystem-access-log-minimal.xml");
        final KernelServices ks = createKernelServicesBuilder(createAdditionalInitialization())
                .setSubsystemXml(subsystemXml).build();
        assertTrue("Subsystem boot failed", ks.isSuccessfulBoot());

        // First remove the access-log added by the fixture, then re-add with bad destination
        final PathAddress accessLogAddress = PathAddress.pathAddress(
                PathElement.pathElement("subsystem", "ejb3"),
                PathElement.pathElement("service", "access-log"));

        final ModelNode removeOp = Util.createRemoveOperation(accessLogAddress);
        ModelNode removeResult = ks.executeOperation(removeOp);
        assertEquals("remove should succeed: " + removeResult, "success",
                removeResult.get("outcome").asString());

        final ModelNode addOp = Util.createAddOperation(accessLogAddress);
        addOp.get("destination").set("syslog");

        final ModelNode result = ks.executeOperation(addOp);
        assertEquals("operation should have failed", "failed", result.get("outcome").asString());
        final String failDesc = result.get("failure-description").asString();
        assertTrue("failure should mention 'syslog': " + failDesc, failDesc.contains("syslog"));
        assertTrue("failure should mention 'destination': " + failDesc, failDesc.contains("destination"));
    }

    /**
     * D6/3b — destination="console" together with path=... must be rejected
     * (file-only attributes present for a non-file destination).
     * The failure message must be specific.
     */
    @Test
    public void testAccessLogRejectFileAttributesForConsole() throws Exception {
        final String subsystemXml = readResource("subsystem-access-log-minimal.xml");
        final KernelServices ks = createKernelServicesBuilder(createAdditionalInitialization())
                .setSubsystemXml(subsystemXml).build();
        assertTrue("Subsystem boot failed", ks.isSuccessfulBoot());

        final PathAddress accessLogAddress = PathAddress.pathAddress(
                PathElement.pathElement("subsystem", "ejb3"),
                PathElement.pathElement("service", "access-log"));

        final ModelNode removeOp = Util.createRemoveOperation(accessLogAddress);
        ModelNode removeResult = ks.executeOperation(removeOp);
        assertEquals("remove should succeed: " + removeResult, "success",
                removeResult.get("outcome").asString());

        final ModelNode addOp = Util.createAddOperation(accessLogAddress);
        addOp.get("destination").set("console");
        addOp.get("path").set("something.log");

        final ModelNode result = ks.executeOperation(addOp);
        assertEquals("operation should have failed", "failed", result.get("outcome").asString());
        final String failDesc = result.get("failure-description").asString();
        // EjbLogger message id=537: "Attributes 'path', 'relative-to', and 'rotate-suffix' are only allowed when 'destination' is 'file'"
        assertTrue("failure should mention path/rotate-suffix/relative-to: " + failDesc,
                failDesc.contains("path") || failDesc.contains("relative-to") || failDesc.contains("rotate-suffix"));
        assertTrue("failure should mention destination 'file': " + failDesc,
                failDesc.contains("file"));
    }

    @Test
    public void test11() throws Exception {
        standardSubsystemTest("subsystem11.xml", false);
    }

    @Test
    public void test15() throws Exception {
        standardSubsystemTest("subsystem15.xml", false);
    }

    /** WFLY-7797 */
    @Test
    public void testPoolSizeAlternatives() throws Exception {
        // Parse the subsystem xml and install into the first controller
        final String subsystemXml = getSubsystemXml();
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT).setSubsystemXml(subsystemXml).build();
        assertTrue("Subsystem boot failed!", ks.isSuccessfulBoot());

        PathAddress pa = PathAddress.pathAddress("subsystem", "ejb3").append("strict-max-bean-instance-pool", "slsb-strict-max-pool");

        ModelNode composite = Util.createEmptyOperation("composite", PathAddress.EMPTY_ADDRESS);
        ModelNode steps = composite.get("steps");
        ModelNode writeMax = Util.getWriteAttributeOperation(pa, "max-pool-size", 5);
        ModelNode writeDerive = Util.getWriteAttributeOperation(pa, "derive-size", "none");

        steps.add(writeMax);
        steps.add(writeDerive);

        // none works in combo with max-pool-size
        ModelNode response = ks.executeOperation(composite);
        assertEquals(response.toString(), "success", response.get("outcome").asString());

        validatePoolConfig(ks, pa);

        steps.setEmptyList();

        // Other values fail in combo with max-pool-size
        writeMax.get("value").set(10);
        writeDerive.get("value").set("from-cpu-count");

        steps.add(writeMax);
        steps.add(writeDerive);

        ks.executeForFailure(composite);

        validatePoolConfig(ks, pa);

    }

    @Test
    public void testDefaultPools() throws Exception {
        final String subsystemXml = readResource("subsystem-pools.xml");
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT).setSubsystemXml(subsystemXml).build();
        assertTrue("Subsystem boot failed!", ks.isSuccessfulBoot());

        // add a test pool
        String testPoolName = "test-pool";
        final PathAddress ejb3Address = PathAddress.pathAddress("subsystem", "ejb3");
        PathAddress testPoolAddress = ejb3Address.append("strict-max-bean-instance-pool", testPoolName);
        final ModelNode addPool = Util.createAddOperation(testPoolAddress);
        ModelNode response = ks.executeOperation(addPool);
        assertEquals(response.toString(), "success", response.get("outcome").asString());

        // set default-mdb-instance-pool
        writeAndReadPool(ks, ejb3Address, "default-mdb-instance-pool", testPoolName);
        writeAndReadPool(ks, ejb3Address, "default-mdb-instance-pool", null);
        writeAndReadPool(ks, ejb3Address, "default-mdb-instance-pool", null);
        writeAndReadPool(ks, ejb3Address, "default-mdb-instance-pool", "mdb-strict-max-pool");

        // set default-slsb-instance-pool
        writeAndReadPool(ks, ejb3Address, "default-slsb-instance-pool", null);
        writeAndReadPool(ks, ejb3Address, "default-slsb-instance-pool", null);
        writeAndReadPool(ks, ejb3Address, "default-slsb-instance-pool", testPoolName);
        writeAndReadPool(ks, ejb3Address, "default-slsb-instance-pool", testPoolName);
        writeAndReadPool(ks, ejb3Address, "default-slsb-instance-pool", "slsb-strict-max-pool");

        final ModelNode removePool = Util.createRemoveOperation(testPoolAddress);
        response = ks.executeOperation(removePool);
        assertEquals(response.toString(), "success", response.get("outcome").asString());
    }

    /**
     * Verifies that attributes with expression are handled properly.
     * @throws Exception for any test failures
     */
    @Test
    public void testExpressionInAttributeValue() throws Exception {
        final String subsystemXml = readResource("with-expression-subsystem.xml");
        final KernelServices ks = createKernelServicesBuilder(createAdditionalInitialization()).setSubsystemXml(subsystemXml).build();
        final ModelNode ejb3 = ks.readWholeModel().get("subsystem", getMainSubsystemName());

        final String statisticsEnabled = ejb3.get("statistics-enabled").resolve().asString();
        assertEquals("true", statisticsEnabled);

        final String logSystemException = ejb3.get("log-system-exceptions").resolve().asString();
        assertEquals("false", logSystemException);

        final String passByValue = ejb3.get("in-vm-remote-interface-invocation-pass-by-value").resolve().asString();
        assertEquals("false", passByValue);

        final String gracefulTxn = ejb3.get("enable-graceful-txn-shutdown").resolve().asString();
        assertEquals("false", gracefulTxn);

        final String disableDefaultEjbPermission = ejb3.get("disable-default-ejb-permissions").resolve().asString();
        assertEquals("false", disableDefaultEjbPermission);

        final int defaultStatefulSessionTimeout = ejb3.get("default-stateful-bean-session-timeout").resolve().asInt();
        assertEquals(600000, defaultStatefulSessionTimeout);

        final int defaultStatefulAccessTimeout = ejb3.get("default-stateful-bean-access-timeout").resolve().asInt();
        assertEquals(5000, defaultStatefulAccessTimeout);

        final String defaultSlsbInstancePool = ejb3.get("default-slsb-instance-pool").resolve().asString();
        assertEquals("slsb-strict-max-pool", defaultSlsbInstancePool);

        final int defaultSingletonAccessTimeout = ejb3.get("default-singleton-bean-access-timeout").resolve().asInt();
        assertEquals(5000, defaultSingletonAccessTimeout);

        final String defaultSfsbPassivationDisabledCache = ejb3.get("default-sfsb-passivation-disabled-cache").resolve().asString();
        assertEquals("simple", defaultSfsbPassivationDisabledCache);

        final String defaultSfsbCache = ejb3.get("default-sfsb-cache").resolve().asString();
        assertEquals("distributable", defaultSfsbCache);

        final String defaultSecurityDomain = ejb3.get("default-security-domain").resolve().asString();
        assertEquals("domain", defaultSecurityDomain);

        final String defaultResourceAdapterName = ejb3.get("default-resource-adapter-name").resolve().asString();
        assertEquals("activemq-ra.rar", defaultResourceAdapterName);

        final String defaultMissingMethodPermissionDenyAccess = ejb3.get("default-missing-method-permissions-deny-access").resolve().asString();
        assertEquals("false", defaultMissingMethodPermissionDenyAccess);

        final String defaultMdbInstancePool = ejb3.get("default-mdb-instance-pool").resolve().asString();
        assertEquals("mdb-strict-max-pool", defaultMdbInstancePool);

        final String defaultEntityBeanOptimisticLocking = ejb3.get("default-entity-bean-optimistic-locking").resolve().asString();
        assertEquals("true", defaultEntityBeanOptimisticLocking);

        final String defaultEntityBeanInstancePool = ejb3.get("default-entity-bean-instance-pool").resolve().asString();
        assertEquals("entity-strict-max-pool", defaultEntityBeanInstancePool);

        final String defaultDistinctName = ejb3.get("default-distinct-name").resolve().asString();
        assertEquals("myname", defaultDistinctName);

        final String allowEjbNameRegex = ejb3.get("allow-ejb-name-regex").resolve().asString();
        assertEquals("false", allowEjbNameRegex);

        final String cachePassivationStore = ejb3.get("cache").asPropertyList().get(1).getValue().get("passivation-store").resolve().asString();
        assertEquals("infinispan", cachePassivationStore);

        final String mdbDeliveryGroupActive = ejb3.get("mdb-delivery-group").asPropertyList().get(1).getValue().get("active").resolve().asString();
        assertEquals("false", mdbDeliveryGroupActive);

        final ModelNode passivationStore = ejb3.get("passivation-store").asPropertyList().get(0).getValue();
        assertEquals("default", passivationStore.get("bean-cache").asString());
        assertEquals("ejb", passivationStore.get("cache-container").asString());
        assertEquals(10, passivationStore.get("max-size").resolve().asInt());

        final ModelNode remotingProfile = ejb3.get("remoting-profile").asPropertyList().get(0).getValue();
        assertEquals("true", remotingProfile.get("exclude-local-receiver").resolve().asString());
        assertEquals("true", remotingProfile.get("local-receiver-pass-by-value").resolve().asString());

        final ModelNode remoteHttpConnection = remotingProfile.get("remote-http-connection").asPropertyList().get(0).getValue();
        assertEquals("http://localhost:8180/wildfly-services", remoteHttpConnection.get("uri").resolve().asString());

        final ModelNode remotingEjbReceiver = remotingProfile.get("remoting-ejb-receiver").asPropertyList().get(0).getValue();
        assertEquals(5000, remotingEjbReceiver.get("connect-timeout").resolve().asInt());
        assertEquals("connection-ref", remotingEjbReceiver.get("outbound-connection-ref").resolve().asString());

        final ModelNode channelCreationOption = remotingEjbReceiver.get("channel-creation-options").asPropertyList().get(0).getValue();
        assertEquals(20, channelCreationOption.get("value").resolve().asInt());

        final String asyncThreadPoolName = ejb3.get("service", "async", "thread-pool-name").resolve().asString();
        assertEquals("default", asyncThreadPoolName);

        final String iiopEnableByDefault = ejb3.get("service", "iiop", "enable-by-default").resolve().asString();
        assertEquals("true", iiopEnableByDefault);
        final String useQualifiedName = ejb3.get("service", "iiop", "use-qualified-name").resolve().asString();
        assertEquals("true", useQualifiedName);

        final ModelNode remote = ejb3.get("service", "remote");
        assertEquals("ejb", remote.get("cluster").asString());
        assertEquals("false", remote.get("execute-in-worker").resolve().asString());
        assertEquals("default", remote.get("thread-pool-name").resolve().asString());
        assertEquals(20, remote.get("channel-creation-options").asPropertyList().get(0).getValue().get("value").resolve().asInt());

        final ModelNode timerService = ejb3.get("service", "timer-service");
        final String fileDataStorePath = timerService.get("file-data-store").asPropertyList().get(0).getValue().get("path").resolve().asString();
        assertEquals("timer-service-data", fileDataStorePath);

        final ModelNode databaseStore = timerService.get("database-data-store").asPropertyList().get(0).getValue();
        assertEquals("java:global/DataSource", databaseStore.get("datasource-jndi-name").resolve().asString());
        assertEquals("hsql", databaseStore.get("database").resolve().asString());
        assertEquals("mypartition", databaseStore.get("partition").resolve().asString());
        assertEquals("true", databaseStore.get("allow-execution").resolve().asString());
        assertEquals("100", databaseStore.get("refresh-interval").resolve().asString());

        final ModelNode strictMaxBeanInstancePool = ejb3.get("strict-max-bean-instance-pool").asPropertyList().get(0).getValue();
        assertEquals("from-cpu-count", strictMaxBeanInstancePool.get("derive-size").resolve().asString());
        assertEquals(5, strictMaxBeanInstancePool.get("timeout").resolve().asInt());
        assertEquals("MINUTES", strictMaxBeanInstancePool.get("timeout-unit").resolve().asString());

        final ModelNode strictMaxBeanInstancePool2 = ejb3.get("strict-max-bean-instance-pool").asPropertyList().get(1).getValue();
        assertEquals(20, strictMaxBeanInstancePool2.get("max-pool-size").resolve().asInt());

        final ModelNode threadPool = ejb3.get("thread-pool").asPropertyList().get(0).getValue();
        assertEquals(10, threadPool.get("max-threads").resolve().asInt());
        assertEquals(10, threadPool.get("core-threads").resolve().asInt());
    }

    private void writeAndReadPool(KernelServices ks, PathAddress ejb3Address, String attributeName, String testPoolName) {
        ModelNode writeAttributeOperation = testPoolName == null ?
                Util.getUndefineAttributeOperation(ejb3Address, attributeName) :
                Util.getWriteAttributeOperation(ejb3Address, attributeName, testPoolName);
        ModelNode response = ks.executeOperation(writeAttributeOperation);
        assertEquals(response.toString(), "success", response.get("outcome").asString());

        final String expectedPoolName = testPoolName == null ? "undefined" : testPoolName;
        final ModelNode readAttributeOperation = Util.getReadAttributeOperation(ejb3Address, attributeName);
        response = ks.executeOperation(readAttributeOperation);
        final String poolName = response.get("result").asString();
        assertEquals("Unexpected pool name", expectedPoolName, poolName);
    }

    private void validatePoolConfig(KernelServices ks, PathAddress pa) {
        ModelNode ra = Util.createEmptyOperation("read-attribute", pa);
        ra.get("name").set("max-pool-size");
        ModelNode response = ks.executeOperation(ra);
        assertEquals(response.toString(), 5, response.get("result").asInt());
        ra.get("name").set("derive-size");
        response = ks.executeOperation(ra);
        assertFalse(response.toString(), response.hasDefined("result"));
    }

    // -------------------------------------------------------------------------
    // F2: access-log model tests — destination variants, write-attribute, validation
    // -------------------------------------------------------------------------

    private static final PathAddress ACCESS_LOG_ADDR = PathAddress.pathAddress(
            PathElement.pathElement("subsystem", "ejb3"),
            PathElement.pathElement("service", "access-log"));

    /**
     * F2/M1 — destination=console: add succeeds and model reads back correctly.
     */
    @Test
    public void testAccessLogDestinationConsole() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        // Remove the default access-log, then add with destination=console
        ks.executeOperation(Util.createRemoveOperation(ACCESS_LOG_ADDR));

        final ModelNode addOp = Util.createAddOperation(ACCESS_LOG_ADDR);
        addOp.get("destination").set("console");
        final ModelNode result = ks.executeOperation(addOp);
        assertEquals("add(destination=console) should succeed: " + result,
                "success", result.get("outcome").asString());

        final ModelNode model = ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log");
        assertEquals("console", model.get("destination").asString());
    }

    /**
     * F2/M2 — destination=logging: add succeeds and model reads back correctly.
     */
    @Test
    public void testAccessLogDestinationLogging() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        ks.executeOperation(Util.createRemoveOperation(ACCESS_LOG_ADDR));

        final ModelNode addOp = Util.createAddOperation(ACCESS_LOG_ADDR);
        addOp.get("destination").set("logging");
        final ModelNode result = ks.executeOperation(addOp);
        assertEquals("add(destination=logging) should succeed: " + result,
                "success", result.get("outcome").asString());

        final ModelNode model = ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log");
        assertEquals("logging", model.get("destination").asString());
    }

    /**
     * F2/M3 — RESTART_NONE write-attribute: include-local and include-node-name
     * can be changed without restarting the service.  In MANAGEMENT mode the
     * runtime side is a no-op (LIVE_SERVICE==null), but the model must update.
     */
    @Test
    public void testAccessLogRestartNoneWriteAttribute() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        // include-local: false → true
        ModelNode write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "include-local", ModelNode.TRUE);
        ModelNode result = ks.executeOperation(write);
        assertEquals("write include-local: " + result, "success", result.get("outcome").asString());
        assertTrue("include-local should be true",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "include-local").asBoolean());

        // include-node-name: true → false
        write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "include-node-name", ModelNode.FALSE);
        result = ks.executeOperation(write);
        assertEquals("write include-node-name: " + result, "success", result.get("outcome").asString());
        assertFalse("include-node-name should be false",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "include-node-name").asBoolean());
    }

    /**
     * F2/M4 — metadata write-attribute: the RESTART_NONE handler stores key/value pairs.
     */
    @Test
    public void testAccessLogMetadataWriteAttribute() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final ModelNode metadata = new ModelNode();
        metadata.get("@version").set("1");
        metadata.get("env").set("prod");
        final ModelNode write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "metadata", metadata);
        final ModelNode result = ks.executeOperation(write);
        assertEquals("write metadata: " + result, "success", result.get("outcome").asString());

        final ModelNode stored = ks.readWholeModel()
                .get("subsystem", "ejb3", "service", "access-log", "metadata");
        assertTrue("metadata should be defined", stored.isDefined());
        assertEquals("1",    stored.get("@version").asString());
        assertEquals("prod", stored.get("env").asString());
    }

    /**
     * F2/M5 — attributes list: a valid token is accepted; an invalid token is rejected.
     */
    @Test
    public void testAccessLogAttributesListValidation() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        // Remove default and re-add with a valid explicit attributes list
        ks.executeOperation(Util.createRemoveOperation(ACCESS_LOG_ADDR));

        final ModelNode addOp = Util.createAddOperation(ACCESS_LOG_ADDR);
        final ModelNode attrList = addOp.get("attributes").setEmptyList();
        attrList.add("bean");
        attrList.add("method");
        attrList.add("outcome");
        attrList.add("duration");
        ModelNode result = ks.executeOperation(addOp);
        assertEquals("add with valid attributes list should succeed: " + result,
                "success", result.get("outcome").asString());

        // Now try to add with an invalid token
        ks.executeOperation(Util.createRemoveOperation(ACCESS_LOG_ADDR));

        final ModelNode badAddOp = Util.createAddOperation(ACCESS_LOG_ADDR);
        final ModelNode badList = badAddOp.get("attributes").setEmptyList();
        badList.add("bean");
        badList.add("not-a-real-field");   // invalid
        result = ks.executeOperation(badAddOp);
        assertEquals("add with invalid attributes token should fail: " + result,
                "failed", result.get("outcome").asString());
        assertTrue("failure should mention the invalid token: " + result,
                result.get("failure-description").asString().contains("not-a-real-field"));
    }

    /**
     * F2/M6 — write-attribute: changing destination to console while an explicit path
     * is set must be rejected (same model validation as the add-op case in D6/3b).
     * The default model only carries default values (not explicitly set), so we first
     * write an explicit path, then attempt to change destination to console.
     */
    @Test
    public void testAccessLogRejectFileAttributesOnDestinationWrite() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        // Explicitly set path so the model records it as operator-supplied.
        ModelNode writePath = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "path",
                new ModelNode("custom-ejb-access.log"));
        ModelNode result = ks.executeOperation(writePath);
        assertEquals("write path should succeed: " + result, "success", result.get("outcome").asString());

        // Now changing destination to console must be rejected (path is explicitly set).
        final ModelNode writeDest = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "destination",
                new ModelNode("console"));
        result = ks.executeOperation(writeDest);
        assertEquals("write-attribute destination=console with explicit path set should fail: " + result,
                "failed", result.get("outcome").asString());
        final String failDesc = result.get("failure-description").asString();
        assertTrue("failure should mention file or path: " + failDesc,
                failDesc.contains("file") || failDesc.contains("path"));
    }

}
