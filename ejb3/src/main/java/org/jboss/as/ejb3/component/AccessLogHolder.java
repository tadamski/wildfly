/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.component;

import org.jboss.as.ejb3.subsystem.AccessLogService;

/**
 * Stable singleton that bridges the runtime lifecycle of {@link AccessLogService} to the
 * statically-registered {@link EjbAccessLogInterceptor}.
 *
 * <p>The interceptor is installed at deployment time and cannot take an MSC dependency on the
 * access-log service (which is added and removed at runtime, after deployments start). This
 * holder provides the shared mutable reference: {@link AccessLogService} publishes and clears
 * itself here on {@code start}/{@code stop}, and the interceptor reads {@link #get()} on every
 * invocation — one volatile read, identical cost to the previous static field.
 *
 * <p>There is one holder instance per server ({@link #INSTANCE}), matching the cardinality of
 * the {@code service=access-log} resource (at most one per subsystem instance).
 */
public final class AccessLogHolder {

    /** The single server-wide holder instance. */
    public static final AccessLogHolder INSTANCE = new AccessLogHolder();

    private volatile AccessLogService service;

    private AccessLogHolder() {
    }

    /**
     * Returns the live {@link AccessLogService}, or {@code null} when the access-log resource
     * is not present.
     */
    public AccessLogService get() {
        return service;
    }

    /**
     * Called by {@link AccessLogService#start} to publish the running service.
     */
    public void set(final AccessLogService service) {
        this.service = service;
    }

    /**
     * Called by {@link AccessLogService#stop} to clear the running service.
     */
    public void clear() {
        this.service = null;
    }
}
