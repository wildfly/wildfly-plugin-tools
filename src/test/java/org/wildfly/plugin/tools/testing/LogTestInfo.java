/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.testing;

import java.lang.reflect.Method;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
public class LogTestInfo implements BeforeTestExecutionCallback, AfterTestExecutionCallback {
    private static final Logger LOGGER = Logger.getLogger(LogTestInfo.class.getPackageName());

    @Override
    public void afterTestExecution(final ExtensionContext context) throws Exception {
        LOGGER.infof("%1$s Finished Test: %2$s.%3$s %1$s", "=".repeat(20),
                context.getTestClass().map(Class::getName).orElse("<UnknownTestClass>"),
                context.getTestMethod().map(Method::getName).orElse("<UnknownMethod>"));
    }

    @Override
    public void beforeTestExecution(final ExtensionContext context) throws Exception {
        LOGGER.infof("%1$s Starting Test: %2$s.%3$s %1$s", "=".repeat(20),
                context.getTestClass().map(Class::getName).orElse("<UnknownTestClass>"),
                context.getTestMethod().map(Method::getName).orElse("<UnknownMethod>"));
    }
}
