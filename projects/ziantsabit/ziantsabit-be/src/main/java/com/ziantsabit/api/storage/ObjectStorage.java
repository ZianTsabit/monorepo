package com.ziantsabit.api.storage;

/**
 * Where uploaded images go: an S3-compatible bucket in every real environment
 * (see S3ObjectStorage), an in-memory map in the test suite, which therefore
 * never needs RustFS running and never leaves test uploads in a real bucket.
 */
public interface ObjectStorage {

    /** Store the bytes under {@code key}. */
    public void put(String key, byte[] bytes, String contentType);

    /** The public URL a browser loads {@code key} from. */
    public String url(String key);
}
