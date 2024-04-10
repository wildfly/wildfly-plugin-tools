/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.io.IOException;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wildfly.core.launcher.Launcher;
import org.wildfly.core.launcher.ProcessHelper;
import org.wildfly.core.launcher.StandaloneCommandBuilder;
import org.wildfly.plugin.tools.server.ServerManager;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("StaticVariableMayNotBeInitialized")
public class StandaloneDeploymentManagerIT extends AbstractDeploymentManagerTest {

    private static Process process;
    private static ModelControllerClient client;
    private static ServerManager serverManager;
    private static Thread consoleConsomer;

    @BeforeAll
    public static void startServer() throws Exception {
        boolean ok = false;
        try {
            client = Environment.createClient();
            if (ServerManager.isRunning(client)) {
                Assertions.fail("A WildFly server is already running: " + ContainerDescription.lookup(client));
            }
            final StandaloneCommandBuilder commandBuilder = StandaloneCommandBuilder.of(Environment.WILDFLY_HOME);
            process = Launcher.of(commandBuilder).launch();
            consoleConsomer = ConsoleConsumer.start(process, System.out);
            serverManager = ServerManager.builder().process(process).client(client).standalone();
            ok = serverManager.waitFor(Environment.TIMEOUT);
        } finally {
            if (!ok) {
                final Process p = process;
                final ModelControllerClient c = client;
                process = null;
                client = null;
                try {
                    ProcessHelper.destroyProcess(p);
                } finally {
                    safeClose(c);
                }
            }
        }
    }

    @AfterAll
    @SuppressWarnings("StaticVariableUsedBeforeInitialization")
    public static void shutdown() throws Exception {
        try {
            if (client != null) {
                serverManager.shutdown();
                safeClose(client);
            }
        } finally {
            if (process != null) {
                process.destroy();
                process.waitFor();
            }
        }
    }

    @Test
    public void testDeploymentQueries() throws Exception {
        Assertions.assertTrue(deploymentManager.getDeployments().isEmpty(), "No deployments should exist.");
        Assertions.assertTrue(deploymentManager.getDeploymentNames().isEmpty(), "No deployments should exist.");
        try {
            deploymentManager.getDeployments("main-server-group");
            Assertions
                    .fail("This is not a domain server and DeploymentManager.getDeployments(serverGroup) should have failed.");
        } catch (IllegalStateException ignore) {
        }
    }

    @Override
    protected ModelControllerClient getClient() {
        return client;
    }

    @Override
    protected ModelNode createDeploymentResourceAddress(final String deploymentName) throws IOException {
        return Operations.createAddress(ClientConstants.DEPLOYMENT, deploymentName);
    }
}
