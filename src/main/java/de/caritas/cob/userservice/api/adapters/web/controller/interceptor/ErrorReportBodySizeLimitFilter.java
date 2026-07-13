package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Abuse guard for the unauthenticated {@code /error-reports} intake endpoint (OBS-P3): a
 * client-side crash can POST here before login, so the endpoint itself cannot require auth. To stop
 * it becoming an open resource-exhaustion / log-injection vector, this filter rejects oversized
 * request bodies for that path only - both up front via {@code Content-Length} and, for chunked
 * requests without one, by capping the number of bytes the servlet container is allowed to read off
 * the wire.
 *
 * <p>Every other request passes through untouched.
 */
@Component
public class ErrorReportBodySizeLimitFilter extends OncePerRequestFilter {

  /** Generous enough for a stack trace plus metadata, small enough to bound memory/log growth. */
  static final long MAX_BODY_BYTES = 8 * 1024L;

  private static final String[] PROTECTED_PATHS = {"/error-reports", "/service/error-reports"};

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!isProtectedPath(request.getRequestURI())) {
      filterChain.doFilter(request, response);
      return;
    }

    long declaredLength = request.getContentLengthLong();
    if (declaredLength > MAX_BODY_BYTES) {
      response.sendError(413, "Request body too large");
      return;
    }

    filterChain.doFilter(new SizeLimitingRequestWrapper(request, MAX_BODY_BYTES), response);
  }

  private boolean isProtectedPath(String requestUri) {
    if (requestUri == null) {
      return false;
    }
    for (String path : PROTECTED_PATHS) {
      if (requestUri.equals(path) || requestUri.endsWith(path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Wraps the raw request stream so reading past {@code maxBytes} fails fast instead of buffering.
   */
  private static final class SizeLimitingRequestWrapper extends HttpServletRequestWrapper {

    private final long maxBytes;

    private SizeLimitingRequestWrapper(HttpServletRequest request, long maxBytes) {
      super(request);
      this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      ServletInputStream delegate = super.getInputStream();
      return new LimitingServletInputStream(delegate, maxBytes);
    }
  }

  private static final class LimitingServletInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long maxBytes;
    private long bytesRead;

    private LimitingServletInputStream(ServletInputStream delegate, long maxBytes) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      checkLimit(1);
      int b = delegate.read();
      if (b != -1) {
        bytesRead++;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      checkLimit(len);
      int read = delegate.read(b, off, len);
      if (read > 0) {
        bytesRead += read;
      }
      return read;
    }

    private void checkLimit(int upcomingRead) throws IOException {
      if (bytesRead + upcomingRead > maxBytes) {
        throw new IOException(
            "Request body exceeds maximum allowed size of " + maxBytes + " bytes");
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
    public void setReadListener(ReadListener readListener) {
      delegate.setReadListener(readListener);
    }
  }
}
