package org.jboss.as.ejb3.deployment.processors;

import org.jboss.as.ee.component.Attachments;
import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.ejb3.aop.AOPMetaData;
import org.jboss.as.ejb3.deployment.EjbDeploymentAttachmentKeys;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.metadata.ejb.spec.EjbJarMetaData;
import org.jboss.modules.Module;

import java.util.List;

public class AOPDeploymentProcessor implements DeploymentUnitProcessor {
    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final EEModuleDescription moduleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);

        final EjbJarMetaData ejbJarMetaData = deploymentUnit.getAttachment(EjbDeploymentAttachmentKeys.EJB_JAR_METADATA);

        if (ejbJarMetaData != null && ejbJarMetaData.getAssemblyDescriptor() != null) {
            List<AOPMetaData> metaData = ejbJarMetaData.getAssemblyDescriptor().getAny(AOPMetaData.class);
            if (metaData != null) {
                for (AOPMetaData md : metaData) {

                }
            }
        }
    }

    @Override
    public void undeploy(DeploymentUnit deploymentUnit) {

    }
}
