/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.wildfly.plugin.tools.Deployment;
import org.wildfly.plugin.tools.DeploymentDescription;
import org.wildfly.plugin.tools.Environment;
import org.wildfly.plugin.tools.UndeployDescription;

/**
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
abstract class AbstractServerManagerListenerIT {
    private static final String DEPLOYMENT_NAME = "test.war";

    @Test
    public void afterStartup() throws Exception {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        try (
                ServerManager serverManager = ServerManager.of(configuration())
                        .addServerManagerListener(new ServerManagerListener() {
                            @Override
                            public void afterStart(final ServerManager serverManager) {
                                invoked.set(true);
                            }
                        })) {
            startServer(serverManager);
            Assertions.assertTrue(invoked.get(),
                    () -> String.format("afterStart on server manager %s was never invoked.", serverManager));
        }
    }

    @Test
    public void beforeShutdown() throws Exception {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        try (
                ServerManager serverManager = ServerManager.of(configuration().shutdownOnClose(false))
                        .addServerManagerListener(new ServerManagerListener() {
                            @Override
                            public void beforeShutdown(final ServerManager serverManager) {
                                invoked.set(true);
                            }
                        })) {
            startServer(serverManager);
            serverManager.shutdown(Environment.TIMEOUT);
            Assertions.assertFalse(serverManager.isRunning(),
                    () -> String.format("Failed to start server %s", serverManager));
            Assertions.assertTrue(invoked.get(),
                    () -> String.format("beforeShutdown on server manager %s was never invoked.", serverManager));
        }
    }

    @Test
    public void beforeDeploy() throws Exception {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        try (
                ServerManager serverManager = ServerManager.of(configuration().shutdownOnClose(true))
                        .addServerManagerListener(new ServerManagerListener() {
                            @Override
                            public void beforeDeploy(final ServerManager serverManager, final Deployment deployment) {
                                Assertions.assertEquals(DEPLOYMENT_NAME, deployment.getName());
                                invoked.set(true);
                            }
                        });
                Deployment deployment = createDeployment()) {
            startServer(serverManager);
            serverManager.deploy(deployment);
            serverManager.undeploy(UndeployDescription.of(deployment));
            Assertions.assertTrue(invoked.get(),
                    () -> String.format("beforeDeploy on server manager %s was never invoked.", serverManager));
        }
    }

    @Test
    public void afterDeploy() throws Exception {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        try (
                ServerManager serverManager = ServerManager.of(configuration().shutdownOnClose(true))
                        .addServerManagerListener(new ServerManagerListener() {
                            @Override
                            public void afterDeploy(final ServerManager serverManager, final Deployment deployment) {
                                Assertions.assertEquals(DEPLOYMENT_NAME, deployment.getName());
                                invoked.set(true);
                            }
                        });
                Deployment deployment = createDeployment()) {
            startServer(serverManager);
            serverManager.deploy(deployment);
            serverManager.undeploy(UndeployDescription.of(deployment));
            Assertions.assertTrue(invoked.get(),
                    () -> String.format("afterDeploy on server manager %s was never invoked.", serverManager));
        }
    }

    @Test
    public void deployFailed() throws Exception {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        try (
                ServerManager serverManager = ServerManager.of(configuration().shutdownOnClose(true))
                        .addServerManagerListener(new ServerManagerListener() {
                            @Override
                            public void deployFailed(final ServerManager serverManager, final Deployment deployment,
                                    final Throwable throwable) {
                                Assertions.assertEquals(DEPLOYMENT_NAME, deployment.getName());
                                Assertions.assertNotNull(throwable);
                                invoked.set(true);
                            }
                        });
                Deployment deployment = createDeployment(true)) {
            startServer(serverManager);
            Assertions.assertThrows(ServerManagerException.class, () -> serverManager.deploy(deployment));
            Assertions.assertTrue(invoked.get(),
                    () -> String.format("deployFailed on server manager %s was never invoked.", serverManager));
        }
    }

    @Test
    public void beforeUndeploy() throws Exception {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        try (
                ServerManager serverManager = ServerManager.of(configuration().shutdownOnClose(true))
                        .addServerManagerListener(new ServerManagerListener() {
                            @Override
                            public void beforeUndeploy(final ServerManager serverManager,
                                    final DeploymentDescription deployment) {
                                Assertions.assertEquals(DEPLOYMENT_NAME, deployment.getName());
                                invoked.set(true);
                            }
                        });
                Deployment deployment = createDeployment()) {
            startServer(serverManager);
            serverManager.deploy(deployment);
            serverManager.undeploy(UndeployDescription.of(deployment));
            Assertions.assertTrue(invoked.get(),
                    () -> String.format("beforeUndeploy on server manager %s was never invoked.", serverManager));
        }
    }

    @Test
    public void afterUndeploy() throws Exception {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        try (
                ServerManager serverManager = ServerManager.of(configuration().shutdownOnClose(true))
                        .addServerManagerListener(new ServerManagerListener() {
                            @Override
                            public void afterUndeploy(final ServerManager serverManager,
                                    final DeploymentDescription deployment) {
                                Assertions.assertEquals(DEPLOYMENT_NAME, deployment.getName());
                                invoked.set(true);
                            }
                        });
                Deployment deployment = createDeployment()) {
            startServer(serverManager);
            serverManager.deploy(deployment);
            serverManager.undeploy(UndeployDescription.of(deployment));
            Assertions.assertTrue(invoked.get(),
                    () -> String.format("afterUndeploy on server manager %s was never invoked.", serverManager));
        }
    }

    @Test
    public void undeployFailed() throws Exception {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        try (
                ServerManager serverManager = ServerManager.of(configuration().shutdownOnClose(true))
                        .addServerManagerListener(new ServerManagerListener() {
                            @Override
                            public void undeployFailed(final ServerManager serverManager,
                                    final DeploymentDescription deployment,
                                    final Throwable throwable) {
                                Assertions.assertEquals(DEPLOYMENT_NAME, deployment.getName());
                                Assertions.assertNotNull(throwable);
                                invoked.set(true);
                            }
                        })) {
            startServer(serverManager);
            Assertions.assertThrows(ServerManagerException.class,
                    () -> serverManager.undeploy(UndeployDescription.of(DEPLOYMENT_NAME).setFailOnMissing(true)));
            Assertions.assertTrue(invoked.get(),
                    () -> String.format("undeployFailed on server manager %s was never invoked.", serverManager));
        }
    }

    void startServer(final ServerManager serverManager) throws Exception {
        Assertions.assertTrue(serverManager.start().isRunning(),
                () -> String.format("Failed to start server %s", serverManager));
    }

    Deployment createDeployment() {
        return createDeployment(false);

    }

    Deployment createDeployment(final boolean forFailure) {
        final WebArchive war = ShrinkWrap.create(WebArchive.class, DEPLOYMENT_NAME)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
        if (forFailure) {
            war.addAsWebInfResource(new StringAsset("<?xml version=\"1.0\"?><web-app><invalid>"), "web.xml");
        }
        return Deployment.of(war.as(ZipExporter.class).exportAsInputStream(),
                DEPLOYMENT_NAME);

    }

    protected abstract Configuration<?> configuration();
}
