/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.connector.subsystems.resourceadapters.Namespace;
import org.jboss.as.connector.subsystems.resourceadapters.ResourceAdapterSubsystemParser;
import org.jboss.as.test.integration.ijstresstest.ejb.StressEJB;
import org.jboss.as.test.integration.ijstresstest.ejb.StressEJBBean;
import org.jboss.as.test.integration.management.base.AbstractMgmtServerSetupTask;
import org.jboss.as.test.shared.FileUtils;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.ResourceAdapterArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.naming.Context;
import javax.naming.InitialContext;
import java.util.Hashtable;
import java.util.List;

@RunWith(Arquillian.class)
@ServerSetup(IronJacamarStressTestCase.IronJacamarStressTestCaseSetup.class)
public class IronJacamarStressTestCase {

    static class IronJacamarStressTestCaseSetup extends AbstractMgmtServerSetupTask {

        @Override
        public void doSetup(final ManagementClient managementClient) throws Exception {
            String xml = FileUtils.readFile(IronJacamarStressTestCase.class, "ra-subsystem.xml");
            List<ModelNode> operations = xmlToModelOperations(xml, Namespace.RESOURCEADAPTERS_7_1.getUriString(), new ResourceAdapterSubsystemParser());
            ModelNode result = executeOperation(operationListToCompositeOperation(operations));
        }

        @Override
        public void tearDown(final ManagementClient managementClient, final String containerId) throws Exception {

            final ModelNode address = new ModelNode();
            address.add("subsystem", "resource-adapters");
            address.add("resource-adapter", "stress.rar");
            address.protect();
            remove(address);
        }
    }

    private static final String APP_NAME = "";
    private static final String DISTINCT_NAME = "";
    private static final String MODULE_NAME = "ejb";

    @Deployment(name = "rar", order = 1)
    public static Archive<?> deployRar() {
        ResourceAdapterArchive raa = ShrinkWrap.create(ResourceAdapterArchive.class, "stresstest.rar");
        JavaArchive ja = ShrinkWrap.create(JavaArchive.class, "lib.jar");
        ja.addPackage(StressTestConnectionFactory.class.getPackage());
        raa.addAsLibrary(ja);
        return raa;
    }

    @Deployment(name = "ejb", order = 2)
    public static Archive deployEjb() {
        final JavaArchive ejbJar = ShrinkWrap.create(JavaArchive.class, "ejb.jar");
        ejbJar.addClasses(StressEJB.class, StressEJBBean.class);
        ejbJar.addAsManifestResource(IronJacamarStressTestCase.class.getPackage(), "MANIFEST.MF", "MANIFEST.MF");
        return ejbJar;
    }

    /**
     * Test the deployment succeeded.
     *
     * @throws Exception
     */
    @Test
    @RunAsClient
    public void performStressTest() throws Exception {
        final Hashtable<String, String> props = new Hashtable<>();
        props.put(Context.URL_PKG_PREFIXES, "org.jboss.ejb.client.naming");
        final Context jndiContext = new InitialContext(props);
        final StressEJB stressEJB = (StressEJB) jndiContext.lookup("ejb:" + APP_NAME + "/" + MODULE_NAME + "/" + DISTINCT_NAME
                + "/" + StressEJBBean.class.getSimpleName() + "!" + StressEJB.class.getName());
        stressEJB.performStressTest();
    }
}
