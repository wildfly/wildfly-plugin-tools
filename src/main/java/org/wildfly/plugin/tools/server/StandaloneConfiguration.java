/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import org.wildfly.core.launcher.CommandBuilder;

/**
 * Represents the configuration used to boot a standalone server.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 * @since 1.2
 */
public class StandaloneConfiguration extends Configuration<StandaloneConfiguration> {
    protected StandaloneConfiguration(final CommandBuilder commandBuilder) {
        super(commandBuilder);
    }

    @Override
    protected LaunchType launchType() {
        return LaunchType.STANDALONE;
    }

    @Override
    protected StandaloneConfiguration self() {
        return this;
    }
}
