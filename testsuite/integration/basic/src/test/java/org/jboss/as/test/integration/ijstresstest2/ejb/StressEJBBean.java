/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest2.ejb;

import jakarta.annotation.Resource;
import jakarta.ejb.Remote;
import jakarta.ejb.Stateless;
import org.jboss.as.test.integration.ijstresstest2.StressTestConnection;
import org.jboss.as.test.integration.ijstresstest2.StressTestConnectionFactory;

import java.util.concurrent.CountDownLatch;

@Stateless
@Remote(StressEJB.class)
public class StressEJBBean implements StressEJB {

    private static final int NUMBER_OF_THREADS = 1;

    @Resource(lookup = "java:jboss/stress-test-adapter")
    StressTestConnectionFactory connectionFactory;

    @Override
    public void performStressTest() {
        CountDownLatch threadsToFinish = new CountDownLatch(NUMBER_OF_THREADS);
        try {
            for (int i = 0; i < NUMBER_OF_THREADS; i++) {
                new StressThread(connectionFactory, threadsToFinish).start();
            }
        } catch (Throwable t) {
            System.out.println("BAJOBONGO test sie wywalil ABC");
            t.printStackTrace();
        } finally {
            try {
                threadsToFinish.await();
            } catch (InterruptedException ignored) {
            }
        }
    }


    private static class StressThread extends Thread {

        private final StressTestConnectionFactory connectionFactory;
        private final CountDownLatch threadsToFinish;

        public StressThread(final StressTestConnectionFactory connectionFactory, CountDownLatch threadsToFinish){
            this.connectionFactory = connectionFactory;
            this.threadsToFinish = threadsToFinish;
        }

        @Override
        public void run() {
            for (int i = 0; i < 100; i++) {
                try {
                    useConnection();
                } catch (Throwable t) {
                    System.out.println("BAJOBONGO WYWALIL SIE");
                    t.printStackTrace();
                    Throwable rootCause = t;
                    while (rootCause.getCause() != null) {
                        rootCause = rootCause.getCause();
                    }
                    if (rootCause.getMessage()!= null && rootCause.getMessage().contains("IJ000655")) {
                        System.out.println("BAJOBONGO KONCZE WATEK BO PULA PADLA");
                        break;
                    }
                }
            }
            threadsToFinish.countDown();
        }

        private void useConnection() throws Exception {
            System.out.println("BAJOBONGO START UZYCIA WATKU");
            StressTestConnection connection = connectionFactory.getConnection();
            connection.helloWorld();
            System.out.println("BAJOBONGO WATEK SE IDZIE SPAC PRZED ZWROCENIEM");
            Thread.sleep(150);
            System.out.println("BAJOBONGO WATEK WSTAJE I BEDZIE ZWRACAL");
            connection.close();
            Thread.sleep(200);
        }
    }


}
