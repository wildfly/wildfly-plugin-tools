/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import org.jboss.dmr.ModelNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class DeploymentResultTestCase {

    @Test
    public void testSuccessful() {
        Assertions.assertTrue(DeploymentResult.SUCCESSFUL.successful());
        Assertions.assertNull(DeploymentResult.SUCCESSFUL.getFailureMessage());
        Assertions.assertFalse(DeploymentResult.SUCCESSFUL.asModelNode().isDefined());

        final DeploymentResult deploymentResult = new DeploymentResult(createCompositeOutcome());
        Assertions.assertTrue(deploymentResult.successful());
        Assertions.assertNull(deploymentResult.getFailureMessage());
        Assertions.assertTrue(deploymentResult.asModelNode().isDefined());
    }

    @Test
    public void testFailed() {
        // Create a failure description
        final ModelNode failureModel = createCompositeOutcome(
                "WFLYCTL0212: Duplicate resource [(\"deployment\" => \"foo.war\")]");

        DeploymentResult deploymentResult = new DeploymentResult(failureModel);
        Assertions.assertFalse(deploymentResult.successful());
        Assertions.assertEquals("WFLYCTL0212: Duplicate resource [(\"deployment\" => \"foo.war\")]",
                deploymentResult.getFailureMessage());
        Assertions.assertTrue(deploymentResult.asModelNode().isDefined());

        // Create a failure not based on model node
        deploymentResult = new DeploymentResult("Test failed message");
        Assertions.assertFalse(deploymentResult.successful());
        Assertions.assertEquals("Test failed message", deploymentResult.getFailureMessage());
        Assertions.assertFalse(deploymentResult.asModelNode().isDefined());

        // Create a failure not based on model node
        deploymentResult = new DeploymentResult("Test failed message %d", 2);
        Assertions.assertFalse(deploymentResult.successful());
        Assertions.assertEquals("Test failed message 2", deploymentResult.getFailureMessage());
        Assertions.assertFalse(deploymentResult.asModelNode().isDefined());
    }

    @Test
    public void testFailureAssertion() {
        try {
            new DeploymentResult("Test failure result").assertSuccess();
            Assertions.fail("Expected the deployment result to be a failure result");
        } catch (DeploymentException ignore) {
        }
        try {
            new DeploymentResult("Test failure result %d", 2).assertSuccess();
            Assertions.fail("Expected the deployment result to be a failure result");
        } catch (DeploymentException ignore) {
        }

        // Create a failure description
        try {
            new DeploymentResult(createCompositeOutcome("WFLYCTL0212: Duplicate resource [(\"deployment\" => \"foo.war\")]"))
                    .assertSuccess();
            Assertions.fail("Expected the deployment result to be a failure result");
        } catch (DeploymentException ignore) {
        }
    }

    private static ModelNode createCompositeOutcome() {
        return createCompositeOutcome(null);
    }

    private static ModelNode createCompositeOutcome(final String failureMessage) {
        final ModelNode response = createOutcome(failureMessage);
        final ModelNode result = response.get("result");
        result.get("step-1").set(createOutcome(failureMessage));
        return response;
    }

    private static ModelNode createOutcome(final String failureMessage) {
        final ModelNode result = new ModelNode().setEmptyObject();
        if (failureMessage == null) {
            result.get("outcome").set("success");
        } else {
            result.get("outcome").set("failed");
            result.get("failure-description").set(failureMessage);
            result.get("rolled-back").set(true);
        }
        return result;
    }
}
