package com.ziantsabit.api.dto;

/** What the editor gets back from an upload: somewhere to point an {@code <img>} at. */
public record UploadedImage(String url, String name) {
}
