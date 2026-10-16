package com.tinyme.integrations.google.service;

import java.io.IOException;

/** Google account lifecycle operations used by settings and delete-all. */
public interface GoogleConnection {
    String start();

    void complete(String code, String state) throws IOException, InterruptedException;

    GoogleConnectionStatus status();

    void discardState(String state);

    /** Revokes remote consent when possible and clears the local connection state. */
    void disconnect() throws IOException, InterruptedException;
}
