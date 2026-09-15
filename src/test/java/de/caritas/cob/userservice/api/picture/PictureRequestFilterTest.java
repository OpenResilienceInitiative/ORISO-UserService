package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;

class PictureRequestFilterTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void multipartNeverReachesDispatcherOrReadsBody(String prefix) throws Exception {
    var request =
        spy(new MockHttpServletRequest("PUT", prefix + "/useradmin/consultants/synthetic/picture"));
    request.setContentType("multipart/form-data; boundary=synthetic");
    var response = new MockHttpServletResponse();
    var chain = mock(jakarta.servlet.FilterChain.class);
    new PictureRequestFilter().doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(415);
    verifyNoInteractions(chain);
    verify(request, never()).getInputStream();
    verify(request, never()).getParts();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void nonPictureMultipartReachesChainUntouched(String prefix) throws Exception {
    assertPassesThrough(
        "PUT",
        prefix + "/useradmin/consultants/synthetic",
        "multipart/form-data; boundary=synthetic");
    assertPassesThrough(
        "PUT", prefix + "/users/consultants", "multipart/form-data; boundary=synthetic");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void pngPicturePutReachesChainUntouched(String prefix) throws Exception {
    assertPassesThrough("PUT", prefix + "/useradmin/consultants/synthetic/picture", "image/png");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void pictureGetWithoutTypeReachesChainUntouched(String prefix) throws Exception {
    assertPassesThrough("GET", prefix + "/useradmin/consultants/synthetic/picture", null);
  }

  private void assertPassesThrough(String method, String uri, String contentType) throws Exception {
    var request = spy(new MockHttpServletRequest(method, uri));
    request.setContentType(contentType);
    var response = new MockHttpServletResponse();
    var chain = mock(jakarta.servlet.FilterChain.class);
    new PictureRequestFilter().doFilter(request, response, chain);
    verify(chain).doFilter(same(request), same(response));
    verifyNoMoreInteractions(chain);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertThat(response.getHeaderNames()).isEmpty();
    verify(request, never()).getInputStream();
    verify(request, never()).getParts();
  }
}
