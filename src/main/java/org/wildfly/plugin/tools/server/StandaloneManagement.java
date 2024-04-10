/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import static org.jboss.as.controller.client.helpers.ClientConstants.CONTROLLER_PROCESS_STATE_STARTING;
import static org.jboss.as.controller.client.helpers.ClientConstants.CONTROLLER_PROCESS_STATE_STOPPING;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.wildfly.common.Assert;
import org.wildfly.plugin.tools.OperationExecutionException;

/**
 * A utility for executing management operations on standalone servers.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public class StandaloneManagement extends ServerManagement {
    private static final Logger LOGGER = Logger.getLogger(StandaloneManagement.class);

    /**
     * Reloads the server and returns immediately.
     *
     * @param client   the client used to execute the reload operation
     * @param reloadOp the reload operation to execute
     */
    public static void executeReload(final ModelControllerClient client, final ModelNode reloadOp) {
        try {
            final ModelNode result = client.execute(reloadOp);
            if (!Operations.isSuccessfulOutcome(result)) {
                throw new RuntimeException(String.format("Failed to reload the server with %s: %s", reloadOp,
                        Operations.getFailureDescription(result)));
            }
        } catch (IOException e) {
            final Throwable cause = e.getCause();
            if (!(cause instanceof ExecutionException) && !(cause instanceof CancellationException)) {
                throw new RuntimeException(e);
            } // else ignore, this might happen if the channel gets closed before we got the response
        }
    }

    /**
     * Gets the "server-state" for a standalone server.
     *
     * @param client the client used to communicate with the server
     *
     * @return the server-state or "failed" if an error occurred. A value of "unknown" is returned if the server is not a
     *             standalone server
     */
    public static String serverState(final ModelControllerClient client) {
        final String launchType = launchType(client);
        if ("STANDALONE".equalsIgnoreCase(launchType)) {
            try {
                final ModelNode response = client
                        .execute(Operations.createReadAttributeOperation(EMPTY_ADDRESS, "server-state"));
                return Operations.isSuccessfulOutcome(response) ? Operations.readResult(response).asString() : "failed";
            } catch (RuntimeException | IOException e) {
                LOGGER.tracef("Interrupted determining the server state", e);
            }
            return "failed";
        }
        return "unknown";
    }

    /**
     * Checks if the container status is "reload-required" and if it's the case executes reload and waits for completion.
     *
     * @param client the client used to execute the operation
     */
    public static void reloadIfRequired(final ModelControllerClient client, final long timeout) {
        final String launchType = launchType(client);
        if ("STANDALONE".equalsIgnoreCase(launchType)) {
            final String runningState = serverState(client);
            if ("reload-required".equalsIgnoreCase(runningState)) {
                executeReload(client, Operations.createOperation("reload"));
                try {
                    waitForStandalone(client, timeout);
                } catch (InterruptedException | TimeoutException e) {
                    throw new RuntimeException("Failed to reload the serve.", e);
                }
            }
        } else {
            LOGGER.warnf("Server type %s is not supported for a reload.", launchType);
        }
    }

    /**
     * Waits the given amount of time in seconds for a standalone server to start.
     *
     * @param client         the client used to communicate with the server
     * @param startupTimeout the time, in seconds, to wait for the server start
     *
     * @throws InterruptedException if interrupted while waiting for the server to start
     * @throws RuntimeException     if the process has died
     * @throws TimeoutException     if the timeout has been reached and the server is still not started
     */
    public static void waitForStandalone(final ModelControllerClient client, final long startupTimeout)
            throws InterruptedException, RuntimeException, TimeoutException {
        waitForStandalone(null, client, startupTimeout);
    }

    /**
     * Waits the given amount of time in seconds for a standalone server to start.
     * <p>
     * If the {@code process} is not {@code null} and a timeout occurs the process will be
     * {@linkplain Process#destroy() destroyed}.
     * </p>
     *
     * @param process        the Java process can be {@code null} if no process is available
     * @param client         the client used to communicate with the server
     * @param startupTimeout the time, in seconds, to wait for the server start
     *
     * @throws InterruptedException if interrupted while waiting for the server to start
     * @throws RuntimeException     if the process has died
     * @throws TimeoutException     if the timeout has been reached and the server is still not started
     */
    public static void waitForStandalone(final Process process, final ModelControllerClient client, final long startupTimeout)
            throws InterruptedException, RuntimeException, TimeoutException {
        Assert.checkNotNullParam("client", client);
        long timeout = startupTimeout * 1000;
        final long sleep = 100L;
        while (timeout > 0) {
            long before = System.currentTimeMillis();
            if (isStandaloneRunning(client))
                break;
            timeout -= (System.currentTimeMillis() - before);
            if (process != null && !process.isAlive()) {
                throw new RuntimeException(
                        String.format("The process has unexpectedly exited with code %d", process.exitValue()));
            }
            TimeUnit.MILLISECONDS.sleep(sleep);
            timeout -= sleep;
        }
        if (timeout <= 0) {
            if (process != null) {
                process.destroy();
            }
            throw new TimeoutException(String.format("The server did not start within %s seconds.", startupTimeout));
        }
    }

    /**
     * Checks to see if a standalone server is running.
     *
     * @param client the client used to communicate with the server
     *
     * @return {@code true} if the server is running, otherwise {@code false}
     */
    public static boolean isStandaloneRunning(final ModelControllerClient client) {
        try {
            final ModelNode response = client.execute(Operations.createReadAttributeOperation(EMPTY_ADDRESS, "server-state"));
            if (Operations.isSuccessfulOutcome(response)) {
                final String state = Operations.readResult(response).asString();
                return !CONTROLLER_PROCESS_STATE_STARTING.equals(state)
                        && !CONTROLLER_PROCESS_STATE_STOPPING.equals(state);
            }
        } catch (RuntimeException | IOException e) {
            LOGGER.trace("Interrupted determining if standalone is running", e);
        }
        return false;
    }

    /**
     * Shuts down a standalone server.
     *
     * @param client the client used to communicate with the server
     *
     * @throws IOException if an error occurs communicating with the server
     */
    public static void shutdownStandalone(final ModelControllerClient client) throws IOException {
        shutdownStandalone(client, 0);
    }

    /**
     * Shuts down a standalone server.
     *
     * @param client  the client used to communicate with the server
     * @param timeout the graceful shutdown timeout, a value of {@code -1} will wait indefinitely and a value of
     *                    {@code 0} will not attempt a graceful shutdown
     *
     * @throws IOException if an error occurs communicating with the server
     */
    public static void shutdownStandalone(final ModelControllerClient client, final int timeout) throws IOException {
        final ModelNode op = Operations.createOperation("shutdown");
        op.get("timeout").set(timeout);
        final ModelNode response = client.execute(op);
        if (Operations.isSuccessfulOutcome(response)) {
            while (true) {
                if (isStandaloneRunning(client)) {
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
