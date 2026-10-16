package com.scheideckerantunes.notes.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Common sanitized RFC 9457 errors; no exception details, values or query strings.
 *
 * @author Carlos Scheidecker
 */
public final class ApiProblems {
    private ApiProblems() {
    }

    public static ProblemDetail create(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.HEADER));
        return problem;
    }
}
