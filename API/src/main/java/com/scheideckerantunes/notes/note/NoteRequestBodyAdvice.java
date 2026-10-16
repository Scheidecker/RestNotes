package com.scheideckerantunes.notes.note;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Bounds JSON reads even for chunked requests or a missing/untrusted Content-Length.
 *
 * @author Carlos Scheidecker
 */
@RestControllerAdvice(assignableTypes = NoteController.class)
public class NoteRequestBodyAdvice extends RequestBodyAdviceAdapter {
    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == CreateNoteRequest.class || targetType == ReplaceNoteRequest.class
                || targetType == PatchNoteRequest.class || targetType == AddTagRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (input.getHeaders().getContentLength() > NoteLimits.BODY_BYTES) {
            throw new NoteOperationException(NoteOperationException.Code.BODY_TOO_LARGE);
        }
        InputStream bounded = new BoundedBody(input.getBody());
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return bounded;
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }

    /**
     * Static error type is recognized without returning parser exception messages.
     *
     * @author Carlos Scheidecker
     */
    public static final class BodyLimitException extends IOException {
        BodyLimitException() {
            super("Note request body limit exceeded");
        }
    }

    /**
     * Counts all consumed bytes independently of transport framing.
     *
     * @author Carlos Scheidecker
     */
    private static final class BoundedBody extends FilterInputStream {
        private long consumed;

        BoundedBody(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int result = in.read();
            count(result < 0 ? 0 : 1);
            return result;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int result = in.read(bytes, offset, length);
            count(Math.max(result, 0));
            return result;
        }

        @Override
        public long skip(long bytes) throws IOException {
            long result = in.skip(bytes);
            count(result);
            return result;
        }

        private void count(long bytes) throws BodyLimitException {
            consumed += bytes;
            if (consumed > NoteLimits.BODY_BYTES) {
                throw new BodyLimitException();
            }
        }
    }
}
