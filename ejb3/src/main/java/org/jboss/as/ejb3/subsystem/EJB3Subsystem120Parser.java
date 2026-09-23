/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.ejb3.subsystem;

/**
 * Parser for ejb3:12.0 namespace.
 *
 * TODO Parameterize a single parser class by schema version.  Inheritence is a poor model for versioning.
 */
public class EJB3Subsystem120Parser extends EJB3Subsystem110Parser {

    @Override
    protected EJB3SubsystemNamespace getExpectedNamespace() {
        return EJB3SubsystemNamespace.EJB3_12_0;
    }

}
