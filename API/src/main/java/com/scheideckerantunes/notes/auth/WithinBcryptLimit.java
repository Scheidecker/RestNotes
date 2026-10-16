package com.scheideckerantunes.notes.auth;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * BCrypt has a byte limit, not a Java character-count limit.
 *
 * @author Carlos Scheidecker
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = WithinBcryptLimit.Validator.class)
public @interface WithinBcryptLimit {
    String message() default "must contain at most 72 UTF-8 bytes";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /**
     * Rejects oversized passwords instead of allowing truncation or an encoder exception.
     *
     * @author Carlos Scheidecker
     */
    class Validator implements ConstraintValidator<WithinBcryptLimit, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }
}
