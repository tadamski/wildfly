/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019, Red Hat, Inc., and individual contributors
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

package org.jboss.as.ejb3.subsystem;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.as.controller.*;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.ejb3.deployment.ServerInterceptorDependencyDeploymentUnitProcessor;
import org.jboss.as.ejb3.deployment.processors.EjbIIOPDeploymentUnitProcessor;
import org.jboss.as.ejb3.logging.EjbLogger;
import org.jboss.as.ejb3.remote.LocalTransportProvider;
import org.jboss.as.ejb3.remote.RemotingProfileService;
import org.jboss.as.remoting.AbstractOutboundConnectionService;
import org.jboss.as.server.AbstractDeploymentChainStep;
import org.jboss.as.server.DeploymentProcessorTarget;
import org.jboss.as.server.deployment.Phase;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.ejb.client.EJBClientContext;
import org.jboss.ejb.client.EJBTransportProvider;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.value.InjectedValue;
import org.jboss.remoting3.RemotingOptions;
import org.wildfly.discovery.AttributeValue;
import org.wildfly.discovery.ServiceURL;
import org.xnio.Option;
import org.xnio.OptionMap;
import org.xnio.Options;

import static org.jboss.as.ejb3.logging.EjbLogger.ROOT_LOGGER;

/**
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 */
public class ServerInterceptorAdd extends AbstractBoottimeAddStepHandler {

    static final ServerInterceptorAdd INSTANCE = new ServerInterceptorAdd();

    private ServerInterceptorAdd() {
        super(RemotingProfileResourceDefinition.ATTRIBUTES.values());
    }

    @Override
    protected void performBoottime(final OperationContext context, final ModelNode operation, final ModelNode model) throws OperationFailedException {
        final String module = ServerInterceptorDefinition.MODULE.resolveModelAttribute(context, model).asString();
        context.addStep(new AbstractDeploymentChainStep() {
            protected void execute(DeploymentProcessorTarget processorTarget) {
                processorTarget.addDeploymentProcessor(EJB3Extension.SUBSYSTEM_NAME, Phase.DEPENDENCIES, Phase.DEPENDENCIES_EJB_INTERCEPTORS,
                        new ServerInterceptorDependencyDeploymentUnitProcessor(module));
            }
        }, OperationContext.Stage.RUNTIME);

    }

}
