package org.jboss.as.test.integration.ejb.singleton.deployment;

import org.jboss.ejb.client.EJBClient;
import org.jboss.ejb.client.StatelessEJBLocator;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebServlet;
import java.io.IOException;

@WebServlet(name = "HelloServlet", urlPatterns = {"/test"}, loadOnStartup = 1)
public class HelloServlet implements Servlet {
    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        System.out.println("BAJOBONGO STARTUJE SERWLET");
        final StatelessEJBLocator<HelloRemote> locator = new StatelessEJBLocator(HelloRemote.class, "TestEarInOrder", "ejb2", HelloBean.class.getSimpleName(), "");
        final HelloRemote proxy = EJBClient.createProxy(locator);
        System.out.println("BAJOBONGO DOSTALEM PROXY");
        try {
            Thread.sleep(7000);
        } catch(InterruptedException e){}
        try {
            System.out.println("BAJOBONGO TERAZ BEDE WOLAL");
            proxy.hello();
        } catch (Exception e) {
            System.out.println("BAJOBONGO POLECIAL WYJATEK "+e);
            e.printStackTrace();
        }
    }

    @Override
    public ServletConfig getServletConfig() {
        return null;
    }

    @Override
    public void service(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {

    }

    @Override
    public String getServletInfo() {
        return null;
    }

    @Override
    public void destroy() {

    }
}
