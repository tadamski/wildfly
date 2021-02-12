/*
 * JBoss, Home of Professional Open Source
 * Copyright 2018, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.test.integration.ejb.singleton.deployment;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.test.integration.management.base.AbstractCliTestBase;
import org.jboss.as.test.integration.management.cli.DeployURLTestCase;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.impl.base.exporter.zip.ZipExporterImpl;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * @author Bartosz Spyrko-Smietanko
 */
@RunWith(Arquillian.class)
@RunAsClient
public class Bleble extends AbstractCliTestBase {

    public static EnterpriseArchive deployment(boolean includeInOrder) {
        EnterpriseArchive ear = ShrinkWrap.create(EnterpriseArchive.class, "TestEar.ear");

        JavaArchive ejb1 = ShrinkWrap.create(JavaArchive.class, "ejb1.jar");
        ejb1.addClass(SingletonOne.class);
        JavaArchive ejb2 = ShrinkWrap.create(JavaArchive.class, "ejb2.jar");
        ejb2.addClass(HelloBean.class);
        ejb2.addClass(HelloRemote.class);
        WebArchive war = ShrinkWrap.create(WebArchive.class, "test.war");
        war.addClass(HelloServlet.class);
        war.addAsWebResource(new StringAsset("Hello"), "index.html");
        war.addAsWebInfResource(new StringAsset("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd\" " +
                "version=\"3.0\"></web-app>"), "web.xml");

        ear.addAsModule(ejb1);
        ear.addAsModule(ejb2);
        ear.addAsModule(war);
        if (includeInOrder) {
            ear.setApplicationXML(new StringAsset("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<application>" +
                    "<initialize-in-order>true</initialize-in-order>" +
                    "<module><ejb>ejb2.jar</ejb></module>" +
                    "<module><web><web-uri>test.war</web-uri><context-root>test</context-root></web></module>" +
                    "<module><ejb>ejb1.jar</ejb></module>" +
                    "</application>"));
        }

        return ear;
    }

    // dummy deployment to keep Arquillian happy - not used in test
    @Deployment
    public static Archive<?> getDeployment() {
        JavaArchive ja = ShrinkWrap.create(JavaArchive.class, "dummy.jar");
        ja.addClass(DeployURLTestCase.class);
        return ja;
    }

    private static File unorderedEarFile;
    private static File inOrderEarFile;

    @BeforeClass
    public static void before() throws Exception {
        unorderedEarFile = exportEar(false, "TestEar.ear");
        inOrderEarFile = exportEar(true, "TestEarInOrder.ear");

        AbstractCliTestBase.initCLI();
    }

    private static File exportEar(boolean inOrder, String name) {
        EnterpriseArchive ear = deployment(inOrder);
        String tempDir = TestSuiteEnvironment.getTmpDir();
        File earFile = new File(tempDir + File.separator + name);
        new ZipExporterImpl(ear).exportTo(earFile, true);
        return earFile;
    }

    @AfterClass
    public static void after() throws Exception {
        unorderedEarFile.delete();
        inOrderEarFile.delete();
        AbstractCliTestBase.closeCLI();
    }

    @Test
    public void test() throws Exception {
        deploy(inOrderEarFile);
    }

    private void deploy(File earFile) throws Exception {
        try {
            cli.sendLine("deploy --url=" + earFile.toURI().toURL().toExternalForm() + " --name=" + earFile.getName() + " --headers={rollback-on-runtime-failure=false}");
        } finally {
            cli.sendLine("undeploy " + earFile.getName());
        }
    }

//    @Test
//    @SuppressWarnings({"unchecked"})
//    public void testEjbInAnotherModuleShouldFail() throws Exception {
//        testEjbInAnotherModuleShouldFail(unorderedEarFile);
//    }
//
//    @Test
//    @SuppressWarnings({"unchecked"})
//    public void testInOrderDeploymentEjbInAnotherModuleShouldFail() throws Exception {
//        testEjbInAnotherModuleShouldFail(inOrderEarFile);
//    }
//
//    private void testEjbInAnotherModuleShouldFail(File earFile) throws Exception {
//        try {
//            cli.sendLine("deploy --url=" + earFile.toURI().toURL().toExternalForm() + " --name=" + earFile.getName() + " --headers={rollback-on-runtime-failure=false}");
//
//            final StatelessEJBLocator<HelloRemote> locator = new StatelessEJBLocator(HelloRemote.class, "TestEar", "ejb2", HelloBean.class.getSimpleName(), "");
//            final HelloRemote proxy = EJBClient.createProxy(locator);
//            Assert.assertNotNull("Received a null proxy", proxy);
//            try {
//                proxy.hello();
//                Assert.fail("Call should have failed");
//            } catch (NoSuchEJBException e) {
//                Assert.assertTrue(e.getMessage(), e.getMessage().startsWith("EJBCLIENT000079"));
//            }
//        } finally {
//            cli.sendLine("undeploy " + earFile.getName());
//        }
//    }
//
//    @Test
//    public void testWebModuleShouldFail(@ArquillianResource URL url) throws Exception {
//        testWebModuleShouldFail(url, unorderedEarFile);
//    }
//
//    @Test
//    public void testInOrderDeploymentWebModuleShouldFail(@ArquillianResource URL url) throws Exception {
//        testWebModuleShouldFail(url, inOrderEarFile);
//    }
//
//    private void testWebModuleShouldFail(URL url, File earFile) throws Exception {
//        try {
//            cli.sendLine("deploy --url=" + earFile.toURI().toURL().toExternalForm() + " --name=" + earFile.getName() + " --headers={rollback-on-runtime-failure=false}");
//
//            try(CloseableHttpClient client = HttpClientBuilder.create().build()) {
//                final HttpGet get = new HttpGet(url.toExternalForm() + "/test/index.html");
//                Assert.assertEquals(500, client.execute(get).getStatusLine().getStatusCode());
//            }
//        } finally {
//            cli.sendLine("undeploy " + earFile.getName());
//        }
//    }
}

