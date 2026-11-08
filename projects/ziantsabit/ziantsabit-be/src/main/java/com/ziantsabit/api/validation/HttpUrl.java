package com.ziantsabit.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.URISyntaxException;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * An absolute http(s) URL with a real host -- a dotted name, {@code localhost}
 * or an IP literal -- or blank for "none". Used on the cover-image fields,
 * where a typo would otherwise be stored and break an {@code <img>} on a
 * public page.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = HttpUrl.Validator.class)
public @interface HttpUrl {

    String message() default Messages.URL;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<HttpUrl, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || value.isBlank()) {
                return true;
            }
            try {
                URI uri = new URI(value.strip());
                String scheme = uri.getScheme();
                String host = uri.getHost();
                return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                        && host != null && !host.isEmpty()
                        && (host.contains(".") || host.equalsIgnoreCase("localhost") || host.startsWith("["))
                        && !host.startsWith(".") && !host.endsWith("..");
            } catch (URISyntaxException malformed) {
                return false;
            }
        }
    }
}
