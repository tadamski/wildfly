/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest3;

import jakarta.resource.Referenceable;
import jakarta.resource.ResourceException;

import java.io.Serializable;

/**
 * User: jpai
 */
public interface StressTestConnectionFactory extends Serializable, Referenceable {

    /**
     * Get connection from factory
     *
     * @return HelloWorldConnection instance
     * @throws ResourceException Thrown if a connection can't be obtained
     */

    StressTestConnection getConnection() throws ResourceException;


}
