package com.scheideckerantunes.notes.note;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces the MySQL byte limit rather than counting Java characters.
 *
 * @author Carlos Scheidecker
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoteContentSize.Validator.class)
public @interface NoteContentSize {
    String message() default "must not exceed 262144 UTF-8 bytes";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /**
     * Null validation belongs to the request's required/partial field semantics.
     *
     * @author Carlos Scheidecker
     */
    class Validator implements ConstraintValidator<NoteContentSize, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return NoteLimits.contentFits(value);
        }
    }
}
