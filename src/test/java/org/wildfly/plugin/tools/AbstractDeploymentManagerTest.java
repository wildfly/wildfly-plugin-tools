/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.io.Closeable;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wildfly.plugin.tools.common.Simple;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("WeakerAccess")
abstract class AbstractDeploymentManagerTest {

    private static final char[] hexChars = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    protected DeploymentManager deploymentManager;

    @BeforeEach
    public void setup() throws UnknownHostException {
        deploymentManager = DeploymentManager.Factory.create(getClient());
    }

    @AfterEach
    public void undeployAll() throws IOException {
        final Set<UndeployDescription> deployments = new HashSet<>();
        for (DeploymentDescription deployment : deploymentManager.getDeployments()) {
            deployments.add(UndeployDescription.of(deployment));
        }
        if (!deployments.isEmpty()) {
            deploymentManager.undeploy(deployments).assertSuccess();
        }
    }

    @Test
    public void testDeploy() throws Exception {
        final String deploymentName = "test-deploy.war";
        final Deployment deployment = createDefaultDeployment(deploymentName);
        deployForSuccess(deployment);
        assertDeploymentEnabled(deployment);

        // Expect a failure when trying to deploy the same content
        assertFailed(deploymentManager.deploy(deployment));
    }

    @Test
    public void testDeployMulti() throws Exception {
        final Deployment deployment1 = createDefaultDeployment("test-deploy-1.war");
        final Deployment deployment2 = createDefaultDeployment("test-deploy-2.war");
        final Set<Deployment> deployments = Set.of(deployment1, deployment2);
        deployForSuccess(deployments);
        assertDeploymentEnabled(deployment1);
        assertDeploymentEnabled(deployment2);

        // Expect a failure when trying to deploy the same content
        assertFailed(deploymentManager.deploy(deployments));
        // Deployments should still exist and be enabled
        assertDeploymentExists(deployment1, true);
        assertDeploymentExists(deployment2, true);
    }

    @Test
    public void testDeployFile() throws Exception {
        final Path tempPath = Files.createTempDirectory("deployment-content");
        try {
            final Path content = tempPath.resolve("test-deploy-file.war");
            createDefaultArchive(content.getFileName().toString()).as(ZipExporter.class)
                    .exportTo(content.toFile(), true);
            final Deployment deployment = configureDeployment(Deployment.of(content));
            deployForSuccess(deployment);
            assertDeploymentEnabled(deployment);
        } finally {
            deletePath(tempPath);
        }
    }

    @Test
    public void testDeployUrl() throws Exception {
        final Path tempPath = Files.createTempDirectory("deployment-content");
        try {
            final Path content = tempPath.resolve("test-deploy-file.war");
            createDefaultArchive(content.getFileName().toString()).as(ZipExporter.class)
                    .exportTo(content.toFile(), true);
            final Deployment deployment = configureDeployment(Deployment.of(content.toUri().toURL()));
            deployForSuccess(deployment);
            assertDeploymentEnabled(deployment);
        } finally {
            deletePath(tempPath);
        }
    }

    @Test
    public void testForceDeploy() throws Exception {
        final String deploymentName = "test-deploy.war";
        final Deployment deployment = createDefaultDeployment(deploymentName);
        DeployResult deployResult = deployForSuccess(deployment, true);

        // Get the current hash
        final byte[] hash = deployResult.hash;
        long lastEnabledTime = deployResult.enabledTime;

        // Force deploy the content and ensure the hash is the same
        deployResult = deployForSuccess(deployment, true);
        assertDeploymentEnabled(deployment);

        final byte[] d1Hash = deployResult.hash;
        long currentLastEnabledTime = deployResult.enabledTime;
        // Compare the original hash and the new hash, they should be equal as the content is exactly the same. However
        // the timestamps should not be equal as the content should have been replaced and therefore undeployed, then
        // redeployed.
        Assertions.assertArrayEquals(hash, d1Hash,
                () -> String.format("Expected hash to be equal: %nExpected: %s%nFound: %s%n", bytesToHexString(hash),
                        bytesToHexString(d1Hash)));
        Assertions.assertNotEquals(lastEnabledTime, currentLastEnabledTime, "Last enabled times should not match.");

        // Create a new deployment, add new content and force deploy it which should result in a new hash and timestamp
        final WebArchive archive = createDefaultArchive(deploymentName)
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
        final Deployment changedDeployment = createDeployment(archive);
        deployResult = deployForSuccess(changedDeployment, true);
        assertDeploymentEnabled(changedDeployment);

        final byte[] d2Hash = deployResult.hash;
        lastEnabledTime = currentLastEnabledTime;
        currentLastEnabledTime = deployResult.enabledTime;

        // In this case we've added some new content to the deployment. The hashes should not match and once again the
        // timestamps should be different.
        Assertions.assertFalse(Arrays.equals(hash, d2Hash),
                () -> String.format("Expected hash to be equal: %nExpected: %s%nFound: %s%n", bytesToHexString(hash),
                        bytesToHexString(d2Hash)));
        Assertions.assertNotEquals(lastEnabledTime, currentLastEnabledTime, "Last enabled times should not match.");

    }

    @Test
    public void testForceDeployMulti() throws Exception {
        final Deployment deployment1 = createDefaultDeployment("test-deploy-1.war");
        final Deployment deployment2 = createDefaultDeployment("test-deploy-2.war");
        final Set<Deployment> deployments = Set.of(deployment1, deployment2);
        final Map<Deployment, DeployResult> first = deployForSuccess(deployments, true);

        // Force deploy the content and ensure the hash is the same
        final Set<Deployment> deployments2 = Set.of(deployment1, deployment2);
        final Map<Deployment, DeployResult> second = deployForSuccess(deployments2, true);

        Assertions.assertEquals(first.size(), second.size());
        Assertions.assertTrue(first.keySet().containsAll(second.keySet()));

        // Skip time checks on domain until WFCORE-1667 is fixed
        final boolean checkTime = !ContainerDescription.lookup(getClient()).isDomain();
        // Get the current hash
        for (Deployment deployment : deployments) {
            final byte[] hash = first.get(deployment).hash;
            final long lastEnabledTime = first.get(deployment).enabledTime;

            final byte[] currentHash = second.get(deployment).hash;
            final long currentLastEnabledTime = second.get(deployment).enabledTime;
            // Compare the original hash and the new hash, they should be equal as the content is exactly the same. However
            // the timestamps should not be equal as the content should have been replaced and therefore undeployed, then
            // redeployed.
            Assertions.assertArrayEquals(hash, currentHash,
                    () -> String.format("Expected hash to be equal: %nExpected: %s%nFound: %s%n", bytesToHexString(hash),
                            bytesToHexString(currentHash)));
            if (checkTime) {
                Assertions.assertNotEquals(lastEnabledTime, currentLastEnabledTime, "Last enabled times should not match");
            }
        }
    }

    @Test
    public void testDeployToRuntime() throws Exception {
        final String deploymentName = "test-runtime-deploy.war";
        final Deployment deployment = createDefaultDeployment(deploymentName)
                .setEnabled(false);

        deployForSuccess(deployment);
        // The content should be disable, i.e. not deployed to the runtime
        assertDeploymentDisabled(deployment);

        // Deploy the content to the runtime, this should result in the enabled field resulting in true
        assertSuccess(deploymentManager.deployToRuntime(deployment));
        // The content should now be enabled, i.e. deployed to runtime
        assertDeploymentEnabled(deployment);
    }

    @Test
    public void testDeployToRuntimeMulti() throws Exception {
        final Deployment deployment1 = createDefaultDeployment("test-runtime-deploy-1.war")
                .setEnabled(false);
        final Deployment deployment2 = createDefaultDeployment("test-runtime-deploy-2.war")
                .setEnabled(false);
        final Deployment deployment3 = createDefaultDeployment("test-runtime-deploy-3.war")
                .setEnabled(true);

        deployForSuccess(Set.of(deployment1, deployment2, deployment3));
        // The content should be disable, i.e. not deployed to the runtime
        assertDeploymentDisabled(deployment1);
        assertDeploymentDisabled(deployment2);
        // This third deployment should be enabled
        assertDeploymentEnabled(deployment3);

        // Deploy the content to the runtime, this should result in the enabled field resulting in true
        assertSuccess(deploymentManager.deployToRuntime(Set.of(deployment1, deployment2, deployment3)));
        // The content should now be enabled, i.e. deployed to runtime
        assertDeploymentEnabled(deployment1);
        assertDeploymentEnabled(deployment2);
        assertDeploymentEnabled(deployment3);
    }

    @Test
    public void testRedeploy() throws Exception {
        final String deploymentName = "test-redeploy.war";
        final Deployment deployment = createDefaultDeployment(deploymentName);

        // Redeploy should fail as the deployment should not exist
        assertFailed(deploymentManager.redeploy(deployment));

        // Deploy the content, the redeploy for a successful redeploy
        final DeployResult deployResult = deployForSuccess(deployment);
        final DeployResult currentDeployResult = redeployForSuccess(deployment);
        // Compare the original hash and the new hash, they should be equal as the content is exactly the same. However
        // the timestamps should not be equal as the content should have been replaced and therefore undeployed, then
        // redeployed.
        Assertions.assertArrayEquals(deployResult.hash, currentDeployResult.hash,
                () -> String.format("Expected hash to be equal: %nExpected: %s%nFound: %s%n",
                        bytesToHexString(deployResult.hash),
                        bytesToHexString(currentDeployResult.hash)));
        Assertions.assertNotEquals(deployResult.enabledTime,
                currentDeployResult.enabledTime, "Last enabled times should not match.");
    }

    @Test
    public void testRedeployMulti() throws Exception {
        final Deployment deployment1 = createDefaultDeployment("test-redeploy-1.war");
        final Deployment deployment2 = createDefaultDeployment("test-redeploy-2.war");
        final Deployment deployment3 = createDefaultDeployment("test-redeploy-3.war");
        final Set<Deployment> allDeployments = Set.of(deployment1, deployment2, deployment3);

        // Redeploy should fail as the deployment should not exist
        assertFailed(deploymentManager.redeploy(allDeployments));

        // Deploy just two of the deployments, then attempt to redeploy all 3 which should fail since one of them could
        // not be redeployed
        final Map<Deployment, DeployResult> deployResults = deployForSuccess(Set.of(deployment1, deployment2));
        assertFailed(deploymentManager.redeploy(allDeployments));

        // Deploy the third deployment content, the redeploy all for a successful redeploy
        deployResults.put(deployment3, deployForSuccess(deployment3));
        final Map<Deployment, DeployResult> currentDeployResults = redeployForSuccess(allDeployments);

        Assertions.assertEquals(deployResults.size(), currentDeployResults.size(),
                "Expected the same size for the original deployment results and the redeploy results");
        Assertions.assertTrue(deployResults.keySet().containsAll(currentDeployResults.keySet()));

        // Skip time checks on domain until WFCORE-1667 is fixed
        final boolean checkTime = !ContainerDescription.lookup(getClient()).isDomain();

        for (Deployment deployment : allDeployments) {
            final DeployResult deployResult = deployResults.get(deployment);
            final DeployResult currentDeployResult = currentDeployResults.get(deployment);
            // Compare the original hash and the new hash, they should be equal as the content is exactly the same. However
            // the timestamps should not be equal as the content should have been replaced and therefore undeployed, then
            // redeployed.
            Assertions.assertArrayEquals(deployResult.hash, currentDeployResult.hash,
                    () -> String.format("Expected hash to be equal: %nExpected: %s%nFound: %s%n",
                            bytesToHexString(deployResult.hash),
                            bytesToHexString(currentDeployResult.hash)));
            if (checkTime) {
                Assertions.assertNotEquals(deployResult.enabledTime, currentDeployResult.enabledTime,
                        "Last enabled times should not match.");
            }
        }
    }

    @Test
    public void testRedeployFile() throws Exception {
        final Path tempPath = Files.createTempDirectory("deployment-content");
        try {
            final Path content = tempPath.resolve("test-deploy-file.war");
            createDefaultArchive(content.getFileName().toString()).as(ZipExporter.class)
                    .exportTo(content.toFile(), true);
            final Deployment deployment = configureDeployment(Deployment.of(content));
            // First deploy, then redeploy
            deployForSuccess(deployment);
            redeployForSuccess(deployment);
            assertDeploymentEnabled(deployment);
        } finally {
            deletePath(tempPath);
        }
    }

    @Test
    public void testRedeployUrl() throws Exception {
        final Path tempPath = Files.createTempDirectory("deployment-content");
        try {
            final Path content = tempPath.resolve("test-deploy-file.war");
            createDefaultArchive(content.getFileName().toString()).as(ZipExporter.class)
                    .exportTo(content.toFile(), true);
            final Deployment deployment = configureDeployment(Deployment.of(content.toUri().toURL()));
            // First deploy, then redeploy
            deployForSuccess(deployment);
            redeployForSuccess(deployment);
            assertDeploymentEnabled(deployment);
        } finally {
            deletePath(tempPath);
        }
    }

    @Test
    public void testRedeployToRuntime() throws Exception {
        // There is a bug in WildFly, fixed in 10.1.0, using a full-replace-deployment operation when the enabled
        // attribute is set to false. Do not test a redeploy with a disabled deployment here as it will remove all
        // subsystems. See WFCORE-1577.

        final String deploymentName = "test-runtime-redeploy.war";
        final Deployment deployment = createDefaultDeployment(deploymentName);

        final DeployResult deployResult = deployForSuccess(deployment);

        // Deploy the content to the runtime, this should result in the enabled field resulting in true
        final DeployResult currentDeployResult = createResult(deploymentManager.redeployToRuntime(deployment), deployment);
        final DeploymentResult result = currentDeployResult.deploymentResult;
        assertSuccess(result);
        assertDeploymentExists(deployment, true);
        Assertions.assertArrayEquals(deployResult.hash, currentDeployResult.hash,
                () -> String.format("Expected hash to be equal: %nExpected: %s%nFound: %s%n",
                        bytesToHexString(deployResult.hash),
                        bytesToHexString(currentDeployResult.hash)));
        // Timestamps should match as a redeploy only redploy's deploys the runtime not the content
        Assertions.assertEquals(deployResult.enabledTime, currentDeployResult.enabledTime,
                () -> "Last enabled times should not match.");
    }

    @Test
    public void testRedeployToRuntimeMulti() throws Exception {
        // There is a bug in WildFly, fixed in 10.1.0, using a full-replace-deployment operation when the enabled
        // attribute is set to false. Do not test a redeploy with a disabled deployment here as it will remove all
        // subsystems. See WFCORE-1577.

        final Deployment deployment1 = createDefaultDeployment("test-runtime-redeploy-1.war");
        final Deployment deployment2 = createDefaultDeployment("test-runtime-redeploy-2.war");
        final Deployment deployment3 = createDefaultDeployment("test-runtime-redeploy-3.war");
        final Set<Deployment> allDeployments = Set.of(deployment1, deployment2, deployment3);
        final Map<Deployment, DeployResult> deployResults = deployForSuccess(allDeployments);

        // Deploy the content to the runtime, this should result in the enabled field resulting in true
        final Map<Deployment, DeployResult> currentDeployResults = createResult(
                deploymentManager.redeployToRuntime(Set.of(deployment1, deployment2, deployment3)),
                allDeployments);

        Assertions.assertEquals(deployResults.size(), currentDeployResults.size(),
                "Expected the same size for the original deployment results and the redeploy results");
        Assertions.assertTrue(deployResults.keySet().containsAll(currentDeployResults.keySet()));

        // Skip time checks on domain until WFCORE-1667 is fixed
        final boolean checkTime = !ContainerDescription.lookup(getClient()).isDomain();

        for (Deployment deployment : allDeployments) {
            final DeployResult deployResult = deployResults.get(deployment);
            final DeployResult currentDeployResult = currentDeployResults.get(deployment);
            Assertions.assertArrayEquals(deployResult.hash, currentDeployResult.hash,
                    () -> String.format("Expected hash to be equal: %nExpected: %s%nFound: %s%n",
                            bytesToHexString(deployResult.hash),
                            bytesToHexString(currentDeployResult.hash)));
            if (checkTime) {
                // Timestamps should match as a redeploy only redploy's deploys the runtime not the content
                Assertions.assertEquals(deployResult.enabledTime, currentDeployResult.enabledTime,
                        "Last enabled times should not match.");
            }
        }
    }

    @Test
    public void testUndeploy() throws Exception {
        final String deploymentName = "test-undeploy.war";
        final Deployment deployment = createDefaultDeployment(deploymentName);

        // First undeploy and don't fail on a missing deployment
        undeployForSuccess(UndeployDescription.of(deployment)
                .setFailOnMissing(false),
                false);

        // Test an undeploy that should fail since it's missing
        assertFailed(deploymentManager.undeploy(
                UndeployDescription.of(deployment)
                        .setFailOnMissing(true)));
        assertDeploymentDoesNotExist(deployment);

        // Deploy the content so it can be undeployed, but leave the content itself on the server. This should result in
        // the enabled being false and the disabled-time being defined.
        deployForSuccess(deployment);

        undeployForSuccess(
                UndeployDescription.of(deployment)
                        .setRemoveContent(false),
                true);

        // Deploy the content back to the runtime
        assertSuccess(deploymentManager.deployToRuntime(deployment));

        // Undeploy the content completely, the content should no longer be in the container
        undeployForSuccess(
                UndeployDescription.of(deployment)
                        .setRemoveContent(true),
                false);

    }

    @Test
    public void testUndeployMulti() throws Exception {
        final Deployment deployment1 = createDefaultDeployment("test-undeploy-1.war");
        final Deployment deployment2 = createDefaultDeployment("test-undeploy-2.war");
        final Deployment deployment3 = createDefaultDeployment("test-undeploy-3.war");

        // First undeploy and don't fail on a missing deployment
        assertSuccess(deploymentManager.undeploy(
                Set.of(UndeployDescription.of(deployment1).setFailOnMissing(false),
                        UndeployDescription.of(deployment2).setFailOnMissing(false))));

        // Test an undeploy that should fail since it's missing
        assertFailed(deploymentManager.undeploy(
                Set.of(UndeployDescription.of(deployment1).setFailOnMissing(false),
                        UndeployDescription.of(deployment2).setFailOnMissing(true),
                        UndeployDescription.of(deployment3).setFailOnMissing(false))));

        // Deploy the content so it can be undeployed, but leave the content itself on the server. This should result in
        // the enabled being false and the disabled-time being defined.
        deployForSuccess(Set.of(deployment1, deployment2, deployment3));

        // Remove all deployments, leaving deployment1 and deployment2 content, but removing deployment3's content
        assertSuccess(deploymentManager.undeploy(
                Set.of(UndeployDescription.of(deployment1).setRemoveContent(false),
                        UndeployDescription.of(deployment2).setRemoveContent(false),
                        UndeployDescription.of(deployment3).setRemoveContent(true))));

        assertDeploymentExists(deployment1, false);
        assertDeploymentExists(deployment2, false);
        assertDeploymentDoesNotExist(deployment3);

        // Deploy the content back to the runtime
        assertSuccess(deploymentManager.deployToRuntime(Set.of(deployment1, deployment2)));

        // Undeploy remaining
        assertSuccess(deploymentManager.undeploy(
                Set.of(UndeployDescription.of(deployment1).setRemoveContent(true),
                        UndeployDescription.of(deployment2).setRemoveContent(true))));
        assertDeploymentDoesNotExist(deployment1);
        assertDeploymentDoesNotExist(deployment2);
    }

    protected abstract ModelControllerClient getClient();

    protected abstract ModelNode createDeploymentResourceAddress(String deploymentName) throws IOException;

    void assertSuccess(final DeploymentResult result) {
        if (!result.successful()) {
            Assertions.fail(result.getFailureMessage());
        }
    }

    void assertFailed(final DeploymentResult result) {
        Assertions.assertFalse(result.successful(), "Deployment was expected to fail.");
    }

    void assertDeploymentExists(final DeploymentDescription deployment) throws IOException {
        Assertions.assertTrue(deploymentManager.hasDeployment(deployment.getName()),
                () -> String.format("Expected deployment %s to exist in the content repository.", deployment));
        for (String serverGroup : deployment.getServerGroups()) {
            Assertions.assertTrue(deploymentManager.hasDeployment(deployment.getName(), serverGroup),
                    () -> String.format("Expected deployment %s to exist in the content repository.", deployment));
        }
    }

    void assertDeploymentExists(final DeploymentDescription deployment, final boolean shouldBeEnabled) throws IOException {
        Assertions.assertTrue(deploymentManager.hasDeployment(deployment.getName()),
                () -> String.format("Expected deployment %s to exist in the content repository.", deployment));
        final Set<String> serverGroups = deployment.getServerGroups();
        if (serverGroups.isEmpty()) {
            Assertions.assertEquals(shouldBeEnabled, deploymentManager.isEnabled(deployment.getName()), () ->

            String.format("Expected enabled attribute to be %s for deployment %s", shouldBeEnabled, deployment));
        } else {
            for (String serverGroup : serverGroups) {
                Assertions.assertTrue(deploymentManager.hasDeployment(deployment.getName(), serverGroup),
                        () -> String.format("Expected deployment %s to exist on %s.", deployment, serverGroup));
                Assertions.assertEquals(shouldBeEnabled, deploymentManager.isEnabled(deployment.getName(), serverGroup),
                        () -> String.format("Expected enabled attribute to be %s for deployment %s on server group %s",
                                shouldBeEnabled, deployment, serverGroup));
            }
        }
    }

    void assertDeploymentDoesNotExist(final DeploymentDescription deployment) throws IOException {
        Assertions.assertFalse(deploymentManager.hasDeployment(deployment.getName()),
                () -> String.format("Expected deployment %s to not exist.", deployment));
    }

    void assertDeploymentEnabled(final DeploymentDescription deployment) throws IOException {
        final Set<String> serverGroups = deployment.getServerGroups();
        if (serverGroups.isEmpty()) {
            Assertions.assertTrue(deploymentManager.isEnabled(deployment.getName()),
                    () -> String.format("Expected deployment %s to be enabled", deployment));
        } else {
            for (String serverGroup : serverGroups) {
                Assertions.assertTrue(deploymentManager.isEnabled(deployment.getName(), serverGroup),
                        () -> String.format("Expected deployment %s to be enabled on %s", deployment, serverGroup));
            }
        }
    }

    void assertDeploymentDisabled(final DeploymentDescription deployment) throws IOException {
        final Set<String> serverGroups = deployment.getServerGroups();
        if (serverGroups.isEmpty()) {
            Assertions.assertFalse(deploymentManager.isEnabled(deployment.getName()),
                    () -> String.format("Expected deployment %s to be disabled", deployment));
        } else {
            for (String serverGroup : serverGroups) {
                Assertions.assertFalse(deploymentManager.isEnabled(deployment.getName(), serverGroup),
                        () -> String.format("Expected deployment %s to be disabled on %s", deployment, serverGroup));
            }
        }
    }

    DeployResult deployForSuccess(final Deployment deployment) throws IOException {
        return deployForSuccess(deployment, false);
    }

    DeployResult deployForSuccess(final Deployment deployment, final boolean force) throws IOException {
        final DeploymentResult result;
        if (force) {
            result = deploymentManager.forceDeploy(deployment);
        } else {
            result = deploymentManager.deploy(deployment);
        }
        assertSuccess(result);
        assertDeploymentExists(deployment);
        return createResult(result, deployment);
    }

    Map<Deployment, DeployResult> deployForSuccess(final Set<Deployment> deployments) throws IOException, InterruptedException {
        return deployForSuccess(deployments, false);
    }

    Map<Deployment, DeployResult> deployForSuccess(final Set<Deployment> deployments, final boolean force)
            throws IOException, InterruptedException {
        final DeploymentResult result;
        if (force) {
            result = deploymentManager.forceDeploy(deployments);
        } else {
            result = deploymentManager.deploy(deployments);
        }
        assertSuccess(result);
        final Map<Deployment, DeployResult> results = new LinkedHashMap<>();
        for (Deployment deployment : deployments) {
            assertDeploymentExists(deployment);
            results.put(deployment, createResult(result, deployment));
        }
        return results;
    }

    DeployResult redeployForSuccess(final Deployment deployment) throws IOException {
        final DeploymentResult result = deploymentManager.redeploy(deployment);
        assertSuccess(result);
        assertDeploymentExists(deployment);
        return createResult(result, deployment);
    }

    Map<Deployment, DeployResult> redeployForSuccess(final Set<Deployment> deployments) throws IOException {
        final DeploymentResult result = deploymentManager.redeploy(deployments);
        assertSuccess(result);
        final Map<Deployment, DeployResult> results = new LinkedHashMap<>();
        for (Deployment deployment : deployments) {
            assertDeploymentExists(deployment);
            results.put(deployment, createResult(result, deployment));
        }
        return results;
    }

    DeploymentResult undeployForSuccess(final UndeployDescription deployment, final boolean contentShouldExist)
            throws IOException {
        return undeployForSuccess(deployment, deployment.getServerGroups(), contentShouldExist);
    }

    DeploymentResult undeployForSuccess(final UndeployDescription deployment, final Set<String> expectedServerGroups,
            final boolean contentShouldExist) throws IOException {
        final DeploymentResult result = deploymentManager.undeploy(deployment);
        assertSuccess(result);
        if (contentShouldExist) {
            final DeploymentDescription copy = SimpleDeploymentDescription.of(deployment.getName(), expectedServerGroups);
            assertDeploymentExists(copy, false);
        } else {
            // There's no need to check the server-groups, if the content is no longer in the repository it can't be on
            // the server groups.
            assertDeploymentDoesNotExist(deployment);
        }
        return result;
    }

    ModelNode executeOp(final ModelNode op) throws IOException {
        final ModelNode result = getClient().execute(op);
        if (Operations.isSuccessfulOutcome(result)) {
            return Operations.readResult(result);
        }
        Assertions.fail(String.format("Operation %s failed: %s", op, Operations.getFailureDescription(result)));
        // Should never be reached
        return new ModelNode();
    }

    Deployment createDefaultDeployment(final String name) {
        return createDeployment(createDefaultArchive(name));
    }

    Deployment createDeployment(final Archive<?> archive) {
        return configureDeployment(Deployment.of(archive.as(ZipExporter.class)
                .exportAsInputStream(), archive.getName()));
    }

    Deployment configureDeployment(final Deployment deployment) {
        return deployment;
    }

    private byte[] readDeploymentHash(final String deploymentName) throws IOException {
        final ModelNode op = Operations.createReadAttributeOperation(
                Operations.createAddress(ClientConstants.DEPLOYMENT, deploymentName), ClientConstants.CONTENT);
        // Response should be a list, we only need the first entry
        final ModelNode response = executeOp(op).get(0);
        if (response.hasDefined("hash")) {
            return response.get("hash").asBytes();
        }
        return new byte[0];
    }

    private long readLastEnabledTime(final ModelNode address) throws IOException {
        final ModelNode op = Operations.createReadAttributeOperation(address, "enabled-time");
        final ModelNode response = executeOp(op);
        return response.isDefined() ? response.asLong() : 0L;
    }

    private DeployResult createResult(final DeploymentResult result, final Deployment deployment) throws IOException {
        return new DeployResult(result, deployment, readDeploymentHash(deployment.getName()),
                readLastEnabledTime(createDeploymentResourceAddress(deployment.getName())));
    }

    private Map<Deployment, DeployResult> createResult(final DeploymentResult result, final Collection<Deployment> deployments)
            throws IOException {
        final Map<Deployment, DeployResult> results = new LinkedHashMap<>(deployments.size());
        for (Deployment deployment : deployments) {
            results.put(deployment, createResult(result, deployment));
        }
        return results;
    }

    static WebArchive createDefaultArchive(final String name) {
        return ShrinkWrap.create(WebArchive.class, name)
                .addClass(Simple.class);
    }

    static void safeClose(final Closeable closeable) {
        if (closeable != null)
            try {
                closeable.close();
            } catch (Exception ignore) {
            }
    }

    private static void deletePath(final Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.deleteIfExists(path);
        }
    }

    private static String bytesToHexString(final byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            // noinspection MagicNumber
            builder.append(hexChars[b >> 4 & 0x0f]).append(hexChars[b & 0x0f]);
        }
        return builder.toString();
    }

    @SuppressWarnings("unused")
    static class DeployResult {
        private final DeploymentResult deploymentResult;
        private final Deployment deployment;
        private final byte[] hash;
        private final long enabledTime;

        DeployResult(final DeploymentResult deploymentResult, final Deployment deployment, final byte[] hash,
                final long enabledTime) {
            this.deploymentResult = deploymentResult;
            this.deployment = deployment;
            this.hash = hash;
            this.enabledTime = enabledTime;
        }
    }

}
