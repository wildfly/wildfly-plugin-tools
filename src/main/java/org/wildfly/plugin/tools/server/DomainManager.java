/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.logging.Logger;
import org.wildfly.plugin.tools.OperationExecutionException;

/**
 * A utility for executing management operations on domain servers.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public class DomainManager extends AbstractServerManager<DomainClient> {
    private static final Logger LOGGER = Logger.getLogger(DomainManager.class);

    DomainManager(final Process process, final ProcessHandle processHandle, final DomainClient client,
            final boolean shutdownOnClose) {
        super(process, processHandle, client, shutdownOnClose);
    }

    @Override
    public String serverState() {
        try {
            return executeOperation(Operations.createReadAttributeOperation(determineHostAddress(), "host-state"))
                    .asString();
        } catch (OperationExecutionException e) {
            LOGGER.debugf("Checking the server state has failed: %s", Operations.getFailureDescription(e.getExecutionResult()));
        } catch (RuntimeException | IOException e) {
            LOGGER.tracef("Interrupted determining the server state", e);
        }
        return "failed";
    }

    /**
     * Determines the address for the host being used.
     *
     * @return the address of the host
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation used to determine the host name fails
     */
    public ModelNode determineHostAddress() throws OperationExecutionException, IOException {
        return CommonOperations.determineHostAddress(client());
    }

    /**
     * Checks to see if the domain is running. If the server is not in admin only mode each servers running state is
     * checked. If any server is not in a started state the domain is not considered to be running.
     *
     * @return {@code true} if the server is in a running state, otherwise {@code false}
     */
    @Override
    public boolean isRunning() {
        if (processHandle != null) {
            return processHandle.isAlive() && CommonOperations.isDomainRunning(client(), false);
        }
        return CommonOperations.isDomainRunning(client(), false);
    }

    @Override
    public void executeReload() throws IOException, OperationExecutionException {
        executeReload(Operations.createOperation("reload-servers"));
    }

    @Override
    public void reloadIfRequired(final long timeout, final TimeUnit unit) throws IOException {
        final String launchType = launchType();
        if ("DOMAIN".equalsIgnoreCase(launchType)) {
            final Map<String, ModelNode> steps = new HashMap<>();
            Operations.CompositeOperationBuilder builder = Operations.CompositeOperationBuilder.create();
            int stepCounter = 1;
            final ModelNode hostAddress = determineHostAddress();
            for (var entry : client.getServerStatuses().entrySet()) {
                final ModelNode address = hostAddress.clone().add("server", entry.getKey().getServerName());
                final ModelNode op = Operations.createReadAttributeOperation(address, "server-state");
                builder.addStep(op);
                // We will simply record the step and address to have a mapping for the reload commands
                steps.put("step-" + stepCounter++, address);
            }

            // Execute the operation
            final ModelNode result = executeOperation(builder.build());
            // Create a new builder for each reload operation
            builder = Operations.CompositeOperationBuilder.create();
            for (Property serverResult : result.asPropertyList()) {
                if (ClientConstants.CONTROLLER_PROCESS_STATE_RELOAD_REQUIRED
                        .equals(Operations.readResult(serverResult.getValue())
                                .asString())) {
                    final ModelNode address = steps.get(serverResult.getName());
                    builder.addStep(Operations.createOperation("reload", address));
                }
            }
            executeOperation(builder.build());
            try {
                if (!waitFor(timeout, unit)) {
                    throw new ServerManagerException("Failed to reload servers within %d %s.", timeout, unit.name()
                            .toLowerCase(Locale.ROOT));
                }
            } catch (InterruptedException e) {
                throw new ServerManagerException(e, "Failed to reload the servers.");
            }
        } else {
            LOGGER.warnf("Cannot reload and wait for the server to start with a server type of %s.", launchType);
        }
    }

    @Override
    void internalShutdown(final ModelControllerClient client, final long timeout) throws IOException {
        // Note the following two operations used to shutdown a domain don't seem to work well in a composite operation.
        // The operation occasionally sees a java.util.concurrent.CancellationException because the operation client
        // is likely closed before the AsyncFuture.get() is complete. Using a non-composite operation doesn't seem to
        // have this issue.
        // First shutdown the servers
        final ModelNode stopServersOp = Operations.createOperation("stop-servers");
        stopServersOp.get("blocking").set(true);
        stopServersOp.get("timeout").set(timeout);
        executeOperation(client, stopServersOp);

        // Now shutdown the host
        final ModelNode address = CommonOperations.determineHostAddress(client);
        final ModelNode shutdownOp = Operations.createOperation("shutdown", address);
        executeOperation(client, shutdownOp);
    }
}
