package com.ziantsabit.api.validation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * What an uploaded file's bytes say it is -- never what its name or the
 * browser's Content-Type claim. A renamed archive sent as {@code image/png}
 * fails here rather than becoming a broken image on a public page.
 */
public final class ImageFormat {

    /**
     * The formats accepted, and the extension each is stored with. The name is
     * the format's own, as Pillow reported it; the extension follows the bytes,
     * so a JPEG uploaded as {@code photo.png} is stored as {@code .jpg}.
     *
     * <p>SVG is the one worth naming as absent: it can carry script.
     */
    public static final Map<String, String> ALLOWED = Map.of("GIF", "gif", "JPEG", "jpg", "PNG", "png", "WEBP", "webp");

    public static final Map<String, String> CONTENT_TYPES = Map.of(
            "GIF", "image/gif", "JPEG", "image/jpeg", "PNG", "image/png", "WEBP", "image/webp");

    private ImageFormat() {
    }

    /**
     * The format name ("PNG", "JPEG", "BMP", ...), or null when the bytes are
     * not an image at all or are too damaged to read the header of.
     */
    public static String detect(byte[] bytes) {
        String format = sniff(bytes);
        if (format == null) {
            return null;
        }
        if (format.equals("WEBP")) {
            // ImageIO has no WebP reader; the RIFF container and a VP8 chunk
            // header are the whole of the check.
            String chunk = ascii(bytes, 12, 4);
            return chunk.equals("VP8 ") || chunk.equals("VP8L") || chunk.equals("VP8X") ? format : null;
        }
        return readsHeader(bytes) ? format : null;
    }

    private static String sniff(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return "JPEG";
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "PNG";
        }
        if (ascii(bytes, 0, 6).equals("GIF87a") || ascii(bytes, 0, 6).equals("GIF89a")) {
            return "GIF";
        }
        if (ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP")) {
            return "WEBP";
        }
        // Images, but not ones this site serves. Recognised so the refusal can
        // say "BMP images are not supported" rather than "not an image".
        if (ascii(bytes, 0, 2).equals("BM")) {
            return "BMP";
        }
        if (startsWith(bytes, 'I', 'I', 0x2A, 0x00) || startsWith(bytes, 'M', 'M', 0x00, 0x2A)) {
            return "TIFF";
        }
        return null;
    }

    /** Ask ImageIO to decode the header: dimensions it can read are a real image. */
    private static boolean readsHeader(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                return reader.getWidth(0) > 0 && reader.getHeight(0) > 0;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        if (bytes.length < offset + length) {
            return "";
        }
        return new String(Arrays.copyOfRange(bytes, offset, offset + length), StandardCharsets.ISO_8859_1);
    }
}
