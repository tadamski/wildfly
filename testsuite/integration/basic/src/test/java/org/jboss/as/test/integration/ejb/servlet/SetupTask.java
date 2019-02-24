package org.jboss.as.test.integration.ejb.servlet;

import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.test.module.util.TestModule;
import org.jboss.as.test.shared.ModuleUtils;

public class SetupTask implements ServerSetupTask {

    private static final String TEST_MODULE_NAME = "customInterceptors";

    private static TestModule testModule;

    @Override
    public void setup(ManagementClient managementClient, String s) throws Exception {
        testModule = ModuleUtils.createTestModuleWithEEDependencies(TEST_MODULE_NAME);
        testModule.addResource("module.jar");
        testModule.create();
    }

    @Override
    public void tearDown(ManagementClient managementClient, String s) throws Exception {
        if (testModule != null) {
            testModule.remove();
        }
    }


}

