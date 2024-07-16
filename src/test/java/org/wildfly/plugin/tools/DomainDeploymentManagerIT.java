/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.dmr.ModelNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wildfly.core.launcher.DomainCommandBuilder;
import org.wildfly.core.launcher.Launcher;
import org.wildfly.core.launcher.ProcessHelper;
import org.wildfly.plugin.tools.server.DomainManager;
import org.wildfly.plugin.tools.server.ServerManager;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("StaticVariableMayNotBeInitialized")
public class DomainDeploymentManagerIT extends AbstractDeploymentManagerTest {
    private static final String DEFAULT_SERVER_GROUP = "main-server-group";
    // Workaround for WFCORE-4121
    private static final String[] MODULAR_JDK_ARGUMENTS = {
            "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-exports=jdk.unsupported/sun.reflect=ALL-UNNAMED",
            "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
            "--add-modules=java.se",
    };
    private static final boolean IS_MODULAR_JDK;

    static {
        final String javaVersion = System.getProperty("java.specification.version");
        int vmVersion;
        try {
            final Matcher matcher = Pattern.compile("^(?:1\\.)?(\\d+)$")
                    .matcher(javaVersion); // match 1.<number> or <number>
            if (matcher.find()) {
                vmVersion = Integer.parseInt(matcher.group(1));
            } else {
                throw new RuntimeException("Unknown version of jvm " + javaVersion);
            }
        } catch (Exception e) {
            vmVersion = 8;
        }
        IS_MODULAR_JDK = vmVersion > 8;
    }

    private static Process process;
    private static DomainClient client;
    private static DomainManager domainManager;
    private static Thread consoleConsomer;

    @BeforeAll
    public static void startServer() throws Exception {
        boolean ok = false;
        try {
            client = DomainClient.Factory.create(Environment.createClient());
            if (ServerManager.isRunning(client)) {
                Assertions.fail("A WildFly server is already running: " + ContainerDescription.lookup(client));
            }
            final DomainCommandBuilder commandBuilder = DomainCommandBuilder.of(Environment.WILDFLY_HOME);
            if (IS_MODULAR_JDK) {
                commandBuilder.addHostControllerJavaOptions(MODULAR_JDK_ARGUMENTS);
            }
            process = Launcher.of(commandBuilder).launch();
            consoleConsomer = ConsoleConsumer.start(process, System.out);
            domainManager = ServerManager.builder().process(process).client(client).domain();
            ok = domainManager.waitFor(Environment.TIMEOUT);
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
                domainManager.shutdown();
                safeClose(client);
            }
        } finally {
            if (process != null) {
                process.destroy();
                process.waitFor();
            }
            if (consoleConsomer != null) {
                consoleConsomer.interrupt();
            }
        }
    }

    @Test
    public void testFailedDeploy() throws Exception {
        // Expect a failure with no server groups defined
        try (Deployment failedDeployment = createDefaultDeployment("test-failed-deployment.war")
                .setServerGroups(Collections.<String> emptySet())) {
            assertFailed(deploymentManager.deploy(failedDeployment));
            assertDeploymentDoesNotExist(failedDeployment);
        }
    }

    @Test
    public void testFailedDeployMulti() throws Exception {
        // Expect a failure with no server groups defined
        try (Deployment failedDeployment1 = createDefaultDeployment("test-failed-deployment-1.war");
                Deployment failedDeployment2 = createDefaultDeployment("test-failed-deployment-2.war")
                        .setServerGroups(Set.of())) {
            final Set<Deployment> failedDeployments = Set.of(failedDeployment1, failedDeployment2);
            assertFailed(deploymentManager.deploy(failedDeployments));
            for (Deployment failedDeployment : failedDeployments) {
                assertDeploymentDoesNotExist(failedDeployment);
            }
        }
    }

    @Test
    public void testFailedForceDeploy() throws Exception {
        // Expect a failure with no server groups defined
        try (Deployment failedDeployment = createDefaultDeployment("test-failed-deployment.war")
                .setServerGroups(Collections.emptySet())) {
            assertFailed(deploymentManager.forceDeploy(failedDeployment));
            assertDeploymentDoesNotExist(failedDeployment);
        }
    }

    @Test
    public void testFailedRedeploy() throws Exception {
        // Expect a failure with no server groups defined
        try (Deployment failedDeployment = createDefaultDeployment("test-redeploy.war")
                .setServerGroups(Collections.emptySet())) {
            assertFailed(deploymentManager
                    .redeploy(failedDeployment));
        }
    }

    @Test
    public void testFailedUndeploy() throws Exception {
        // Undeploy with an additional server-group where the deployment does not exist
        undeployForSuccess(
                UndeployDescription.of("test-undeploy-multi-server-groups.war")
                        .setFailOnMissing(false)
                        .setRemoveContent(false)
                        .addServerGroup("other-server-group"),
                Collections.singleton(DEFAULT_SERVER_GROUP), false);

        // Undeploy with an additional server-group where the deployment does not exist
        try (Deployment deployment = createDefaultDeployment("test-undeploy-multi-server-groups-failed.war")) {
            deployForSuccess(deployment);
            final DeploymentResult result = deploymentManager.undeploy(
                    UndeployDescription.of(deployment)
                            .setFailOnMissing(true)
                            .addServerGroup("other-server-group"));
            assertFailed(result);
            assertDeploymentExists(deployment, true);
        }
    }

    @Test
    public void testDeploymentQueries() throws Exception {
        Assertions.assertTrue(deploymentManager.getDeployments().isEmpty(), "No deployments should exist.");
        Assertions.assertTrue(deploymentManager.getDeploymentNames().isEmpty(), "No deployments should exist.");
        Assertions.assertTrue(deploymentManager.getDeployments(DEFAULT_SERVER_GROUP).isEmpty(),
                () -> String.format("No deployments should exist on %s", DEFAULT_SERVER_GROUP));
    }

    @Override
    protected ModelControllerClient getClient() {
        return client;
    }

    @Override
    protected ModelNode createDeploymentResourceAddress(final String deploymentName) throws IOException {
        return domainManager.determineHostAddress()
                .add(ClientConstants.SERVER, "server-one")
                .add(ClientConstants.DEPLOYMENT, deploymentName);
    }

    @Override
    Deployment configureDeployment(final Deployment deployment) {
        return deployment.addServerGroups(DEFAULT_SERVER_GROUP);
    }
}
