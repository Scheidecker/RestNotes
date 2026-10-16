package com.scheideckerantunes.notes.common;

import com.scheideckerantunes.notes.auth.DuplicateAccountException;
import com.scheideckerantunes.notes.auth.RegistrationDisabledException;
import com.scheideckerantunes.notes.team.TeamConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Never copies submitted passwords, rejected values or internal exception messages.
 *
 * @author Carlos Scheidecker
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(DuplicateAccountException.class)
    ProblemDetail duplicate(HttpServletRequest request) {
        return ApiProblems.create(HttpStatus.CONFLICT, "An account with this email already exists", request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(HttpServletRequest request) {
        return ApiProblems.create(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(TeamConflictException.class)
    ProblemDetail teamConflict(TeamConflictException exception, HttpServletRequest request) {
        String detail = switch (exception.getCode()) {
            case MEMBER_ALREADY_EXISTS -> "User is already a member of this team";
            case LAST_TEAM_ADMIN -> "A team must retain at least one ADMIN";
        };
        ProblemDetail problem = ApiProblems.create(HttpStatus.CONFLICT, detail, request);
        problem.setProperty("code", exception.getCode().name());
        return problem;
    }

    @ExceptionHandler(RegistrationDisabledException.class)
    ProblemDetail registrationDisabled(HttpServletRequest request) {
        return ApiProblems.create(HttpStatus.FORBIDDEN, "User registration is disabled", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> authentication(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", "Bearer")
                .header("Cache-Control", "no-store")
                .body(ApiProblems.create(HttpStatus.UNAUTHORIZED, "Invalid credentials", request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(HttpStatus.BAD_REQUEST, "Request validation failed", request);
        List<FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, ConstraintViolationException.class})
    ProblemDetail malformed(HttpServletRequest request) {
        return ApiProblems.create(HttpStatus.BAD_REQUEST, "Invalid request body or parameters", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(HttpServletRequest request) {
        return ApiProblems.create(HttpStatus.FORBIDDEN, "Access is denied", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse error) {
            HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                return ApiProblems.create(status, status.getReasonPhrase(), request);
            }
        }
        LOG.error("Unhandled {} [correlationId={}]", exception.getClass().getSimpleName(),
                request.getAttribute(CorrelationIdFilter.HEADER));
        return ApiProblems.create(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    /**
     * Only field names and static validation messages are exposed.
     *
     * @author Carlos Scheidecker
     */
    record FieldError(String field, String message) {
    }
}
