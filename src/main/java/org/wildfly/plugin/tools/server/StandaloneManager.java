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
public class StandaloneManager extends AbstractServerManager {
    private static final Logger LOGGER = Logger.getLogger(StandaloneManager.class);
    private final ModelControllerClient client;

    protected StandaloneManager(final ProcessHandle process, final ModelControllerClient client) {
        super(process, client);
        this.client = client;
    }

    @Override
    public ModelControllerClient client() {
        return client;
    }

    @Override
    public String serverState() {
        try {
            @SuppressWarnings("resource")
            final ModelNode response = client()
                    .execute(Operations.createReadAttributeOperation(CommonOperations.EMPTY_ADDRESS, "server-state"));
            return Operations.isSuccessfulOutcome(response) ? Operations.readResult(response).asString() : "failed";
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
    public void reloadIfRequired() throws IOException {
        reloadIfRequired(10L, TimeUnit.SECONDS);
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
                        throw new RuntimeException(String.format("Failed to reload server within %d %s.", timeout, unit.name()
                                .toLowerCase(Locale.ROOT)));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException("Failed to reload the server.", e);
                }
            }
        } else {
            LOGGER.warnf("Cannot reload and wait for the server to start with a server type of %s.", launchType);
        }
    }

    @Override
    public boolean isRunning() {
        return CommonOperations.isStandaloneRunning(client);
    }

    @Override
    public void shutdown() throws IOException {
        shutdown(0);
    }

    @Override
    public void shutdown(final long timeout) throws IOException {
        final ModelNode op = Operations.createOperation("shutdown");
        op.get("timeout").set(timeout);
        final ModelNode response = client.execute(op);
        if (Operations.isSuccessfulOutcome(response)) {
            while (true) {
                if (isRunning()) {
                    Thread.onSpinWait();
                } else {
                    break;
                }
            }
        } else {
            throw new OperationExecutionException(op, response);
        }
    }
}
