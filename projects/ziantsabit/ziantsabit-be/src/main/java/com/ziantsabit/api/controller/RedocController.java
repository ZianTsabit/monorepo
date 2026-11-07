package com.ziantsabit.api.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

/** {@code /api/redoc/}: a ReDoc page over {@code /api/schema/}. */
@RestController
@Hidden
public class RedocController {

    // ReDoc's own script from its CDN, as drf-spectacular's page did, so the
    // page needs internet while the schema itself does not.
    private static final String PAGE = """
            <!DOCTYPE html>
            <html>
              <head>
                <title>ziantsabit.com API</title>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
              </head>
              <body>
                <redoc spec-url="/api/schema"></redoc>
                <script src="https://cdn.jsdelivr.net/npm/redoc@2/bundles/redoc.standalone.js"></script>
              </body>
            </html>
            """;

    @GetMapping(value = "/api/redoc", produces = MediaType.TEXT_HTML_VALUE)
    public String redoc() {
        return PAGE;
    }
}
