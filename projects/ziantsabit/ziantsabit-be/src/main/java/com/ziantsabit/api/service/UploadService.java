package com.ziantsabit.api.service;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ziantsabit.api.config.AppProperties;
import com.ziantsabit.api.dto.UploadedImage;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.storage.ObjectStorage;
import com.ziantsabit.api.util.Slugs;
import com.ziantsabit.api.validation.ImageFormat;

/**
 * Image uploads for the admin editor: a file in, a URL out.
 *
 * <p>Deliberately not part of a post write. The New Post form has no post yet,
 * and a cover image and an inline {@code ![](...)} in a body want exactly the
 * same thing from the server. The cost is that nothing links an object back to
 * the post using it -- deleting a post leaves its images behind, which inline
 * images would do regardless, since their only record is Markdown text.
 */
@Service
public class UploadService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectStorage storage;
    private final long maxSize;

    public UploadService(ObjectStorage storage, AppProperties properties) {
        this.storage = storage;
        this.maxSize = properties.maxUploadSize();
    }

    /**
     * Check an uploaded file is an image this site serves, store it, and return
     * where it can be loaded from.
     *
     * @param sentAsText whether a {@code file} field arrived as plain text rather
     *                   than a file, which earns its own message
     */
    public UploadedImage upload(MultipartFile file, boolean sentAsText) throws IOException {
        if (file == null) {
            throw ApiException.field("file", sentAsText
                    ? "The submitted data was not a file. Check the encoding type on the form."
                    : "No file was submitted.");
        }
        if (file.isEmpty()) {
            throw ApiException.field("file", "The submitted file is empty.");
        }
        byte[] bytes = file.getBytes();

        String format = ImageFormat.detect(bytes);
        if (format == null) {
            throw ApiException.field("file",
                    "Upload a valid image. The file you uploaded was either not an image or a corrupted image.");
        }
        // Checked after the bytes, so an oversized *non*-image is reported as
        // the wrong kind of file rather than as a size problem.
        if (bytes.length > maxSize) {
            throw ApiException.field("file",
                    "Image is " + bytes.length / 1024 + " KB; the limit is " + maxSize / 1024 + " KB.");
        }
        if (!ImageFormat.ALLOWED.containsKey(format)) {
            throw ApiException.field("file", format + " images are not supported. Use one of: "
                    + String.join(", ", ImageFormat.ALLOWED.keySet().stream().sorted().toList()) + ".");
        }

        String key = objectKey(file.getOriginalFilename(), ImageFormat.ALLOWED.get(format));
        storage.put(key, bytes, ImageFormat.CONTENT_TYPES.get(format));
        return new UploadedImage(storage.url(key), key);
    }

    /**
     * {@code uploads/YYYY/MM/<slugified name>-<8 hex>.<ext>}: date-partitioned
     * so the bucket stays browsable by hand, and suffixed with random hex so two
     * files called {@code screenshot.png} cannot collide -- and so a key cannot
     * be guessed from the filename someone happened to upload.
     */
    static String objectKey(String filename, String extension) {
        String stem = Slugs.slugify(stem(filename == null ? "" : filename));
        if (stem.length() > 60) {
            stem = stem.substring(0, 60);
        }
        if (stem.isEmpty()) {
            stem = "image";
        }
        byte[] suffix = new byte[4];
        RANDOM.nextBytes(suffix);
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        return String.format("uploads/%04d/%02d/%s-%s.%s",
                now.getYear(), now.getMonthValue(), stem, HexFormat.of().formatHex(suffix), extension);
    }

    /** The name without its directory or last extension, as Python's Path.stem. */
    private static String stem(String filename) {
        String name = filename.substring(filename.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
