/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import static org.jboss.as.controller.client.helpers.ClientConstants.CONTENT;
import static org.jboss.as.controller.client.helpers.ClientConstants.PATH;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.dmr.ModelNode;

/**
 * Allows content to be added to an operation. The content will be attached to an {@link OperationBuilder} with either
 * the {@link OperationBuilder#addInputStream(InputStream)} or {@link OperationBuilder#addFileAsAttachment(File)}
 * depending on the type of content being used.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
abstract class DeploymentContent implements AutoCloseable {

    /**
     * Adds the content to the operation.
     *
     * @param builder the builder used to attach the content to
     * @param op      the deployment operation to be modified with the information required to represent the content
     *                    being deployed
     */
    abstract void addContentToOperation(OperationBuilder builder, ModelNode op);

    /**
     * If a name can be resolved from the content that name will be used, otherwise {@code null} will be returned.
     *
     * @return the name resolved from the content or {@code null} if no name could be resolved
     */
    String resolvedName() {
        return null;
    }

    @Override
    public void close() {
        // Do nothing by default
    }

    /**
     * Creates new deployment content based on a file system path.
     * <p>
     * The {@link #resolvedName()} will return the name of the file.
     * </p>
     *
     * @param content the path to the content
     *
     * @return the deployment content
     */
    static DeploymentContent of(final Path content) {
        return new DeploymentContent() {

            @Override
            void addContentToOperation(final OperationBuilder builder, final ModelNode op) {
                final ModelNode contentNode = op.get(CONTENT);
                final ModelNode contentItem = contentNode.get(0);
                // If the content points to a directory we are deploying exploded content
                if (Files.isDirectory(content)) {
                    contentItem.get(PATH).set(content.toAbsolutePath().toString());
                    contentItem.get("archive").set(false);
                } else {
                    // The index is 0 based so use the input stream count before adding the input stream
                    contentItem.get(ClientConstants.INPUT_STREAM_INDEX).set(builder.getInputStreamCount());
                    builder.addFileAsAttachment(content.toFile());
                }
            }

            @Override
            String resolvedName() {
                return content.getFileName().toString();
            }

            @Override
            public String toString() {
                return String.format("%s(%s)", DeploymentContent.class.getName(), content);
            }
        };
    }

    /**
     * Creates new deployment content based on the stream content. The stream content is copied, stored in-memory and
     * closed.
     *
     * @param content the content to deploy
     * @param name    the name for the deployment
     *
     * @return the deployment content
     */
    static DeploymentContent of(final InputStream content, final String name) {
        final ReusableInputStream copiedContent = new ReusableInputStream(content, name);
        return new DeploymentContent() {
            @Override
            void addContentToOperation(final OperationBuilder builder, final ModelNode op) {
                // Close before attempting to re-use.
                copiedContent.reset();
                final ModelNode contentNode = op.get(CONTENT);
                final ModelNode contentItem = contentNode.get(0);
                // The index is 0 based so use the input stream count before adding the input stream
                contentItem.get(ClientConstants.INPUT_STREAM_INDEX).set(builder.getInputStreamCount());
                builder.addInputStream(copiedContent);
            }

            @Override
            public String toString() {
                return String.format("%s(%s)", DeploymentContent.class.getName(), copiedContent);
            }

            @Override
            public void close() {
                copiedContent.close(true);
            }
        };
    }

    /**
     * Creates new deployment content based on the {@linkplain URL URL}. The server will require access to the URL.
     *
     * @param url the URL of the content to deploy
     *
     * @return the deployment content
     */
    static DeploymentContent of(final URL url) {
        return new DeploymentContent() {
            @Override
            void addContentToOperation(final OperationBuilder builder, final ModelNode op) {
                final ModelNode contentNode = op.get(CONTENT);
                final ModelNode contentItem = contentNode.get(0);
                contentItem.get("url").set(url.toExternalForm());
            }

            @Override
            String resolvedName() {
                final String path = url.getPath();
                final int index = path.lastIndexOf('/');
                if (index >= 0) {
                    return path.substring(index + 1);
                }
                return path;
            }

            @Override
            public String toString() {
                return String.format("%s(%s)", DeploymentContent.class.getName(), url.toExternalForm());
            }
        };
    }

    /**
     * Creates new deployment content based on a file system path.
     * <p>
     * The {@link #resolvedName()} will return the name of the file.
     * </p>
     *
     * @param content the path to the content
     *
     * @return the deployment content
     */
    static DeploymentContent local(final Path content) {
        if (Files.notExists(content)) {
            throw new IllegalArgumentException(String.format("File or directory %s does not exist.", content));
        }
        return new DeploymentContent() {

            @Override
            void addContentToOperation(final OperationBuilder builder, final ModelNode op) {
                final ModelNode contentNode = op.get(CONTENT);
                final ModelNode contentItem = contentNode.get(0);
                contentItem.get(PATH).set(content.toAbsolutePath().toString());
                contentItem.get("archive").set(!Files.isDirectory(content));
            }

            @Override
            String resolvedName() {
                return content.getFileName().toString();
            }

            @Override
            public String toString() {
                return String.format("%s(%s)", DeploymentContent.class.getName(), content);
            }
        };
    }
}
