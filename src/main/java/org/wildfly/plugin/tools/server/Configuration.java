/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.UncheckedIOException;
import java.net.UnknownHostException;

import org.jboss.as.controller.client.ModelControllerClient;

/**
 * The configuration used when starting a server.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class Configuration {
    private ModelControllerClient client;
    private String managementAddress;
    private int managementPort;
    private boolean shutdownOnClose;

    public static Configuration create() {
        return new Configuration();
    }

    /**
     * Sets the client to use for the server manager.
     * <p>
     * If the the server manager is {@linkplain ServerManager#close() closed}, the client will also be closed.
     * </p>
     *
     * @param client the client to use to communicate with the server
     *
     * @return this configuration
     */
    public Configuration client(final ModelControllerClient client) {
        this.client = client;
        return this;
    }

    /**
     * The client set on the configuration or a new client.
     *
     * @return the client to use
     */
    protected ModelControllerClient client() {
        if (client == null) {
            try {
                return ModelControllerClient.Factory.create(managementAddress(), managementPort());
            } catch (UnknownHostException e) {
                throw new UncheckedIOException(e);
            }
        }
        return client;
    }

    /**
     * The management address to use for the client if the {@linkplain #client(ModelControllerClient) client} has
     * not been set.
     *
     * @param managementAddress the management address, default is {@code localhost}
     *
     * @return this configuration
     */
    public Configuration managementAddress(final String managementAddress) {
        this.managementAddress = managementAddress;
        return this;
    }

    /**
     * The management address set or {@code 127.0.0.1} if not set.
     *
     * @return the management address
     */
    protected String managementAddress() {
        return managementAddress == null ? "127.0.0.1" : managementAddress;
    }

    /**
     * The management port to use for the client if the {@linkplain #client(ModelControllerClient) client} has
     * not been set.
     *
     * @param managementPort the management port, default is {@code 9990}
     *
     * @return this configuration
     */
    public Configuration managementPort(final int managementPort) {
        this.managementPort = managementPort;
        return this;
    }

    /**
     * The management port or {@code 9990} if set to 0 or less.
     *
     * @return the management port
     */
    protected int managementPort() {
        return managementPort > 0 ? managementPort : 9990;
    }

    /**
     * When set to {@code true} the server will be {@linkplain ServerManager#shutdown() shutdown} when the server
     * manager is {@linkplain ServerManager#close() closed}.
     *
     * @param shutdownOnClose {@code true} to shutdown the server when the server manager is closed
     *
     * @return this configuration
     */
    public Configuration shutdownOnClose(final boolean shutdownOnClose) {
        this.shutdownOnClose = shutdownOnClose;
        return this;
    }

    /**
     * Indicates if the server should be shutdown when the {@link ServerManager} is closed.
     *
     * @return {@code true} to shutdown the server when the {@linkplain ServerManager#close() is closed}
     */
    protected boolean shutdownOnClose() {
        return shutdownOnClose;
    }
}
