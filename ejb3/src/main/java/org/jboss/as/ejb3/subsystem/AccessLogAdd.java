/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.CapabilityServiceBuilder;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.ejb3.component.AccessLogHolder;
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
        // Resolve path/relativeTo/rotateSuffix including defaults — used when destination=file.
        final ModelNode pathNode = AccessLogResourceDefinition.PATH.resolveModelAttribute(context, model);
        final String path = pathNode.isDefined() ? pathNode.asString() : null;
        final ModelNode relativeToNode = AccessLogResourceDefinition.RELATIVE_TO.resolveModelAttribute(context, model);
        final String relativeTo = relativeToNode.isDefined() ? relativeToNode.asString() : null;
        final ModelNode rotateSuffixNode = AccessLogResourceDefinition.ROTATE_SUFFIX.resolveModelAttribute(context, model);
        final String rotateSuffix = rotateSuffixNode.isDefined() ? rotateSuffixNode.asString() : "";
        // Track whether operator explicitly set any file attr — raw model, not resolved.
        final boolean fileAttrsExplicitlySet = model.hasDefined(EJB3SubsystemModel.PATH)
                || model.hasDefined(EJB3SubsystemModel.RELATIVE_TO)
                || model.hasDefined(EJB3SubsystemModel.ROTATE_SUFFIX);
        final String worker = AccessLogResourceDefinition.WORKER.resolveModelAttribute(context, model).asString();
        final boolean includeLocal = AccessLogResourceDefinition.INCLUDE_LOCAL.resolveModelAttribute(context, model).asBoolean();
        final boolean includeNodeName = AccessLogResourceDefinition.INCLUDE_NODE_NAME.resolveModelAttribute(context, model).asBoolean();
        final int queueLength = AccessLogResourceDefinition.QUEUE_LENGTH.resolveModelAttribute(context, model).asInt();

        // Parse the enabled-attributes list. UNDEFINED → all 13 tokens (default-all).
        final ModelNode attributesNode = model.get(EJB3SubsystemModel.ATTRIBUTES);
        final Set<AccessLogResourceDefinition.AttributeVocabulary> enabledAttributes;
        if (attributesNode.isDefined()) {
            final List<ModelNode> tokens = attributesNode.asList();
            enabledAttributes = EnumSet.noneOf(AccessLogResourceDefinition.AttributeVocabulary.class);
            for (ModelNode token : tokens) {
                final String name = token.asString();
                for (AccessLogResourceDefinition.AttributeVocabulary v : AccessLogResourceDefinition.AttributeVocabulary.values()) {
                    if (v.getToken().equals(name)) {
                        enabledAttributes.add(v);
                        break;
                    }
                }
            }
        } else {
            enabledAttributes = AccessLogService.ALL_ATTRIBUTES;
        }

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
        final Supplier<AccessLogHolder> holderSupplier = sb.requires(AccessLogHolder.ACCESS_LOG_HOLDER_SERVICE_NAME);
        final Supplier<XnioWorker> workerSupplier = sb.requiresCapability("org.wildfly.io.worker", XnioWorker.class, worker);
        final Supplier<PathManager> pathManagerSupplier = sb.requires(PathManager.SERVICE_DESCRIPTOR);

        final AccessLogService service = new AccessLogService(
                serviceConsumer, holderSupplier, workerSupplier, pathManagerSupplier,
                destination, path, relativeTo, rotateSuffix, fileAttrsExplicitlySet,
                enabledAttributes, includeLocal, includeNodeName, metadata, queueLength);
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
