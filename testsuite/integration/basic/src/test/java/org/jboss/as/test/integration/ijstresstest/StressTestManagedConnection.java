/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.ijstresstest;

import jakarta.resource.NotSupportedException;
import jakarta.resource.ResourceException;
import jakarta.resource.spi.ConnectionEvent;
import jakarta.resource.spi.ConnectionEventListener;
import jakarta.resource.spi.ConnectionRequestInfo;
import jakarta.resource.spi.LocalTransaction;
import jakarta.resource.spi.ManagedConnection;
import jakarta.resource.spi.ManagedConnectionMetaData;

import javax.security.auth.Subject;
import javax.transaction.xa.XAResource;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static org.wildfly.common.Assert.checkNotNullParam;

/**
 * User: jpai
 */
public class StressTestManagedConnection implements ManagedConnection {


    /**
     * MCF
     */
    private StressTestManagedConnectionFactory mcf;


    /**
     * Listeners
     */
    private List<ConnectionEventListener> listeners;


    /**
     * Connection
     */
    private Object connection;

    private PrintWriter writer;


    /**
     * default constructor
     *
     * @param mcf mcf
     */
    public StressTestManagedConnection(StressTestManagedConnectionFactory mcf) {
        this.mcf = mcf;
        this.listeners = new ArrayList<ConnectionEventListener>();
        this.connection = null;

    }


    /**
     * Creates a new connection handle for the underlying physical connection
     * <p/>
     * represented by the ManagedConnection instance.
     *
     * @param subject       Security context as JAAS subject
     * @param cxRequestInfo ConnectionRequestInfo instance
     * @return generic Object instance representing the connection handle.
     * @throws ResourceException generic exception if operation fails
     */

    public Object getConnection(Subject subject, ConnectionRequestInfo cxRequestInfo) throws ResourceException {

        connection = new StressTestConnectionImpl(this, mcf);
        return connection;
    }


    /**
     * Used by the container to change the association of an
     * <p/>
     * application-level connection handle with a ManagedConneciton instance.
     *
     * @param connection Application-level connection handle
     * @throws ResourceException generic exception if operation fails
     */

    public void associateConnection(Object connection) throws ResourceException {

        this.connection = connection;

    }


    /**
     * Application server calls this method to force any cleanup on
     * <p/>
     * the ManagedConnection instance.
     *
     * @throws ResourceException generic exception if operation fails
     */

    public void cleanup() throws ResourceException {

    }


    /**
     * Destroys the physical connection to the underlying resource manager.
     *
     * @throws ResourceException generic exception if operation fails
     */

    public void destroy() throws ResourceException {

        this.connection = null;

    }


    /**
     * Adds a connection event listener to the ManagedConnection instance.
     *
     * @param listener A new ConnectionEventListener to be registered
     */

    public void addConnectionEventListener(ConnectionEventListener listener) {
        checkNotNullParam("listener", listener);

        listeners.add(listener);

    }


    /**
     * Removes an already registered connection event listener
     * <p/>
     * from the ManagedConnection instance.
     *
     * @param listener Already registered connection event listener to be removed
     */
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        checkNotNullParam("listener", listener);

        listeners.remove(listener);

    }


    /**
     * Returns an <code>jakarta.resource.spi.LocalTransaction</code> instance.
     *
     * @return LocalTransaction instance
     * @throws ResourceException generic exception if operation fails
     */

    public LocalTransaction getLocalTransaction() throws ResourceException {

        throw new NotSupportedException("LocalTransaction not supported");

    }


    /**
     * Returns an <code>javax.transaction.xa.XAresource</code> instance.
     *
     * @return XAResource instance
     * @throws ResourceException generic exception if operation fails
     */

    public XAResource getXAResource() throws ResourceException {

        throw new NotSupportedException("GetXAResource not supported");
    }


    /**
     * Gets the metadata information for this connection's underlying
     * <p/>
     * EIS resource manager instance.
     *
     * @return ManagedConnectionMetaData instance
     * @throws ResourceException generic exception if operation fails
     */
    public ManagedConnectionMetaData getMetaData() throws ResourceException {

        return new StressTestManagedConnectionMetaData();

    }

    @Override
    public void setLogWriter(PrintWriter printWriter) throws ResourceException {
        this.writer = printWriter;
    }

    @Override
    public PrintWriter getLogWriter() throws ResourceException {
        return this.writer;
    }


    /**
     * Close handle
     *
     * @param handle The handle
     */

    void closeHandle(StressTestConnection handle) {

        ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.CONNECTION_CLOSED);
        event.setConnectionHandle(handle);
        for (ConnectionEventListener cel : listeners) {
            cel.connectionClosed(event);
        }
    }

}
