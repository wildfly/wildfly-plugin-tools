/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;

/**
 * A listener which takes a {@linkplain ServerManager#takeSnapshot() snapshot} of the server configuration after the
 * server has started and restores that configuration before the server is shutdown.
 * <p>
 * <strong>Important:</strong> This listener should typically be added first so that it takes a clean snapshot of the
 * configuration early and restores the old configuration last during the shutdown.
 * </p>
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
public class RestoreConfigListener implements ServerManagerListener {
    private static final Logger LOGGER = Logger.getLogger(RestoreConfigListener.class);
    private volatile String configPath;

    @Override
    public void afterStart(final ServerManager serverManager) {
        try {
            configPath = serverManager.takeSnapshot();
        } catch (IOException e) {
            throw new ServerManagerException(e, "Failed to take a snapshot of the servers configuration.");
        }
    }

    @Override
    public void beforeShutdown(final ServerManager serverManager) {
        final String configPath = this.configPath;
        if (configPath != null) {
            try {
                if (serverManager instanceof DomainManager domainManager) {
                    restoreDomain(domainManager, configPath);
                } else {
                    restoreStandalone(serverManager, configPath);
                }
            } catch (IOException e) {
                LOGGER.warnf(e, "Failed to restore the previous configuration %s.", configPath);
            } catch (InterruptedException e) {
                LOGGER.warnf(e, "Interrupted while attempting to restore the configuration %s.", configPath);
            }
        } else {
            LOGGER.warn("No configuration was restored. The snapshot configuration file was never created.");
        }
    }

    private static void restoreStandalone(final ServerManager serverManager, final String configPath)
            throws IOException, InterruptedException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("Reloading server with configuration snapshot: %s", configPath);
        }
        final ModelNode op = Operations.createOperation("reload");
        op.get("server-config").set(configPath);
        serverManager.executeReload(op);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Reload operation sent, waiting for server to restart");
        }
        // Wait until the server is running, then write the config
        if (serverManager.waitFor(ServerManager.TIMEOUT, TimeUnit.SECONDS)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Server restarted, writing configuration");
            }
            final ModelNode writeOp = Operations.createOperation("write-config");
            final ModelNode result = serverManager.client().execute(writeOp);
            if (!Operations.isSuccessfulOutcome(result)) {
                LOGGER.warnf("Failed to write config after restoring from snapshot: %s",
                        Operations.getFailureDescription(result).asString());
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Successfully restored configuration from snapshot: %s", configPath);
                }
            }
        } else {
            // The server has not reloaded within the timeout
            LOGGER.warnf(
                    "The server failed to reload within %d seconds. The previous configuration at %s will not be restored.",
                    ServerManager.TIMEOUT, configPath);
        }
    }

    private static void restoreDomain(final DomainManager serverManager, final String configPath)
            throws IOException, InterruptedException {
        final ModelNode hostAddress = serverManager.determineHostAddress();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Shutting down domain servers before restoring configuration");
        }
        // First we shut down the servers
        ModelNode op = Operations.createOperation("stop-servers");
        op.get("blocking").set(true);
        serverManager.executeOperation(op);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Domain servers stopped");
        }

        // The servers should be stopped now as this is a blocking operation. We can now reload the server
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("Reloading host controller with configuration snapshot: %s", configPath);
        }
        op = Operations.createOperation("reload", hostAddress);
        op.get("domain-config").set(configPath);
        serverManager.executeReload(op);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Reload operation sent, waiting for host controller to restart");
        }
        // Wait until the server is running, then write the config
        if (serverManager.waitFor(ServerManager.TIMEOUT, TimeUnit.SECONDS)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Host controller restarted, writing configuration");
            }
            final ModelNode writeOp = Operations.createOperation("write-config", hostAddress);
            final ModelNode result = serverManager.client().execute(writeOp);
            if (!Operations.isSuccessfulOutcome(result)) {
                LOGGER.warnf("Failed to write config after restoring from snapshot: %s",
                        Operations.getFailureDescription(result).asString());
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Successfully restored configuration from snapshot: %s", configPath);
                }
            }
        } else {
            // The server has not reloaded within the timeout
            LOGGER.warnf(
                    "The server failed to reload within %d seconds. The previous configuration at %s will not be restored.",
                    ServerManager.TIMEOUT, configPath);
        }
    }
}
