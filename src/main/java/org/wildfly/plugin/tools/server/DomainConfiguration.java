/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import org.wildfly.core.launcher.CommandBuilder;

/**
 * Represents the configuration used to boot a domain server.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class DomainConfiguration extends Configuration<DomainConfiguration> {
    protected DomainConfiguration(final CommandBuilder commandBuilder) {
        super(commandBuilder);
    }

    @Override
    protected LaunchType launchType() {
        return LaunchType.DOMAIN;
    }

    @Override
    protected DomainConfiguration self() {
        return this;
    }
}
