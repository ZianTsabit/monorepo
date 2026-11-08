package com.ziantsabit.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * The value is one of a fixed set, or null (absent). With {@code allowBlank},
 * the empty string is also accepted, meaning "none chosen".
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OneOf.Validator.class)
public @interface OneOf {

    String[] value();

    boolean allowBlank() default false;

    String message() default Messages.CHOICE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<OneOf, String> {

        private OneOf rule;

        @Override
        public void initialize(OneOf rule) {
            this.rule = rule;
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || (rule.allowBlank() && value.isEmpty())
                    || Arrays.asList(rule.value()).contains(value)) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    rule.message().replace("{choices}", String.join(", ", rule.value())))
                    .addConstraintViolation();
            return false;
        }
    }
}
