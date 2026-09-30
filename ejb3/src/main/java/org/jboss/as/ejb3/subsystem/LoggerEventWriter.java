/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.wildfly.event.logger.Event;
import org.wildfly.event.logger.EventFormatter;
import org.wildfly.event.logger.EventWriter;

/**
 * An {@link EventWriter} that formats each event and passes the result to a named
 * {@link Logger} category at {@link Level#INFO}.
 *
 * <p>The category is a constructor argument so this class carries no knowledge of
 * the subsystem it is embedded in.  H2 lifts this class into
 * {@code org.wildfly.event.logger} verbatim; nothing here may import an ejb3 type.
 *
 * @author Tomasz Adamski
 */
public final class LoggerEventWriter implements EventWriter {

    private final Logger logger;
    private final EventFormatter formatter;

    private LoggerEventWriter(final String category, final EventFormatter formatter) {
        this.logger = Logger.getLogger(category);
        this.formatter = formatter;
    }

    /**
     * Creates a new writer that logs to the named category.
     *
     * @param category  the logger category (e.g. {@code "org.jboss.as.ejb3.access-log"})
     * @param formatter the formatter to convert each event to a string
     * @return a new writer
     */
    public static LoggerEventWriter of(final String category, final EventFormatter formatter) {
        return new LoggerEventWriter(category, formatter);
    }

    @Override
    public void write(final Event event) {
        final String line = formatter.format(event);
        logger.log(Level.INFO, line);
    }

    @Override
    public void close() {
        // Logger has no resource to release; nothing to do.
    }
}
