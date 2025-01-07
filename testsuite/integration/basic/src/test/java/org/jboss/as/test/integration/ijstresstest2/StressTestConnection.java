/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest2;

/**
 * User: jpai
 */
public interface StressTestConnection {
    /**
     * HelloWorld
     *
     * @return String
     */

    String helloWorld();

    /**
     * Close
     */

    void close();
}
