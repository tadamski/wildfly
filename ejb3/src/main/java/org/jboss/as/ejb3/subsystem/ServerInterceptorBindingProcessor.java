package org.jboss.as.ejb3.subsystem;

import org.jboss.as.ee.component.Attachments;
import org.jboss.as.ee.component.ComponentDescription;
import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.ejb3.component.EJBComponentDescription;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.modules.Module;

public class ServerInterceptorBindingProcessor implements DeploymentUnitProcessor {

    private final String interceptorClass;

    ServerInterceptorBindingProcessor(String interceptorClass){
        this.interceptorClass = interceptorClass;
    }


    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {

        DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();

        final EEModuleDescription eeModuleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);

        for (final ComponentDescription componentDescription : eeModuleDescription.getComponentDescriptions()) {
            if (!(componentDescription instanceof EJBComponentDescription)) {
                continue;
            }
            final EJBComponentDescription ejbComponentDescription = (EJBComponentDescription) componentDescription;
        }

    }

    @Override
    public void undeploy(DeploymentUnit context) {

    }
}
