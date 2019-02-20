package org.jboss.as.ejb3.aop;

import org.jboss.invocation.ImmediateInterceptorFactory;
import org.jboss.invocation.Interceptor;
import org.jboss.invocation.InterceptorContext;
import org.jboss.invocation.InterceptorFactory;

public class AOPInterceptor implements Interceptor {
    public static final InterceptorFactory FACTORY = new ImmediateInterceptorFactory(new AOPInterceptor());

    @Override
    public Object processInvocation(InterceptorContext invocationContext) throws Exception {
        System.out.println("IDZIE INTERCEPTOR");
        return invocationContext.proceed();
    }
}
