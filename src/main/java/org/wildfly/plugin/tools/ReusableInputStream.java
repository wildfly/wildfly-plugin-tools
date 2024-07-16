/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.logging.Logger;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class ReusableInputStream extends InputStream {
    private static final Logger LOGGER = Logger.getLogger(ReusableInputStream.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final String name;
    private final Path content;
    private final Thread shutdownHook;
    private boolean closed;
    private InputStream delegate;

    ReusableInputStream(final InputStream content, final String name) {
        this.name = name;
        this.content = createContent(content, name);
        this.shutdownHook = new Thread(new Thread(() -> {
            try {
                Files.deleteIfExists(ReusableInputStream.this.content);
            } catch (IOException e) {
                LOGGER.errorf(e, "Failed to content file delete file %s for deployment %s.", ReusableInputStream.this.content,
                        name);
            }
        }));
        // Add a shutdown hook to delete the file on close
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        this.closed = false;
    }

    @Override
    public int read() throws IOException {
        return getDelegate().read();
    }

    @Override
    public int read(final byte[] b) throws IOException {
        return getDelegate().read(b);
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        return getDelegate().read(b, off, len);
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        return getDelegate().readAllBytes();
    }

    @Override
    public byte[] readNBytes(final int len) throws IOException {
        return getDelegate().readNBytes(len);
    }

    @Override
    public int readNBytes(final byte[] b, final int off, final int len) throws IOException {
        return getDelegate().readNBytes(b, off, len);
    }

    @Override
    public long skip(final long n) throws IOException {
        return getDelegate().skip(n);
    }

    @Override
    public int available() throws IOException {
        return getDelegate().available();
    }

    @Override
    public void close() {
        close(false);
    }

    @Override
    public void mark(final int readlimit) {
        try {
            getDelegate().mark(readlimit);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create the delegate.", e);
        }
    }

    @Override
    public void reset() {
        close(false);
    }

    @Override
    public boolean markSupported() {
        try {
            return getDelegate().markSupported();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create the delegate.", e);
        }
    }

    @Override
    public long transferTo(final OutputStream out) throws IOException {
        return getDelegate().transferTo(out);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ReusableInputStream)) {
            return false;
        }
        final ReusableInputStream other = (ReusableInputStream) obj;
        return Objects.equals(name, other.name);
    }

    @Override
    public String toString() {
        return "ReusableInputStream[" + name + ']';
    }

    /**
     * Closes this input stream and deletes the backing file if {@code deleteContent} is {@code true}.
     *
     * @param deleteContent {@code true} to delete the backing file and close this input stream, meaning it can no longer be
     *                          read from
     */
    void close(final boolean deleteContent) {
        lock.lock();
        try {
            if (delegate != null) {
                delegate.close();
                delegate = null;
            }
            if (deleteContent) {
                closed = true;
                Files.deleteIfExists(content);
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close delegate stream.", e);
        } finally {
            lock.unlock();
        }
    }

    private InputStream getDelegate() throws IOException {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("This stream has been closed and can no longer be read from.");
            }
            if (delegate == null) {
                delegate = Files.newInputStream(content);
            }
            return delegate;
        } finally {
            lock.unlock();
        }
    }

    private static Path createContent(final InputStream content, final String name) {
        try (content) {
            final var file = Files.createTempFile("deployment-", "-" + name);
            // Use REPLACE_EXISTING because the file is created above
            Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy the content " + content, e);
        }
    }
}
