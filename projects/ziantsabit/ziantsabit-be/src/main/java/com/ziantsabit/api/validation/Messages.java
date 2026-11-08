package com.ziantsabit.api.validation;

/**
 * The words a field error is reported in. Constants, because a constraint
 * annotation's {@code message} has to be one; the SPA shows them beside the
 * matching input, so they are written for the person filling the form in.
 */
public final class Messages {

    public static final String REQUIRED = "This field is required.";
    public static final String BLANK = "This field may not be blank.";
    public static final String TOO_LONG = "Must be at most {max} characters.";
    public static final String URL = "Enter a valid URL.";
    public static final String SLUG = "Use only letters, numbers, underscores and hyphens.";
    public static final String CHOICE = "Must be one of: {choices}.";
    public static final String INVALID = "Invalid value.";
    public static final String NOT_AN_OBJECT = "Expected a JSON object.";

    private Messages() {
    }
}
