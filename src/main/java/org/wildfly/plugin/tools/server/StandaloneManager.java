/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.wildfly.plugin.tools.OperationExecutionException;

/**
 * A utility for managing a standalone server.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public class StandaloneManager extends AbstractServerManager<ModelControllerClient> {
    private static final Logger LOGGER = Logger.getLogger(StandaloneManager.class);

    StandaloneManager(final ProcessHandle process, final ModelControllerClient client,
            final boolean shutdownOnClose) {
        super(process, client, shutdownOnClose);
    }

    @Override
    public String serverState() {
        try {
            return executeOperation(Operations.createReadAttributeOperation(CommonOperations.EMPTY_ADDRESS, "server-state"))
                    .asString();
        } catch (OperationExecutionException e) {
            LOGGER.debugf("Checking the server state has failed: %s", Operations.getFailureDescription(e.getExecutionResult()));
        } catch (RuntimeException | IOException e) {
            LOGGER.tracef("Interrupted determining the server state", e);
        }
        return "failed";
    }

    @Override
    public void executeReload() throws IOException {
        executeReload(Operations.createOperation("reload"));
    }

    @Override
    public void reloadIfRequired(final long timeout, final TimeUnit unit) throws IOException {
        final String launchType = launchType();
        if ("STANDALONE".equalsIgnoreCase(launchType)) {
            final String runningState = serverState();
            if ("reload-required".equalsIgnoreCase(runningState)) {
                executeReload();
                try {
                    if (!waitFor(timeout, unit)) {
                        throw new ServerManagerException("Failed to reload server within %d %s.", timeout, unit.name()
                                .toLowerCase(Locale.ROOT));
                    }
                } catch (InterruptedException e) {
                    throw new ServerManagerException(e, "Failed to reload the server.");
                }
            }
        } else {
            LOGGER.warnf("Cannot reload and wait for the server to start with a server type of %s.", launchType);
        }
    }

    @Override
    public boolean isRunning() {
        if (process != null) {
            return process.isAlive() && CommonOperations.isStandaloneRunning(client());
        }
        return CommonOperations.isStandaloneRunning(client());
    }

    @Override
    void internalShutdown(final ModelControllerClient client, final long timeout) throws IOException {
        final ModelNode op = Operations.createOperation("shutdown");
        op.get("timeout").set(timeout);
        executeOperation(client, op);
    }
}
