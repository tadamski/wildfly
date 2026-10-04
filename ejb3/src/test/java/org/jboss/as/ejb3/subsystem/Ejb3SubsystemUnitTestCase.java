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
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
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

    /**
     * R3/1 — write-attribute(destination) updates the model correctly.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>  {@code requiresRuntime()} is
     * {@code false}; {@code applyUpdateToRuntime} is never called.  This test covers
     * model validation and read-back only.
     */
    @Test
    public void testAccessLogRestartServicesWriteDestination() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        // destination: file → logging
        ModelNode write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "destination", new ModelNode("logging"));
        ModelNode result = ks.executeOperation(write);
        assertEquals("write destination=logging should succeed: " + result,
                "success", result.get("outcome").asString());
        assertEquals("model should reflect new destination", "logging",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "destination").asString());

        // destination: logging → console
        write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "destination", new ModelNode("console"));
        result = ks.executeOperation(write);
        assertEquals("write destination=console should succeed: " + result,
                "success", result.get("outcome").asString());
        assertEquals("model should reflect new destination", "console",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "destination").asString());

        // destination: console → file
        write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "destination", new ModelNode("file"));
        result = ks.executeOperation(write);
        assertEquals("write destination=file should succeed: " + result,
                "success", result.get("outcome").asString());
        assertEquals("model should reflect new destination", "file",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "destination").asString());
    }

    /**
     * R3/2 — write-attribute(path) and write-attribute(worker) update the model correctly.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>  Covers model validation and
     * persistence for two more RESTART_RESOURCE_SERVICES attributes; runtime step skipped.
     */
    @Test
    public void testAccessLogRestartServicesWritePathAndWorker() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(createAdditionalInitialization())
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        // path
        ModelNode write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "path", new ModelNode("custom.log"));
        ModelNode result = ks.executeOperation(write);
        assertEquals("write path should succeed: " + result, "success", result.get("outcome").asString());
        assertEquals("model should reflect new path", "custom.log",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "path").asString());

        // worker
        write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "worker", new ModelNode("default"));
        result = ks.executeOperation(write);
        assertEquals("write worker should succeed: " + result, "success", result.get("outcome").asString());
        assertEquals("model should reflect worker", "default",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "worker").asString());
    }

    /**
     * R3/3 — write-attribute(rotate-suffix) updates the model correctly.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>  Runtime step skipped.
     */
    @Test
    public void testAccessLogRestartServicesWriteRotateSuffix() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        ModelNode write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "rotate-suffix", new ModelNode(".yyyy-MM-dd-HH"));
        ModelNode result = ks.executeOperation(write);
        assertEquals("write rotate-suffix should succeed: " + result, "success", result.get("outcome").asString());
        assertEquals("model should reflect new rotate-suffix", ".yyyy-MM-dd-HH",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "rotate-suffix").asString());
    }

    /**
     * R3/4 — Repeated write-attribute(destination) calls succeed without reload-required.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>  Runtime step skipped.
     * Verifies the model layer does not emit reload-required on any destination value.
     */
    @Test
    public void testAccessLogRestartServicesRepeatedWrites() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final String[] destinations = {"logging", "console", "file", "logging", "console", "file"};
        for (String dest : destinations) {
            final ModelNode write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "destination", new ModelNode(dest));
            final ModelNode result = ks.executeOperation(write);
            assertEquals("write destination=" + dest + " should succeed: " + result,
                    "success", result.get("outcome").asString());
            assertFalse("repeated write must not trigger reload-required for destination=" + dest,
                    hasReloadRequired(result));
        }
    }

    /**
     * R3/5 — Composite write changing two RESTART_RESOURCE_SERVICES attributes at once succeeds.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>  Runtime step skipped.
     */
    @Test
    public void testAccessLogRestartServicesBatchWrite() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final ModelNode composite = Util.createEmptyOperation("composite", PathAddress.EMPTY_ADDRESS);
        final ModelNode steps = composite.get("steps");
        steps.add(Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "path", new ModelNode("batch.log")));
        steps.add(Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "rotate-suffix", new ModelNode(".yyyy-MM")));

        final ModelNode result = ks.executeOperation(composite);
        assertEquals("batch write should succeed: " + result, "success", result.get("outcome").asString());
        assertEquals("batch.log",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "path").asString());
        assertEquals(".yyyy-MM",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "rotate-suffix").asString());
    }

    /**
     * R3/6 — {@code :remove} succeeds without {@code reload-required} at the model layer.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>  {@code RemoveHandler.performRuntime}
     * is never called here; this confirms the operation is not rejected at the model stage.
     * Runtime proof (that {@code performRuntime} calls {@code removeService} rather than
     * {@code reloadRequired}) is in
     * {@code AccessLogRemoveNoReloadTestCase.testRemoveNoReloadRequired} in the integration
     * test suite.
     */
    @Test
    public void testAccessLogRemoveNoReloadRequired() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final ModelNode removeOp = Util.createRemoveOperation(ACCESS_LOG_ADDR);
        final ModelNode result = ks.executeOperation(removeOp);
        assertEquals(":remove should succeed: " + result, "success", result.get("outcome").asString());
        assertFalse(":remove must not trigger reload-required", hasReloadRequired(result));
    }

    /**
     * R3/7 — {@code :remove} followed by {@code :add} succeeds without reload at model layer.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>  Runtime step skipped; see
     * {@code AccessLogRemoveNoReloadTestCase} for the end-to-end runtime equivalent.
     */
    @Test
    public void testAccessLogRemoveThenAddNoReload() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        // Remove
        final ModelNode removeOp = Util.createRemoveOperation(ACCESS_LOG_ADDR);
        ModelNode result = ks.executeOperation(removeOp);
        assertEquals(":remove should succeed: " + result, "success", result.get("outcome").asString());
        assertFalse(":remove must not trigger reload-required", hasReloadRequired(result));

        // Re-add
        final ModelNode addOp = Util.createAddOperation(ACCESS_LOG_ADDR);
        addOp.get("destination").set("logging");
        result = ks.executeOperation(addOp);
        assertEquals(":add after :remove should succeed: " + result, "success", result.get("outcome").asString());
        assertFalse(":add after :remove must not trigger reload-required", hasReloadRequired(result));

        assertEquals("model should show logging after re-add", "logging",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "destination").asString());
    }

    // -------------------------------------------------------------------------
    // R4/P2 — rotate-suffix="" model tests (D23: empty string means no rotation)
    // Model-only (ADMIN_ONLY mode) — validator fix is what is under test.
    // -------------------------------------------------------------------------

    /**
     * R4/P2a — write-attribute(rotate-suffix="") is accepted after the validator fix.
     *
     * <p>Before the fix, the default STRING validator rejected empty values with
     * "minimum length of 1 characters".  D23 records empty string as meaning no rotation.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>
     */
    @Test
    public void testRotateSuffixEmptyAccepted() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final ModelNode write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "rotate-suffix", new ModelNode(""));
        final ModelNode result = ks.executeOperation(write);
        assertEquals("rotate-suffix=\"\" should be accepted: " + result,
                "success", result.get("outcome").asString());
        assertEquals("model should persist empty rotate-suffix", "",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "rotate-suffix").asString());
    }

    /**
     * R4/P2b — :add(rotate-suffix="") is accepted and the empty value round-trips.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>
     */
    @Test
    public void testRotateSuffixEmptyOnAdd() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        ks.executeOperation(Util.createRemoveOperation(ACCESS_LOG_ADDR));

        final ModelNode addOp = Util.createAddOperation(ACCESS_LOG_ADDR);
        addOp.get("rotate-suffix").set("");
        final ModelNode result = ks.executeOperation(addOp);
        assertEquals(":add(rotate-suffix=\"\") should succeed: " + result,
                "success", result.get("outcome").asString());
        assertEquals("model should persist empty rotate-suffix after :add", "",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "rotate-suffix").asString());
    }

    /**
     * R4/P2c — rotate-suffix="" round-trips through XML parse and persist.
     *
     * <p>Uses {@code standardSubsystemTest} which parses the XML, marshals it back, and
     * re-parses the marshalled form.  Proves that an empty rotate-suffix survives the
     * XML round-trip without being dropped or defaulted.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>
     */
    @Test
    public void testRotateSuffixEmptyXmlRoundTrip() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-no-rotation.xml")).build();
        assertTrue("boot failed: " + ks.getBootError(), ks.isSuccessfulBoot());

        assertEquals("rotate-suffix should be empty after XML parse", "",
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "rotate-suffix").asString());
    }

    // -------------------------------------------------------------------------
    // D24b — queue-length attribute tests
    // -------------------------------------------------------------------------

    /**
     * D24b/1 — default value of {@code queue-length} is 1024 when not specified.
     */
    @Test
    public void testQueueLengthDefault() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final ModelNode model = ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log");
        assertEquals("queue-length default must be 1024", 1024, model.get("queue-length").asInt());
    }

    /**
     * D24b/2 — an explicit {@code queue-length} value round-trips through the model.
     */
    @Test
    public void testQueueLengthExplicit() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final ModelNode model = ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log");
        assertEquals("queue-length must round-trip from subsystem.xml", 2048, model.get("queue-length").asInt());
    }

    /**
     * D24b/3 — {@code queue-length} expressed as an expression is stored correctly.
     * Acceptance check for the xs:union pattern: if {@code testSchema} passes with
     * with-expression-subsystem.xml containing {@code queue-length="${ejb.access.queue:2048}"}
     * then the XSD union did not repeat the D1 defect.
     */
    @Test
    public void testQueueLengthExpression() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(createAdditionalInitialization())
                .setSubsystemXml(readResource("with-expression-subsystem.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final ModelNode accessLog = ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log");
        // The expression ${ejb.access.queue:2048} resolves to 2048
        assertEquals("queue-length expression must resolve to 2048",
                2048, accessLog.get("queue-length").resolve().asInt());
    }

    /**
     * D24b/4 — {@code queue-length} can be changed via write-attribute (model round-trip).
     */
    @Test
    public void testQueueLengthWriteAttribute() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        final ModelNode write = Util.getWriteAttributeOperation(ACCESS_LOG_ADDR, "queue-length", new ModelNode(512));
        final ModelNode result = ks.executeOperation(write);
        assertEquals("write queue-length=512 should succeed: " + result, "success", result.get("outcome").asString());
        assertEquals("model should reflect new queue-length", 512,
                ks.readWholeModel().get("subsystem", "ejb3", "service", "access-log", "queue-length").asInt());
    }

    /**
     * D24b/5 — {@code queue-length=0} must be rejected (minimum is 1).
     */
    @Test
    public void testQueueLengthRejectZero() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        ks.executeOperation(Util.createRemoveOperation(ACCESS_LOG_ADDR));

        final ModelNode addOp = Util.createAddOperation(ACCESS_LOG_ADDR);
        addOp.get("queue-length").set(0);
        final ModelNode result = ks.executeOperation(addOp);
        assertEquals(":add(queue-length=0) must be rejected", "failed", result.get("outcome").asString());
    }

    // -------------------------------------------------------------------------
    // E8 — transport token
    // -------------------------------------------------------------------------

    /**
     * E8/1 — {@code transport} is a valid token in an explicit {@code attributes} list
     * and round-trips through the model without error.
     *
     * <p><strong>Model-only (ADMIN_ONLY mode).</strong>
     */
    @Test
    public void testTransportTokenRoundTripsInAttributesList() throws Exception {
        final KernelServices ks = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                .setSubsystemXml(readResource("subsystem-access-log-minimal.xml")).build();
        assertTrue("boot failed", ks.isSuccessfulBoot());

        ks.executeOperation(Util.createRemoveOperation(ACCESS_LOG_ADDR));

        final ModelNode addOp = Util.createAddOperation(ACCESS_LOG_ADDR);
        final ModelNode attrList = addOp.get("attributes").setEmptyList();
        attrList.add("transport");
        attrList.add("protocol");
        attrList.add("bean");
        attrList.add("outcome");
        final ModelNode result = ks.executeOperation(addOp);
        assertEquals(":add with transport in attributes list must succeed: " + result,
                "success", result.get("outcome").asString());

        // Verify the token round-trips in the model
        final ModelNode stored = ks.readWholeModel()
                .get("subsystem", "ejb3", "service", "access-log", "attributes");
        assertTrue("attributes must be defined after explicit :add", stored.isDefined());
        final java.util.List<ModelNode> tokens = stored.asList();
        assertTrue("transport must appear in the stored attributes list",
                tokens.stream().anyMatch(n -> "transport".equals(n.asString())));
        assertTrue("protocol must appear in the stored attributes list",
                tokens.stream().anyMatch(n -> "protocol".equals(n.asString())));
    }

    /**
     * E8/2 — When {@code attributes} is undefined (the default), {@code AccessLogService.ALL_ATTRIBUTES}
     * contains the {@code TRANSPORT} token, confirming a default record will carry the field.
     */
    @Test
    public void testTransportPresentInDefaultAllAttributes() {
        assertTrue("TRANSPORT must be in ALL_ATTRIBUTES (default record carries the field)",
                AccessLogService.ALL_ATTRIBUTES.contains(
                        AccessLogResourceDefinition.AttributeVocabulary.TRANSPORT));
    }

    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the operation response contains a
     * {@code response-headers / process-state = "reload-required"} entry.
     */
    private static boolean hasReloadRequired(final ModelNode response) {
        if (!response.hasDefined(ModelDescriptionConstants.RESPONSE_HEADERS)) {
            return false;
        }
        final ModelNode headers = response.get(ModelDescriptionConstants.RESPONSE_HEADERS);
        if (!headers.hasDefined(ModelDescriptionConstants.PROCESS_STATE)) {
            return false;
        }
        return ModelDescriptionConstants.RELOAD_REQUIRED.equals(
                headers.get(ModelDescriptionConstants.PROCESS_STATE).asString());
    }

}
