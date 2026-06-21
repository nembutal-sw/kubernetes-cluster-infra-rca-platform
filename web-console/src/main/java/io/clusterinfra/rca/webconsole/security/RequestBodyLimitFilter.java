package io.clusterinfra.rca.webconsole.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    private static final String EVIDENCE_RESPONSE_PATH = "/api/agents/evidence-responses";

    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;

    public RequestBodyLimitFilter(RcaConsoleProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().matches("POST|PUT|PATCH");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        long limit = limitFor(SecurityFilterSupport.path(request));
        if (request.getContentLengthLong() > limit) {
            writeTooLarge(response, limit);
            return;
        }
        try {
            filterChain.doFilter(new LimitedRequest(request, limit), response);
        } catch (PayloadTooLargeException exception) {
            if (!response.isCommitted()) {
                response.reset();
                writeTooLarge(response, limit);
            }
        }
    }

    private long limitFor(String path) {
        return EVIDENCE_RESPONSE_PATH.equals(path)
            ? properties.getSecurity().getEvidenceRequestMaxBytes()
            : properties.getSecurity().getStandardRequestMaxBytes();
    }

    private void writeTooLarge(HttpServletResponse response, long limit) throws IOException {
        SecurityFilterSupport.writeError(
            objectMapper,
            response,
            HttpStatus.PAYLOAD_TOO_LARGE.value(),
            "request body exceeds " + limit + " bytes"
        );
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;

        private LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
            jakarta.servlet.ServletInputStream delegate = super.getInputStream();
            InputStream limited = new FilterInputStream(delegate) {
                private long count;

                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0 && ++count > limit) {
                        throw new PayloadTooLargeException();
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int read = super.read(bytes, offset, length);
                    if (read > 0 && (count += read) > limit) {
                        throw new PayloadTooLargeException();
                    }
                    return read;
                }
            };
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    delegate.setReadListener(listener);
                }

                @Override
                public int read() throws IOException {
                    return limited.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    return limited.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null
                ? StandardCharsets.UTF_8
                : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class PayloadTooLargeException extends IOException {
    }
}
