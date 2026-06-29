package com.antiaddiction.storage;

import com.antiaddiction.network.ApiClient;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public final class StorageKeyManager {

    public static final StorageKeyManager INSTANCE = new StorageKeyManager();

    private record KeyEntry(byte[] key, String kid) {}

    private final ConcurrentHashMap<String, KeyEntry> cache = new ConcurrentHashMap<>();

    private StorageKeyManager() {}

    public byte[] keyFor(String saveName) throws IOException {
        return fetch(saveName).key();
    }

    public String kidFor(String saveName) throws IOException {
        return fetch(saveName).kid();
    }

    private synchronized KeyEntry fetch(String saveName) throws IOException {
        KeyEntry hit = cache.get(saveName);
        if (hit != null) return hit;
        ApiClient.StorageKeyResult r = ApiClient.fetchStorageKey(saveName);
        KeyEntry entry = new KeyEntry(r.key(), r.kid());
        cache.put(saveName, entry);
        return entry;
    }

    public void invalidate(String saveName) {
        cache.remove(saveName);
    }

    public void clearAll() {
        cache.clear();
    }
}
