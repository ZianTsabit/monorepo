package com.ziantsabit.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Present and, for text, not blank -- with one message for each case, so a
 * missing field says "required" and a blank one says {@link #blank()}, never
 * both (which {@code @NotNull} plus {@code @NotBlank} would).
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Required.Validator.class)
public @interface Required {

    String message() default Messages.REQUIRED;

    /** What a blank value is told. */
    String blank() default Messages.BLANK;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<Required, Object> {

        private String blank;

        @Override
        public void initialize(Required rule) {
            this.blank = rule.blank();
        }

        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            if (value == null) {
                return false;
            }
            if (value instanceof CharSequence text && text.toString().isBlank()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(blank).addConstraintViolation();
                return false;
            }
            return true;
        }
    }
}
