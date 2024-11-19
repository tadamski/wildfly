/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest;

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


    /**
     * Default constructor
     *
     * @param mc  HelloWorldManagedConnection
     * @param mcf HelloWorldManagedConnectionFactory
     */
    public StressTestConnectionImpl(StressTestManagedConnection mc, StressTestManagedConnectionFactory mcf) {

        this.mc = mc;

        this.mcf = mcf;

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
    public String helloWorld(String name) {

        return "Hello World, " + name + " !";

    }


    /**
     * Close
     */
    public void close() {

        mc.closeHandle(this);

    }

}

