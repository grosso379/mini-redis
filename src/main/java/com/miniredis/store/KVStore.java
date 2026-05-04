package com.miniredis.store;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class KVStore {

    private final ConcurrentHashMap<String, byte[]> map = new ConcurrentHashMap<>();

    public byte[] put(String key, byte[] value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        return map.put(key, value);
    }

    public byte[] get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return map.get(key);
    }

    public byte[] delete(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return map.remove(key);
    }

    public int size() {
        return map.size();
    }
}
