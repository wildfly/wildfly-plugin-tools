/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.wildfly.common.Assert;
import org.wildfly.plugin.tools.server.DomainManagement;
import org.wildfly.plugin.tools.server.ServerManagement;
import org.wildfly.plugin.tools.server.StandaloneManagement;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 *
 * @deprecated Use the {@link ServerManagement}, {@link StandaloneManagement} and {@link DomainManagement} utilities as
 *                 replacements.
 *
 * @see ServerManagement
 * @see StandaloneManagement
 * @see DomainManagement
 */
@SuppressWarnings("unused")
@Deprecated(forRemoval = true, since = "1.0.1.Final")
public class ServerHelper {

    /**
     * Checks whether or not the directory is a valid home directory for a server.
     * <p>
     * This validates the path is not {@code null}, exists, is a directory and contains a {@code jboss-modules.jar}.
     * </p>
     *
     * @param path the path to validate
     *
     * @return {@code true} if the path is valid otherwise {@code false}
     */
    public static boolean isValidHomeDirectory(final Path path) {
        return ServerManagement.isValidHomeDirectory(path);
    }

    /**
     * Checks whether or not the directory is a valid home directory for a server.
     * <p>
     * This validates the path is not {@code null}, exists, is a directory and contains a {@code jboss-modules.jar}.
     * </p>
     *
     * @param path the path to validate
     *
     * @return {@code true} if the path is valid otherwise {@code false}
     */
    public static boolean isValidHomeDirectory(final String path) {
        return ServerManagement.isValidHomeDirectory(path);
    }

    /**
     * Returns the description of the running container.
     *
     * @param client the client used to query the server
     *
     * @return the description of the running container
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation used to query the container fails
     * @see ContainerDescription#lookup(ModelControllerClient)
     */
    public static ContainerDescription getContainerDescription(final ModelControllerClient client)
            throws IOException, OperationExecutionException {
        return ContainerDescription.lookup(Assert.checkNotNullParam("client", client));
    }

    /**
     * Checks if the container status is "reload-required" and if it's the case executes reload and waits for completion.
     *
     * @param client the client used to execute the operation
     */
    public static void reloadIfRequired(final ModelControllerClient client, final long timeout) {
        StandaloneManagement.reloadIfRequired(client, timeout);
    }

    /**
     * Reloads the server and returns immediately.
     *
     * @param client   the client used to execute the reload operation
     * @param reloadOp the reload operation to execute
     */
    public static void executeReload(final ModelControllerClient client, final ModelNode reloadOp) {
        StandaloneManagement.executeReload(client, reloadOp);
    }

    /**
     * Determines the servers "launch-type".
     *
     * @param client the client used to communicate with the server
     * @return the servers launch-type or "unknown" if it could not be determined
     */
    public static String launchType(final ModelControllerClient client) {
        return ServerManagement.launchType(client);
    }

    /**
     * Gets the "server-state" for a standalone server.
     *
     * @param client the client used to communicate with the server
     * @return the server-state or "failed" if an error occurred. A value of "unknown" is returned if the server is not a
     *             standalone server
     */
    public static String serverState(final ModelControllerClient client) {
        return StandaloneManagement.serverState(client);
    }

    /**
     * Waits the given amount of time in seconds for a managed domain to start. A domain is considered started when each
     * of the servers in the domain are started unless the server is disabled.
     *
     * @param client         the client used to communicate with the server
     * @param startupTimeout the time, in seconds, to wait for the server start
     *
     * @throws InterruptedException if interrupted while waiting for the server to start
     * @throws RuntimeException     if the process has died
     * @throws TimeoutException     if the timeout has been reached and the server is still not started
     */
    public static void waitForDomain(final ModelControllerClient client, final long startupTimeout)
            throws InterruptedException, RuntimeException, TimeoutException {
        waitForDomain(null, client, startupTimeout);
    }

    /**
     * Waits the given amount of time in seconds for a managed domain to start. A domain is considered started when each
     * of the servers in the domain are started unless the server is disabled.
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
    public static void waitForDomain(final Process process, final ModelControllerClient client, final long startupTimeout)
            throws InterruptedException, RuntimeException, TimeoutException {
        DomainManagement.waitForDomain(process, client, startupTimeout);
    }

    /**
     * Checks to see if the domain is running. If the server is not in admin only mode each servers running state is
     * checked. If any server is not in a started state the domain is not considered to be running.
     *
     * @param client the client used to communicate with the server
     *
     * @return {@code true} if the server is in a running state, otherwise {@code false}
     */
    public static boolean isDomainRunning(final ModelControllerClient client) {
        return DomainManagement.isDomainRunning(client);
    }

    /**
     * Shuts down a managed domain container. The servers are first stopped, then the host controller is shutdown.
     *
     * @param client the client used to communicate with the server
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation used to shutdown the managed domain failed
     */
    public static void shutdownDomain(final ModelControllerClient client) throws IOException, OperationExecutionException {
        DomainManagement.shutdownDomain(client, 0);
    }

    /**
     * Shuts down a managed domain container. The servers are first stopped, then the host controller is shutdown.
     *
     * @param client  the client used to communicate with the server
     * @param timeout the graceful shutdown timeout, a value of {@code -1} will wait indefinitely and a value of
     *                    {@code 0} will not attempt a graceful shutdown
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation used to shutdown the managed domain failed
     */
    public static void shutdownDomain(final ModelControllerClient client, final int timeout)
            throws IOException, OperationExecutionException {
        DomainManagement.shutdownDomain(client, timeout);
    }

    /**
     * Determines the address for the host being used.
     *
     * @param client the client used to communicate with the server
     *
     * @return the address of the host
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation used to determine the host name fails
     */
    public static ModelNode determineHostAddress(final ModelControllerClient client)
            throws IOException, OperationExecutionException {
        return DomainManagement.determineHostAddress(client);
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
        StandaloneManagement.waitForStandalone(process, client, startupTimeout);
    }

    /**
     * Checks to see if a standalone server is running.
     *
     * @param client the client used to communicate with the server
     *
     * @return {@code true} if the server is running, otherwise {@code false}
     */
    public static boolean isStandaloneRunning(final ModelControllerClient client) {
        return StandaloneManagement.isStandaloneRunning(client);
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
        StandaloneManagement.shutdownStandalone(client, timeout);
    }
}
