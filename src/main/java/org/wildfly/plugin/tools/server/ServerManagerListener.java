/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import org.wildfly.plugin.tools.Deployment;
import org.wildfly.plugin.tools.DeploymentDescription;

/**
 * A listener which gets called at each event defined for the {@link ServerManager}.
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
public interface ServerManagerListener {

    /**
     * Invoked after a server has successfully {@linkplain ServerManager#start() started}.
     *
     * @param serverManager the server manager that has been started
     */
    default void afterStart(final ServerManager serverManager) {
    }

    /**
     * Invoked before the server is {@linkplain ServerManager#shutdown() shutdown}, assuming the server is running.
     * If the server is determined to not be running, this method will not be invoked.
     *
     * @param serverManager the server manager this is about to shut down
     */
    default void beforeShutdown(final ServerManager serverManager) {
    }

    /**
     * Invoked before the deployment is done via the {@link ServerManager#deploy(Deployment)} method.
     *
     * @param serverManager the server manager for the deployment
     * @param deployment    the deployment
     */
    default void beforeDeploy(final ServerManager serverManager, final Deployment deployment) {
    }

    /**
     * Invoked after the deployment is done via the {@link ServerManager#deploy(org.wildfly.plugin.tools.Deployment)} method.
     *
     * @param serverManager the server manager for the deployment
     * @param deployment    the deployment
     */
    default void afterDeploy(final ServerManager serverManager, final Deployment deployment) {
    }

    /**
     * Invoked if the deployment operation fails. This allows listeners to clean up any resources or configuration
     * created in {@link #beforeDeploy(ServerManager, Deployment)}.
     *
     * @param serverManager the server manager
     * @param deployment    the deployment that failed
     * @param throwable     the error that caused the deployment to fail
     */
    default void deployFailed(final ServerManager serverManager, final Deployment deployment, final Throwable throwable) {
    }

    /**
     * Invoked before the undeploy is done via the {@link ServerManager#undeploy(DeploymentDescription)} method.
     *
     * @param serverManager the server manager for the deployment
     * @param deployment    the deployment
     */
    default void beforeUndeploy(final ServerManager serverManager, final DeploymentDescription deployment) {
    }

    /**
     * Invoked after the undeploy is done via the {@link ServerManager#undeploy(DeploymentDescription)} method.
     *
     * @param serverManager the server manager for the deployment
     * @param deployment    the deployment
     */
    default void afterUndeploy(final ServerManager serverManager, final DeploymentDescription deployment) {
    }

    /**
     * Invoked if the undeployment operation fails.
     *
     * @param serverManager the server manager
     * @param deployment    the deployment that failed to undeploy
     * @param throwable     the error that caused the undeployment to fail
     */
    default void undeployFailed(final ServerManager serverManager, final DeploymentDescription deployment,
            final Throwable throwable) {
    }
}
