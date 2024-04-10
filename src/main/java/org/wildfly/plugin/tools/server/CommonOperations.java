/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import static org.jboss.as.controller.client.helpers.ClientConstants.CONTROLLER_PROCESS_STATE_STARTING;
import static org.jboss.as.controller.client.helpers.ClientConstants.CONTROLLER_PROCESS_STATE_STOPPING;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.as.controller.client.helpers.domain.ServerIdentity;
import org.jboss.as.controller.client.helpers.domain.ServerStatus;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.wildfly.plugin.tools.OperationExecutionException;

/**
 * Commonly shared management operations.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class CommonOperations {
    static final ModelNode EMPTY_ADDRESS = new ModelNode().setEmptyList();
    private static final Logger LOGGER = Logger.getLogger(CommonOperations.class);

    static {
        EMPTY_ADDRESS.protect();
    }

    /**
     * Determines the address for the host being used.
     *
     * @return the address of the host
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation used to determine the host name fails
     */
    static ModelNode determineHostAddress(final ModelControllerClient client)
            throws IOException, OperationExecutionException {
        final ModelNode op = Operations.createReadAttributeOperation(EMPTY_ADDRESS, "local-host-name");
        ModelNode response = client.execute(op);
        if (Operations.isSuccessfulOutcome(response)) {
            return Operations.createAddress("host", Operations.readResult(response).asString());
        }
        throw new OperationExecutionException(op, response);
    }

    /**
     * Checks if a standalone server is running.
     *
     * @param client the client used to check the server state
     *
     * @return {@code true} if a server is running, otherwise {@code false}
     */
    static boolean isStandaloneRunning(final ModelControllerClient client) {
        try {
            final ModelNode response = client
                    .execute(Operations.createReadAttributeOperation(new ModelNode().setEmptyList(), "server-state"));
            if (Operations.isSuccessfulOutcome(response)) {
                final String state = Operations.readResult(response).asString();
                return !CONTROLLER_PROCESS_STATE_STARTING.equals(state)
                        && !CONTROLLER_PROCESS_STATE_STOPPING.equals(state);
            }
        } catch (RuntimeException | IOException e) {
            Logger.getLogger(ServerManager.class).trace("Interrupted determining if server is running", e);
        }
        return false;
    }

    static boolean isDomainRunning(final ModelControllerClient client, final boolean shutdown) {
        final DomainClient domainClient = (client instanceof DomainClient) ? (DomainClient) client
                : DomainClient.Factory.create(client);
        try {
            // Check for admin-only
            final ModelNode hostAddress = determineHostAddress(domainClient);
            final Operations.CompositeOperationBuilder builder = Operations.CompositeOperationBuilder.create()
                    .addStep(Operations.createReadAttributeOperation(hostAddress, "running-mode"))
                    .addStep(Operations.createReadAttributeOperation(hostAddress, "host-state"));
            ModelNode response = domainClient.execute(builder.build());
            if (Operations.isSuccessfulOutcome(response)) {
                response = Operations.readResult(response);
                if ("ADMIN_ONLY".equals(Operations.readResult(response.get("step-1")).asString())) {
                    if (Operations.isSuccessfulOutcome(response.get("step-2"))) {
                        final String state = Operations.readResult(response).asString();
                        return !CONTROLLER_PROCESS_STATE_STARTING.equals(state)
                                && !CONTROLLER_PROCESS_STATE_STOPPING.equals(state);
                    }
                }
            }
            final Map<ServerIdentity, ServerStatus> servers = new HashMap<>();
            final Map<ServerIdentity, ServerStatus> statuses = domainClient.getServerStatuses();
            for (ServerIdentity id : statuses.keySet()) {
                final ServerStatus status = statuses.get(id);
                switch (status) {
                    case DISABLED:
                    case STARTED: {
                        servers.put(id, status);
                        break;
                    }
                }
            }
            if (shutdown) {
                return statuses.isEmpty();
            }
            return statuses.size() == servers.size();
        } catch (Exception e) {
            LOGGER.trace("Interrupted determining if domain is running", e);
        }
        return false;
    }
}
