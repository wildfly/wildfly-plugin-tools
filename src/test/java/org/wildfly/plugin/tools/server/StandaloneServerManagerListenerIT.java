/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import org.wildfly.plugin.tools.Environment;

/**
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
public class StandaloneServerManagerListenerIT extends AbstractServerManagerListenerIT {
    @Override
    protected Configuration<?> configuration() {
        return Environment.standaloneConfiguration().shutdownOnClose(true);
    }
}
