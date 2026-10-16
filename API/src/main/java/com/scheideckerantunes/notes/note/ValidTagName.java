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
 * Static validation messages never echo submitted labels.
 *
 * @author Carlos Scheidecker
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidTagName.Validator.class)
public @interface ValidTagName {
    String message() default "must be a routable tag name of at most 50 characters";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /**
     * Requiredness is separately enforced with NotBlank.
     *
     * @author Carlos Scheidecker
     */
    class Validator implements ConstraintValidator<ValidTagName, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || TagNames.valid(value);
        }
    }
}
