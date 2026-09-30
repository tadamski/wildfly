/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.ejb3.subsystem;

import org.jboss.as.controller.AbstractRuntimeOnlyHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PropertiesAttributeDefinition;
import org.jboss.as.controller.ReloadRequiredRemoveStepHandler;
import org.jboss.as.controller.ReloadRequiredWriteAttributeHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.StringListAttributeDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.operations.validation.EnumValidator;
import org.jboss.as.controller.operations.validation.StringAllowedValuesValidator;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

import java.util.EnumSet;

public class AccessLogResourceDefinition extends SimpleResourceDefinition {

    static final RuntimeCapability<Void> ACCESS_LOG_CAPABILITY =
            RuntimeCapability.Builder.of("org.wildfly.ejb3.access-log").build();

    static final SimpleAttributeDefinition DESTINATION =
            new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.DESTINATION, ModelType.STRING, true)
                    .setAllowExpression(true)
                    .setValidator(new StringAllowedValuesValidator("console", "logging", "file"))
                    .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
                    .setDefaultValue(new ModelNode("file"))
                    .build();

    static final SimpleAttributeDefinition PATH =
            new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.PATH, ModelType.STRING, true)
                    .setAllowExpression(true)
                    .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
                    .setDefaultValue(new ModelNode("ejb-access.log"))
                    .build();

    static final SimpleAttributeDefinition RELATIVE_TO =
            new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.RELATIVE_TO, ModelType.STRING, true)
                    .setAllowExpression(true)
                    .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
                    .setDefaultValue(new ModelNode("jboss.server.log.dir"))
                    .build();

    static final SimpleAttributeDefinition ROTATE_SUFFIX =
            new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.ROTATE_SUFFIX, ModelType.STRING, true)
                    .setAllowExpression(true)
                    .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
                    .setDefaultValue(new ModelNode(".yyyy-MM-dd")) // "" = no rotation
                    .build();

    static final SimpleAttributeDefinition WORKER =
            new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.WORKER, ModelType.STRING, true)
                    .setAllowExpression(false)
                    .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
                    .setDefaultValue(new ModelNode("default"))
                    .setCapabilityReference("org.wildfly.io.worker")
                    .build();

    static final SimpleAttributeDefinition INCLUDE_LOCAL =
            new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.INCLUDE_LOCAL, ModelType.BOOLEAN, true)
                    .setAllowExpression(true)
                    .setDefaultValue(ModelNode.FALSE)
                    .build();

    static final SimpleAttributeDefinition INCLUDE_NODE_NAME =
            new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.INCLUDE_NODE_NAME, ModelType.BOOLEAN, true)
                    .setAllowExpression(true)
                    .setDefaultValue(ModelNode.TRUE)
                    .build();

    public enum AttributeVocabulary {
        TIMESTAMP("timestamp"),
        APP("app"),
        MODULE("module"),
        BEAN("bean"),
        VIEW("view"),
        METHOD("method"),
        USER("user"),
        REMOTE_ADDRESS("remote-address"),
        LOCAL_ADDRESS("local-address"),
        INVOCATION_TYPE("invocation-type"),
        OUTCOME("outcome"),
        DURATION("duration"),
        NODE_NAME("node-name");

        private final String token;

        AttributeVocabulary(String token) {
            this.token = token;
        }

        public String getToken() {
            return token;
        }

        @Override
        public String toString() {
            return token;
        }
    }

    public static final StringListAttributeDefinition ATTRIBUTES = new StringListAttributeDefinition.Builder(EJB3SubsystemModel.ATTRIBUTES)
            .setAllowExpression(false)
            .setRequired(false)
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .setElementValidator(new EnumValidator<>(AttributeVocabulary.class, EnumSet.allOf(AttributeVocabulary.class)))
            .build();

    public static final PropertiesAttributeDefinition METADATA = new PropertiesAttributeDefinition.Builder(EJB3SubsystemModel.METADATA, true)
            .setAllowExpression(true)
            .build();

    public static final SimpleAttributeDefinition EVENTS_LOGGED = new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.EVENTS_LOGGED, ModelType.LONG)
            .setStorageRuntime()
            .build();

    public static final SimpleAttributeDefinition EVENTS_DROPPED = new SimpleAttributeDefinitionBuilder(EJB3SubsystemModel.EVENTS_DROPPED, ModelType.LONG)
            .setStorageRuntime()
            .build();

    private static final AttributeDefinition[] RESTART_RESOURCE_SERVICES_ATTRIBUTES = new AttributeDefinition[]{
            DESTINATION, PATH, RELATIVE_TO, ROTATE_SUFFIX, WORKER, ATTRIBUTES
    };

    private static final AttributeDefinition[] RESTART_NONE_ATTRIBUTES = new AttributeDefinition[]{
            INCLUDE_LOCAL, INCLUDE_NODE_NAME, METADATA
    };

    private static final AttributeDefinition[] ALL_CONFIG_ATTRIBUTES = new AttributeDefinition[]{
            DESTINATION, PATH, RELATIVE_TO, ROTATE_SUFFIX, WORKER, INCLUDE_LOCAL, INCLUDE_NODE_NAME, ATTRIBUTES, METADATA
    };

    AccessLogResourceDefinition() {
        super(new Parameters(EJB3SubsystemModel.ACCESS_LOG_PATH, EJB3Extension.getResourceDescriptionResolver(EJB3SubsystemModel.SERVICE + "." + EJB3SubsystemModel.ACCESS_LOG))
                .setAddHandler(new AccessLogAdd(ALL_CONFIG_ATTRIBUTES))
                .setRemoveHandler(ReloadRequiredRemoveStepHandler.INSTANCE)
                .addCapabilities(ACCESS_LOG_CAPABILITY));
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        super.registerAttributes(resourceRegistration);

        ReloadRequiredWriteAttributeHandler reloadHandler = new ReloadRequiredWriteAttributeHandler(RESTART_RESOURCE_SERVICES_ATTRIBUTES) {
            @Override
            protected void validateUpdatedModel(OperationContext context, Resource resource) throws OperationFailedException {
                super.validateUpdatedModel(context, resource);
                AccessLogAdd.validateDestinationAttributes(resource.getModel());
            }
        };

        for (AttributeDefinition attr : RESTART_RESOURCE_SERVICES_ATTRIBUTES) {
            resourceRegistration.registerReadWriteAttribute(attr, null, reloadHandler);
        }

        for (AttributeDefinition attr : RESTART_NONE_ATTRIBUTES) {
            resourceRegistration.registerReadWriteAttribute(attr, null, new org.jboss.as.controller.ModelOnlyWriteAttributeHandler(attr));
        }

        resourceRegistration.registerMetric(EVENTS_LOGGED, METRIC_HANDLER);
        resourceRegistration.registerMetric(EVENTS_DROPPED, METRIC_HANDLER);
    }

    /**
     * The live service instance, published by {@link AccessLogService} on start/stop.
     * Singleton resource — one instance per server, so a static volatile is safe.
     */
    static volatile AccessLogService LIVE_SERVICE = null;

    private static final AbstractRuntimeOnlyHandler METRIC_HANDLER = new AbstractRuntimeOnlyHandler() {
        @Override
        protected void executeRuntimeStep(OperationContext context, ModelNode operation) throws OperationFailedException {
            final AccessLogService service = LIVE_SERVICE;
            if (service == null) {
                return;
            }
            final String name = operation.get(
                    org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME).asString();
            final long value = EJB3SubsystemModel.EVENTS_LOGGED.equals(name)
                    ? service.getEventsLogged()
                    : service.getEventsDropped();
            context.getResult().set(value);
        }
    };
}
