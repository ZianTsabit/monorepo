package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.support.Api.Part;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** Ported from the Django suite's ImageUploadTests. */
class ImageUploadTest extends ApiTestBase {

    static final String URL = "/api/uploads/images/";

    /** A real image, encoded in memory: the endpoint looks at the bytes. */
    static byte[] image(int size, String format) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, size, size);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }

    static Part png(String name) {
        return Part.file(name, "image/png", image(4, "png"));
    }

    @BeforeEach
    void fixtures() {
        createUser();
    }

    Response upload(Part part) {
        return owner().multipart(URL, List.of(part));
    }

    @Test
    void anonymousCannotUpload() {
        assertThat(client.multipart(URL, List.of(png("Screen Shot.png"))).status()).isEqualTo(403);
        assertThat(storage.objects()).isEmpty();
    }

    @Test
    void uploadReturnsAUrl() {
        Response response = upload(png("Screen Shot.png"));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.string("url")).isEqualTo("http://storage.test/ziantsabit-media/" + response.string("name"));
        assertThat(response.string("name")).startsWith("uploads/");
        assertThat(storage.objects()).containsKey(response.string("name"));
    }

    @Test
    void storedNameIsSlugifiedAndSuffixed() {
        String first = upload(png("Screen Shot.png")).string("name");
        String second = upload(png("Screen Shot.png")).string("name");
        assertThat(first).matches("^uploads/\\d{4}/\\d{2}/screen-shot-[0-9a-f]{8}\\.png$");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void extensionFollowsTheRealFormatNotTheFilename() {
        Response response = upload(Part.file("photo.png", "image/png", image(4, "jpg")));
        assertThat(response.string("name")).endsWith(".jpg");
    }

    @Test
    void gifAndWebpAreAccepted() {
        assertThat(upload(Part.file("a.gif", "image/gif", image(4, "gif"))).string("name")).endsWith(".gif");
        // The smallest well-formed lossless WebP: RIFF header, VP8L chunk.
        byte[] webp = java.util.HexFormat.of().parseHex(
                "524946461a000000574542505650384c0d0000002f0000000700e09a8b54b7ff0100");
        assertThat(upload(Part.file("a.webp", "image/webp", webp)).string("name")).endsWith(".webp");
    }

    @Test
    void nonImageIsRejected() {
        Response response = upload(Part.file("payload.png", "image/png",
                "MZ\u0000not an image".getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.map()).containsKey("file");
    }

    @Test
    void anImageInAFormatTheSiteDoesNotServeIsNamed() {
        Response response = upload(Part.file("a.bmp", "image/bmp", image(4, "bmp")));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("file"))
                .isEqualTo(List.of("BMP images are not supported. Use one of: GIF, JPEG, PNG, WEBP."));
    }

    @Test
    void missingFileIsRejected() {
        Response response = owner().multipart(URL, List.of(new Part("other", null, null, "x".getBytes())));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("file")).isEqualTo(List.of("No file was submitted."));
    }

    @Test
    void anEmptyFileIsRejected() {
        Response response = upload(Part.file("empty.png", "image/png", new byte[0]));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("file")).isEqualTo(List.of("The submitted file is empty."));
    }

    @Test
    void jsonIsTheWrongEncoding() {
        Response response = owner().post(URL, body("file", "x"));
        assertThat(response.status()).isEqualTo(415);
    }
}
