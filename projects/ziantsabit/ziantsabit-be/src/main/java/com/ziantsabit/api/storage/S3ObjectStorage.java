package com.ziantsabit.api.storage;

import java.net.URI;

import org.springframework.stereotype.Component;

import com.ziantsabit.api.config.AppProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The bucket behind {@code /api/uploads/images/}: RustFS in development and
 * production, though nothing here names the server -- any S3-compatible store
 * at {@code AWS_S3_ENDPOINT_URL} will do.
 *
 * <p>Two endpoints, and they are genuinely different addresses: the API writes
 * to {@code endpoint} (inside Docker, {@code http://rustfs:9000}, a name only
 * the compose network resolves), while every URL it hands out is built from
 * {@code publicEndpoint}, the address a visitor's browser can reach. Getting
 * that wrong is invisible on upload and breaks every image on the site.
 */
@Component
public class S3ObjectStorage implements ObjectStorage {

    private final AppProperties.Storage config;
    private volatile S3Client client;

    public S3ObjectStorage(AppProperties properties) {
        this.config = properties.storage();
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        // No ACL: RustFS does not support per-object ACLs, and the bucket policy
        // storage-init applies is what makes objects publicly readable.
        client().putObject(
                PutObjectRequest.builder().bucket(config.bucket()).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public String url(String key) {
        // Path-style (host/bucket/key) and unsigned: the bucket is public-read,
        // and a signed URL would expire under every page that had cached it.
        return stripTrailingSlash(config.publicEndpoint()) + "/" + config.bucket() + "/" + key;
    }

    /** Built on first upload, so the API starts even while the bucket is down. */
    private S3Client client() {
        S3Client existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = S3Client.builder()
                        .endpointOverride(URI.create(config.endpoint()))
                        .region(Region.of(config.region()))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
                        // A self-hosted bucket has no wildcard DNS for
                        // virtual-host addressing.
                        .forcePathStyle(true)
                        // Only checksum when S3 requires it: the SDK's newer
                        // default trailing checksums are a corner of the
                        // protocol S3-compatible servers do not all speak.
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .httpClient(UrlConnectionHttpClient.create())
                        .build();
            }
            return client;
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
