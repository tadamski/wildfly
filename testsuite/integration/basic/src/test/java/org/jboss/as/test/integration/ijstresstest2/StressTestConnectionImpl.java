/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest2;

import jakarta.resource.spi.ConnectionRequestInfo;
import jakarta.resource.spi.LazyAssociatableConnectionManager;

import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * User: jpai
 */
public class StressTestConnectionImpl implements StressTestConnection {


    /**
     * ManagedConnection
     */

    private StressTestManagedConnection mc;


    /**
     * ManagedConnectionFactory
     */

    private StressTestManagedConnectionFactory mcf;

    private ConnectionRequestInfo cri;

    private LazyAssociatableConnectionManager cm;

    private ReentrantLock lock = new ReentrantLock(true);


    /**
     * Default constructor
     *
     * @param mc  HelloWorldManagedConnection
     * @param mcf HelloWorldManagedConnectionFactory
     */
    public StressTestConnectionImpl(StressTestManagedConnection mc, StressTestManagedConnectionFactory mcf, ConnectionRequestInfo cri, LazyAssociatableConnectionManager cm) {

        this.mc = mc;
        this.mcf = mcf;
        this.cri = cri;
        this.cm = cm;
    }


    /**
     * Call helloWorld
     *
     * @return String helloworld
     */
    public String helloWorld() {

        return helloWorld(mcf.getResourceAdapter().toString());
    }


    /**
     * Call helloWorld
     *
     * @param name String name
     * @return String helloworld
     */
    private String helloWorld(String name) {
        lock.lock();
        try {
        System.out.println("BAJOBONGO TU SIE ROZPOCZYNA HELLO WORLD");
        //introduceRandomError();
        return "Hello World, " + name + " !";
        }
        finally {
            lock.unlock();
        }

    }

    private void introduceRandomError() {
        if (new Random().nextDouble() < 0.1) {
                mc.notifyError();
        }
    }


    /**
     * Close
     */
    public void close() {
        lock.lock();
        try {
        System.out.println("BAJOBONGO ZAMYKAM HENDLA");
        mc.closeHandle(this);
        }
        finally {
            lock.unlock();
        }

    }
}

