/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.helpers.DelegatingModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.wildfly.plugin.tools.ContainerDescription;
import org.wildfly.plugin.tools.DeploymentManager;
import org.wildfly.plugin.tools.OperationExecutionException;

/**
 * A {@link ServerManager} which does not allow any termination of the server. Invocation on any of these methods
 * throws a {@link UnsupportedOperationException}.
 * <p>
 * Note that the {@link #close()} will not shutdown the server regardless of the
 * {@link Configuration#shutdownOnClose(boolean)} or {@link ServerManager.Builder#shutdownOnClose(boolean)} setting.
 * </p>
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 * @since 1.2
 */
@SuppressWarnings("unused")
class ManagedServerManager implements ServerManager {

    private final ServerManager delegate;
    private final ModelControllerClient client;

    /**
     * Create a new managed server manager.
     *
     * @param delegate the delegate
     */
    ManagedServerManager(final ServerManager delegate) {
        this.delegate = delegate;
        this.client = new UnclosableModelControllerClient(delegate.client());
    }

    @Override
    public ModelControllerClient client() {
        return client;
    }

    @Override
    public String serverState() {
        return delegate.serverState();
    }

    @Override
    public String launchType() {
        return delegate.launchType();
    }

    @Override
    public String takeSnapshot() throws IOException {
        return delegate.takeSnapshot();
    }

    @Override
    public ContainerDescription containerDescription() throws IOException {
        return delegate.containerDescription();
    }

    @Override
    public DeploymentManager deploymentManager() {
        return delegate.deploymentManager();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    /**
     * Not allowed, throws an {@link UnsupportedOperationException}
     */
    @Override
    public CompletableFuture<ServerManager> kill() {
        throw new UnsupportedOperationException("Cannot kill a managed server");
    }

    @Override
    public boolean waitFor(final long startupTimeout) throws InterruptedException {
        return delegate.waitFor(startupTimeout);
    }

    @Override
    public boolean waitFor(final long startupTimeout, final TimeUnit unit) throws InterruptedException {
        return delegate.waitFor(startupTimeout, unit);
    }

    @Override
    public ServerManager start(final long timeout, final TimeUnit unit) {
        throw new UnsupportedOperationException("Cannot start a managed server");
    }

    @Override
    public ServerManager start() {
        throw new UnsupportedOperationException("Cannot start a managed server");
    }

    @Override
    public CompletionStage<ServerManager> startAsync() {
        throw new UnsupportedOperationException("Cannot start a managed server");
    }

    @Override
    public CompletionStage<ServerManager> startAsync(final long timeout, final TimeUnit unit) {
        throw new UnsupportedOperationException("Cannot start a managed server");
    }

    /**
     * Not allowed, throws an {@link UnsupportedOperationException}
     */
    @Override
    public void shutdown() throws IOException {
        throw new UnsupportedOperationException("Cannot shutdown a managed server");
    }

    /**
     * Not allowed, throws an {@link UnsupportedOperationException}
     */
    @Override
    public void shutdown(final long timeout) throws IOException {
        throw new UnsupportedOperationException("Cannot shutdown a managed server");
    }

    @Override
    public CompletableFuture<ServerManager> shutdownAsync() {
        throw new UnsupportedOperationException("Cannot shutdown a managed server");
    }

    @Override
    public CompletableFuture<ServerManager> shutdownAsync(final long timeout) {
        throw new UnsupportedOperationException("Cannot shutdown a managed server");
    }

    @Override
    public void executeReload() throws IOException {
        delegate.executeReload();
    }

    @Override
    public void executeReload(final ModelNode reloadOp) throws IOException {
        checkOperation(reloadOp);
        delegate.executeReload(reloadOp);
    }

    @Override
    public void reloadIfRequired() throws IOException {
        delegate.reloadIfRequired();
    }

    @Override
    public void reloadIfRequired(final long timeout, final TimeUnit unit) throws IOException {
        delegate.reloadIfRequired(timeout, unit);
    }

    @Override
    public ModelNode executeOperation(final ModelNode op) throws IOException, OperationExecutionException {
        checkOperation(op);
        return delegate.executeOperation(op);
    }

    @Override
    public ModelNode executeOperation(final Operation op) throws IOException, OperationExecutionException {
        checkOperation(op.getOperation());
        return delegate.executeOperation(op);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void close() {
        // Don't do anything
    }

    @Override
    public ServerManager asManaged() {
        return this;
    }

    private void checkOperation(final ModelNode op) {
        final String opName = Operations.getOperationName(op);
        if (opName.equalsIgnoreCase("shutdown")) {
            throw new UnsupportedOperationException("Cannot shutdown a managed server");
        }
    }

    private static class UnclosableModelControllerClient extends DelegatingModelControllerClient {

        public UnclosableModelControllerClient(final ModelControllerClient delegate) {
            super(delegate);
        }

        @Override
        public void close() {
            // Don't actually close
        }
    }
}
