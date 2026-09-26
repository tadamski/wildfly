/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.subsystem;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.ejb3.logging.EjbLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

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
