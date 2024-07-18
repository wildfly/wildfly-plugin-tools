/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.wildfly.core.launcher.DomainCommandBuilder;
import org.wildfly.core.launcher.Launcher;
import org.wildfly.core.launcher.StandaloneCommandBuilder;
import org.wildfly.plugin.tools.ConsoleConsumer;
import org.wildfly.plugin.tools.Environment;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class ServerManagerIT {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void findDomainProcess() throws Exception {
        checkProcess(launchDomain());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void findStandaloneProcess() throws Exception {
        checkProcess(launchStandalone());
    }

    @Test
    public void checkDomainServerState() throws Exception {
        checkServerState(launchDomain());
    }

    @Test
    public void checkStandaloneServerState() throws Exception {
        checkServerState(launchStandalone());
    }

    @Test
    public void checkStandaloneReloadIfRequired() throws Exception {
        try (StandaloneManager serverManager = Environment.launchStandalone()) {
            // Execute a command which will put the server in a state of requiring a reload
            final ModelNode address = Operations.createAddress("subsystem", "remoting");
            ModelNode result = executeCommand(serverManager.client(),
                    Operations.createWriteAttributeOperation(address, "max-inbound-channels", 50));
            verifyReloadRequired(result);
            serverManager.reloadIfRequired();
            Assertions.assertTrue(serverManager.isRunning(), "The server does not appear to be running.");
            // Validate the server state
            result = executeCommand(serverManager.client(),
                    Operations.createReadAttributeOperation(new ModelNode().setEmptyList(), "server-state"));
            Assertions.assertEquals(ClientConstants.CONTROLLER_PROCESS_STATE_RUNNING, Operations.readResult(result)
                    .asString());
        }
    }

    @Test
    public void checkDomainReloadIfRequired() throws Exception {
        try (DomainManager serverManager = Environment.launchDomain()) {
            // Execute a command which will put the server in a state of requiring a reload
            final ModelNode address = Operations.createAddress("profile", "full", "subsystem", "remoting");
            ModelNode result = executeCommand(serverManager.client(),
                    Operations.createWriteAttributeOperation(address, "max-inbound-channels", 50));
            verifyReloadRequired(result);
            serverManager.reloadIfRequired();
            Assertions.assertTrue(serverManager.isRunning(), "The server does not appear to be running.");
            // Validate the server state
            result = executeCommand(serverManager.client(),
                    Operations.createReadAttributeOperation(serverManager.determineHostAddress()
                            .add("server", "server-one"),
                            "server-state"));
            Assertions.assertEquals(ClientConstants.CONTROLLER_PROCESS_STATE_RUNNING, Operations.readResult(result)
                    .asString());
        }
    }

    @Test
    public void checkStandaloneServerManagerClosed() throws Exception {
        ServerManager checker;
        ProcessHandle process;
        try (StandaloneManager serverManager = Environment.launchStandalone(false)) {
            checker = serverManager;
            process = serverManager.process;
        }
        Assertions.assertTrue(checker.isClosed(), String
                .format("Expected ServerManager %s to be closed, but the client did not close the server manager.", checker));
        try {
            Assertions.assertTrue(process.isAlive(),
                    "Expected the server to still be running as close() should not have shut it down.");
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    public void checkStandaloneShutdownOnClose() throws Exception {
        ProcessHandle process = null;
        try {
            try (StandaloneManager serverManager = Environment.launchStandalone()) {
                process = serverManager.process;
                serverManager.waitFor(Environment.TIMEOUT, TimeUnit.SECONDS);
                Assertions.assertTrue(serverManager.isRunning(), "The server does not appear to be running.");
            }
            Assertions.assertFalse(process.isAlive(), "Expected server to be shutdown");
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    public void checkDomainServerManagerClosed() throws Exception {
        final Process process = launchDomain();
        ServerManager checker;
        try (
                ServerManager serverManager = ServerManager.builder().process(process)
                        .managementAddress(Environment.HOSTNAME).managementPort(Environment.PORT).domain()) {
            serverManager.waitFor(Environment.TIMEOUT, TimeUnit.SECONDS);
            checker = serverManager;
            serverManager.shutdown();
        } finally {
            process.destroyForcibly();
        }
        Assertions.assertTrue(checker.isClosed(), String
                .format("Expected ServerManager %s to be closed, but the client did not close the server manager.", checker));
    }

    @Test
    public void checkDomainShutdownOnClose() throws Exception {
        final Process process = launchDomain();
        try {
            try (
                    ServerManager serverManager = ServerManager.builder().process(process)
                            .shutdownOnClose(true)
                            .managementAddress(Environment.HOSTNAME).managementPort(Environment.PORT).domain()) {
                serverManager.waitFor(Environment.TIMEOUT, TimeUnit.SECONDS);
            }
            Assertions.assertFalse(process.isAlive(), "Expected server to be shutdown");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    public void checkManagedStandalone() {
        try (ServerManager serverManager = Environment.launchStandalone()) {
            final ServerManager managedServerManager = serverManager.asManaged();
            Assertions.assertThrows(UnsupportedOperationException.class, managedServerManager::kill);
            Assertions.assertThrows(UnsupportedOperationException.class, managedServerManager::shutdown);
            Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> managedServerManager.shutdown(Environment.TIMEOUT));
        }
    }

    @Test
    public void checkManagedDomain() {
        try (ServerManager serverManager = Environment.launchDomain()) {
            final ServerManager managedServerManager = serverManager.asManaged();
            Assertions.assertThrows(UnsupportedOperationException.class, managedServerManager::kill);
            Assertions.assertThrows(UnsupportedOperationException.class, managedServerManager::shutdown);
            Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> managedServerManager.shutdown(Environment.TIMEOUT));
        }
    }

    private ModelNode executeCommand(final ModelControllerClient client, final ModelNode op) throws IOException {
        final ModelNode result = client.execute(op);
        if (!Operations.isSuccessfulOutcome(result)) {
            Assertions.fail("Failed to execute command: " + Operations.getFailureDescription(result));
        }
        return result;
    }

    private void verifyReloadRequired(final ModelNode result) {
        if (result.hasDefined(ClientConstants.RESPONSE_HEADERS)) {
            final ModelNode responseHeaders = result.get(ClientConstants.RESPONSE_HEADERS);
            Assertions.assertTrue(responseHeaders.hasDefined("operation-requires-reload") &&
                    responseHeaders.get("operation-requires-reload").asBoolean(),
                    "The operation did not require a reload: " + responseHeaders);
        } else if (result.hasDefined("server-groups")) {
            // Check the server groups for the response headers
            for (Property property : result.get("server-groups").asPropertyList()) {
                // They key is the server name and the value is the response for that server
                for (ModelNode serverNode : property.getValue().get("host", "primary").asList()) {
                    verifyReloadRequired(serverNode.asProperty().getValue().get("response"));
                }
            }
        } else {
            Assertions.fail("The operation did not require a reload: " + result);
        }
    }

    private void checkServerState(final Process process) throws Exception {
        try (
                ServerManager serverManager = Environment.createServerManagerBuilder(process).build()
                        .get(Environment.TIMEOUT, TimeUnit.SECONDS)) {
            Assertions.assertTrue(serverManager.waitFor(Environment.TIMEOUT), "Server failed to start");
            // Check the server state
            Assertions.assertEquals("running", serverManager.serverState());
        } finally {
            process.destroyForcibly();
        }
    }

    private void checkProcess(final Process process) throws Exception {
        try (
                ServerManager serverManager = Environment.createServerManagerBuilder(process).build()
                        .get(Environment.TIMEOUT, TimeUnit.SECONDS)) {
            Assertions.assertTrue(serverManager.waitFor(Environment.TIMEOUT), "Server failed to start");
            final Optional<ProcessHandle> handle = ServerManager.findProcess();
            Assertions.assertTrue(handle.isPresent(), () -> "Could not find the server process for " + process.pid());
            Assertions.assertEquals(process.toHandle(), handle.get());
        } finally {
            process.destroyForcibly();
        }
    }

    private Process launchDomain() throws IOException {
        final Process process = Launcher.of(DomainCommandBuilder.of(Environment.WILDFLY_HOME))
                .setRedirectErrorStream(true)
                .launch();
        ConsoleConsumer.start(process, System.out);
        return process;
    }

    private Process launchStandalone() throws IOException {
        final Process process = Launcher.of(StandaloneCommandBuilder.of(Environment.WILDFLY_HOME))
                .setRedirectErrorStream(true)
                .launch();
        ConsoleConsumer.start(process, System.out);
        return process;
    }
}
