/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import org.wildfly.plugin.tools.Deployment;
import org.wildfly.plugin.tools.Environment;

/**
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
public class DomainServerManagerListenerIT extends AbstractServerManagerListenerIT {

    @Override
    Deployment createDeployment(final boolean forFailure) {
        return super.createDeployment(forFailure).addServerGroup("main-server-group");
    }

    @Override
    protected Configuration<?> configuration() {
        return Environment.domainConfiguration().shutdownOnClose(true);
    }
}
