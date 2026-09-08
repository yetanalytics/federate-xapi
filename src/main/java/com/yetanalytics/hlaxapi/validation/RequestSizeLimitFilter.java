package com.yetanalytics.hlaxapi.validation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects declared and streamed request bodies that exceed the in-memory API limit. */
final class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxRequestBytes;

    RequestSizeLimitFilter(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxRequestBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Request too large\","
                    + "\"status\":413,\"detail\":\"Request body exceeds the configured limit of "
                    + maxRequestBytes + " bytes\"}");
            return;
        }
        filterChain.doFilter(new LimitedRequest(request, maxRequestBytes), response);
    }

    static final class RequestTooLargeException extends IOException {

        RequestTooLargeException(long maxRequestBytes) {
            super("Request body exceeds the configured limit of " + maxRequestBytes + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;

        private LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long count;

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    if (value >= 0) {
                        record(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int read = delegate.read(bytes, offset, length);
                    if (read > 0) {
                        record(read);
                    }
                    return read;
                }

                private void record(int bytesRead) throws RequestTooLargeException {
                    count += bytesRead;
                    if (count > limit) {
                        throw new RequestTooLargeException(limit);
                    }
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }

                @Override
                public void close() throws IOException {
                    delegate.close();
                }
            };
        }
    }
}
