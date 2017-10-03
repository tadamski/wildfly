/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat Middleware LLC, and individual contributors
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

package org.jboss.as.test.iiopssl.iona;

import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExtHelper;

import java.util.Properties;

@RunWith(Arquillian.class)
@RunAsClient
public class IONASSLCompatibilityTestCase {

    @Test
    public void testIIOPNeverCallInvocation() throws Throwable {
        String corbaloc = "corbaloc:iiop:1.2@localhost:3728/NameService";
        String orbixHome = System.getProperty("orbix.home");

        String[] args = new String[6];
        args[0] = "-ORBdomain_name";
        args[1] = "orbix-domain";
        args[2] = "-ORBconfig_domains_dir";
        args[3] = orbixHome+"/etc/domains";
        args[4] = "-ORBInitRef";
        args[5] = "NameService=" + corbaloc;


        Properties props = new Properties();
        props.put("org.omg.CORBA.ORBClass", "com.iona.corba.art.artimpl.ORBImpl");
        props.put("org.omg.CORBA.ORBSingletonClass", "com.iona.corba.art.artimpl.ORBSingleton");

        ORB orb = ORB.init(args, props);

        org.omg.CORBA.Object nsObject = orb.string_to_object(corbaloc);
        NamingContextExtHelper.narrow(nsObject);

        orb.destroy();
    }
}
