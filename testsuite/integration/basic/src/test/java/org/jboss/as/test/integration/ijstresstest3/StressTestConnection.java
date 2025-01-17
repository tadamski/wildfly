/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest3;

import jakarta.resource.ResourceException;

/**
 * User: jpai
 */
public interface StressTestConnection {
    /**
     * HelloWorld
     *
     * @return String
     */

    String helloWorld() throws ResourceException;

    /**
     * Close
     */

    void close();
}
