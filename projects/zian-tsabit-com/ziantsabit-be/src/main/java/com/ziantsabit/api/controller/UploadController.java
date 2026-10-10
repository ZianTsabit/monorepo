package com.ziantsabit.api.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ziantsabit.api.dto.ApiDocs;
import com.ziantsabit.api.dto.UploadedImage;
import com.ziantsabit.api.service.UploadService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/** Image uploads for the admin editor: a file in, a URL out. See UploadService. */
@RestController
@Tag(name = "uploads")
public class UploadController {

    private final UploadService service;

    public UploadController(UploadService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/uploads/images",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    @Operation(summary = "Upload one image and get back its public URL, for a cover_image_url or a Markdown "
            + "image in a body. Authenticated callers only.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = @Schema(implementation = ApiDocs.ImageUploadInput.class)))
    public ResponseEntity<UploadedImage> upload(
            @RequestParam(name = "file", required = false) MultipartFile file, HttpServletRequest request)
            throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.upload(file, request.getParameter("file") != null));
    }
}
