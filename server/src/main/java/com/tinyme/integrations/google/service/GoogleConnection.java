package com.tinyme.integrations.google.service;

import java.io.IOException;

/** Google account lifecycle operations used by settings and delete-all. */
public interface GoogleConnection {
    /** Revokes remote consent when possible and clears the local connection state. */
    void disconnect() throws IOException, InterruptedException;
}
