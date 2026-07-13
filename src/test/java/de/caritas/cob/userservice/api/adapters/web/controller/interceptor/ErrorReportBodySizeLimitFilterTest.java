package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Covers the abuse guard for the unauthenticated /error-reports intake (OBS-P3). */
@ExtendWith(MockitoExtension.class)
class ErrorReportBodySizeLimitFilterTest {

  private final ErrorReportBodySizeLimitFilter filter = new ErrorReportBodySizeLimitFilter();

  @Mock private FilterChain filterChain;

  @Test
  void doFilterInternal_Should_reject413_When_declaredContentLengthExceedsLimit() throws Exception {
    var request = new MockHttpServletRequest("POST", "/error-reports");
    var oversized = "x".repeat((int) ErrorReportBodySizeLimitFilter.MAX_BODY_BYTES + 1);
    request.setContent(oversized.getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(413);
    verifyNoInteractions(filterChain);
  }

  @Test
  void doFilterInternal_Should_passThrough_When_bodyWithinLimit() throws Exception {
    var request = new MockHttpServletRequest("POST", "/error-reports");
    request.setContent("{\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain)
        .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void doFilterInternal_Should_passThrough_When_pathIsUnrelated() throws Exception {
    var request = new MockHttpServletRequest("POST", "/users/askers/new");
    var oversized = "x".repeat((int) ErrorReportBodySizeLimitFilter.MAX_BODY_BYTES + 1);
    request.setContent(oversized.getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
  }

  @Test
  void getInputStream_Should_throwIOException_When_streamedBytesExceedLimit() throws Exception {
    // Belt-and-braces: even when the declared Content-Length is within bounds, the wrapper must
    // cap the actual bytes read off the wire (guards chunked requests, which have no upfront
    // Content-Length at all).
    var request = new MockHttpServletRequest("POST", "/error-reports");
    var withinDeclaredLimit = "x".repeat((int) ErrorReportBodySizeLimitFilter.MAX_BODY_BYTES);
    request.setContent(withinDeclaredLimit.getBytes(StandardCharsets.UTF_8));

    var capturingChain =
        (FilterChain)
            (req, res) -> {
              var in = ((jakarta.servlet.http.HttpServletRequest) req).getInputStream();
              // Reading exactly the limit must succeed...
              assertThat(in.readNBytes((int) ErrorReportBodySizeLimitFilter.MAX_BODY_BYTES))
                  .hasSize((int) ErrorReportBodySizeLimitFilter.MAX_BODY_BYTES);
            };

    filter.doFilter(request, new MockHttpServletResponse(), capturingChain);
  }
}
