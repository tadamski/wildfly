/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.integration.ejb.remote.compression;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.network.NetworkUtils;
import org.jboss.ejb.client.EJBClientConnection;
import org.jboss.ejb.client.EJBClientContext;
import org.jboss.ejb.protocol.remote.RemoteTransportProvider;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.common.Assert;
import org.wildfly.naming.client.WildFlyInitialContextFactory;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.net.URI;
import java.util.Properties;

/**
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 */

@RunWith(Arquillian.class)
@RunAsClient
public class DefaultCompressionTestCase {

    private static final String MODULE_NAME = "default-compression";

    final String INVOCATION_URL = "remote+http://" +
            NetworkUtils.formatPossibleIpv6Address(System.getProperty("node0", "localhost")) + ":8080";

    @Deployment(testable = false)
    public static Archive createDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, MODULE_NAME + ".jar");
        jar.addPackage(DefaultCompressionTestCase.class.getPackage());
        return jar;
    }

    /**
     * Test setting the invocation timeout when initializing an EJB client context programmatically.
     */
    @Test
    public void testDefaultCompression() {
        final EJBClientContext.Builder builder = new EJBClientContext.Builder();
        builder.setDefaultCompression(5);
        builder.addTransportProvider(new RemoteTransportProvider());
        builder.addClientConnection(
                new EJBClientConnection.Builder().setDestination(URI.create(INVOCATION_URL)).build()
        );
        builder.build().run(() -> {
            final Properties properties = new Properties();
            properties.put(Context.INITIAL_CONTEXT_FACTORY, WildFlyInitialContextFactory.class.getName());
            InitialContext ejbCtx = null;
            try {
                ejbCtx = new InitialContext(properties);
                Foo bean = lookup(ejbCtx);
                bean.foo();
            } catch (Exception e) {
                org.junit.Assert.fail(e);
            } finally {
                if (ejbCtx != null) {
                    try {
                        ejbCtx.close();
                    } catch (NamingException e) {
                        Assert.unreachableCode();
                    }
                }
            }
        });
    }

    public Foo lookup(InitialContext ctx) throws NamingException {
        return (Foo)ctx.lookup(
                "ejb:/" + MODULE_NAME + "/" + FooBean.class.getSimpleName() + "!"
                        + Foo.class.getName());
    }
}
