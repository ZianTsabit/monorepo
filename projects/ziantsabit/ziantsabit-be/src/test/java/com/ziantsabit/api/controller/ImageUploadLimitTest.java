package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.ziantsabit.api.support.Api.Part;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** Its own class, and so its own context: the size limit is read at startup. */
@TestPropertySource(properties = "app.max-upload-size=100")
class ImageUploadLimitTest extends ApiTestBase {

    @Test
    void oversizedImageIsRejected() {
        createUser();
        Response response = owner().multipart(ImageUploadTest.URL,
                List.of(Part.file("big.png", "image/png", ImageUploadTest.image(400, "png"))));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("file").toString()).contains("; the limit is 0 KB.");
    }
}
