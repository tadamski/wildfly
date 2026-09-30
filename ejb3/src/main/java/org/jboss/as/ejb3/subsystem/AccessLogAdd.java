/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.CapabilityServiceBuilder;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.ejb3.logging.EjbLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.dmr.Property;
import org.jboss.msc.service.ServiceController;
import org.xnio.XnioWorker;

public class AccessLogAdd extends AbstractAddStepHandler {

    AccessLogAdd(AttributeDefinition... attributes) {
        super(attributes);
    }

    @Override
    protected void populateModel(OperationContext context, ModelNode operation, org.jboss.as.controller.registry.Resource resource) throws OperationFailedException {
        super.populateModel(context, operation, resource);
        ModelNode model = resource.getModel();
        validateDestinationAttributes(model);
    }

    @Override
    protected void performRuntime(final OperationContext context, final ModelNode operation, final ModelNode model)
            throws OperationFailedException {
        final String destination = AccessLogResourceDefinition.DESTINATION.resolveModelAttribute(context, model).asString();
        final String path = AccessLogResourceDefinition.PATH.resolveModelAttribute(context, model).asString();
        final String rotateSuffix = AccessLogResourceDefinition.ROTATE_SUFFIX.resolveModelAttribute(context, model).asString();
        final String worker = AccessLogResourceDefinition.WORKER.resolveModelAttribute(context, model).asString();
        final boolean includeNodeName = AccessLogResourceDefinition.INCLUDE_NODE_NAME.resolveModelAttribute(context, model).asBoolean();

        final ModelNode metadataNode = AccessLogResourceDefinition.METADATA.resolveModelAttribute(context, model);
        final Map<String, Object> metadata = new LinkedHashMap<>();
        if (metadataNode.isDefined()) {
            for (Property property : metadataNode.asPropertyList()) {
                metadata.put(property.getName(), property.getValue().asString());
            }
        }

        final CapabilityServiceBuilder<?> sb = context.getCapabilityServiceTarget()
                .addCapability(AccessLogResourceDefinition.ACCESS_LOG_CAPABILITY);
        final Consumer<AccessLogService> serviceConsumer = sb.provides(AccessLogResourceDefinition.ACCESS_LOG_CAPABILITY);
        final Supplier<XnioWorker> workerSupplier = sb.requiresCapability("org.wildfly.io.worker", XnioWorker.class, worker);

        final AccessLogService service = new AccessLogService(
                serviceConsumer, workerSupplier,
                destination, path, rotateSuffix, includeNodeName, metadata);
        sb.setInstance(service)
                .setInitialMode(ServiceController.Mode.ACTIVE)
                .install();
    }

    static void validateDestinationAttributes(ModelNode model) throws OperationFailedException {
        if (model.hasDefined(EJB3SubsystemModel.DESTINATION)) {
            ModelNode destNode = model.get(EJB3SubsystemModel.DESTINATION);
            if (destNode.getType() != ModelType.EXPRESSION) {
                String dest = destNode.asString();
                if ("console".equals(dest) || "logging".equals(dest)) {
                    if (model.hasDefined(EJB3SubsystemModel.PATH)
                            || model.hasDefined(EJB3SubsystemModel.RELATIVE_TO)
                            || model.hasDefined(EJB3SubsystemModel.ROTATE_SUFFIX)) {
                        throw EjbLogger.ROOT_LOGGER.fileAttributesNotAllowedForDestination();
                    }
                }
            }
        }
    }
}
