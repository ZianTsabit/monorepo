package com.ziantsabit.api.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ziantsabit.api.storage.ObjectStorage;

/** The bucket, in memory: the suite never needs RustFS and never leaves uploads behind. */
public class InMemoryStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, bytes);
    }

    @Override
    public String url(String key) {
        return "http://storage.test/ziantsabit-media/" + key;
    }

    public Map<String, byte[]> objects() {
        return objects;
    }

    void clear() {
        objects.clear();
    }
}
