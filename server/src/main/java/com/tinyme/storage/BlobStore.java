package com.tinyme.storage;

import java.io.IOException;
import java.io.InputStream;

public interface BlobStore {
    void put(String key, byte[] bytes) throws IOException;
    InputStream get(String key) throws IOException;
    void delete(String key) throws IOException;
    void deleteAll() throws IOException;
}
