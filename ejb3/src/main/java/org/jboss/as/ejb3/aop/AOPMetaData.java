package org.jboss.as.ejb3.aop;

import org.jboss.metadata.ejb.parser.jboss.ejb3.AbstractEJBBoundMetaData;

public class AOPMetaData extends AbstractEJBBoundMetaData {
    public String interceptorModule;
    public String interceptorClass;

    public String getInterceptorModule() {
        return interceptorModule;
    }

    public void setInterceptorModule(String interceptorModule) {
        this.interceptorModule = interceptorModule;
    }

    public String getInterceptorClass() {
        return interceptorClass;
    }

    public void setInterceptorClass(String interceptorClass) {
        this.interceptorClass = interceptorClass;
    }
}
