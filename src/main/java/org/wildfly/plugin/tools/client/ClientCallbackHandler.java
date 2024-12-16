/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.client;

import java.io.Console;
import java.util.Arrays;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.RealmChoiceCallback;

/**
 * A CallbackHandler implementation to supply the username and password if required when
 * connecting to the server - if these are not available the user will be prompted to
 * supply them.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
@SuppressWarnings("unused")
public class ClientCallbackHandler implements CallbackHandler {

    private final Console console;
    private boolean promptShown = false;
    private String username;
    private char[] password;

    /**
     * Creates a new client call back prompting the user for a username and password.
     */
    public ClientCallbackHandler() {
        console = System.console();
        this.username = null;
        this.password = null;
    }

    /**
     * Creates a new client call back with the given username and password.
     *
     * @param username the username to authenticate with, can be {@code null} to prompt on the console for a username
     * @param password the password to authenticate with, can be {@code null} to prompt on the console for a password
     */
    public ClientCallbackHandler(final String username, final String password) {
        console = System.console();
        this.username = username;
        if (password != null) {
            this.password = password.toCharArray();
        }
    }

    /**
     * Creates a new client call back with the given username and password.
     *
     * @param username the username to authenticate with, can be {@code null} to prompt on the console for a username
     * @param password the password to authenticate with, can be {@code null} to prompt on the console for a password
     */
    public ClientCallbackHandler(final String username, final char[] password) {
        console = System.console();
        this.username = username;
        if (password != null) {
            this.password = Arrays.copyOf(password, password.length);
        }
    }

    @Override
    public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
        // Special case for anonymous authentication to avoid prompting user for their name.
        if (callbacks.length == 1 && callbacks[0] instanceof NameCallback) {
            ((NameCallback) callbacks[0]).setName("anonymous demo user");
            return;
        }

        for (Callback current : callbacks) {
            if (current instanceof RealmCallback) {
                final RealmCallback rcb = (RealmCallback) current;
                final String defaultText = rcb.getDefaultText();
                rcb.setText(defaultText); // For now just use the realm suggested.

                prompt(defaultText);
            } else if (current instanceof RealmChoiceCallback) {
                throw new UnsupportedCallbackException(current, "Realm choice not currently supported.");
            } else if (current instanceof NameCallback) {
                final NameCallback ncb = (NameCallback) current;
                final String userName = obtainUsername();

                ncb.setName(userName);
            } else if (current instanceof PasswordCallback) {
                PasswordCallback pcb = (PasswordCallback) current;
                char[] password = obtainPassword();

                pcb.setPassword(password);
            } else {
                throw new UnsupportedCallbackException(current);
            }
        }
    }

    private void prompt(final String realm) {
        if (!promptShown) {
            promptShown = true;
        }
    }

    private String obtainUsername() {
        if (username == null) {
            checkConsole();
            username = console.readLine("Username:");
        }
        return username;
    }

    private char[] obtainPassword() {
        if (password == null) {
            checkConsole();
            password = console.readPassword("Password:");
        }

        return password;
    }

    private void checkConsole() {
        if (console == null) {
            throw new IllegalStateException(
                    "The environment does not have a usable console. Cannot prompt for user name and password");
        }
    }

}
