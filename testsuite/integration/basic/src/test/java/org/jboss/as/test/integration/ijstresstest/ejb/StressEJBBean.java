/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest.ejb;

import jakarta.annotation.Resource;
import jakarta.ejb.Remote;
import jakarta.ejb.Stateless;
import org.jboss.as.test.integration.ijstresstest.StressTestConnection;
import org.jboss.as.test.integration.ijstresstest.StressTestConnectionFactory;

@Stateless
@Remote(StressEJB.class)
public class StressEJBBean implements StressEJB {

    @Resource(lookup = "java:jboss/stress-test-adapter")
    StressTestConnectionFactory connectionFactory;

    @Override
    public void performStressTest() {
        try {
            for (int i = 0; i < 100; i++) {
                useConnection();
            }
        } catch (Throwable t) {
            System.out.println("BAJOBONGO test sie wywalil");
        }
    }


    private void useConnection() throws Exception {
        StressTestConnection connection = connectionFactory.getConnection();
        Thread.sleep(300);
        connection.close();
    }
}
