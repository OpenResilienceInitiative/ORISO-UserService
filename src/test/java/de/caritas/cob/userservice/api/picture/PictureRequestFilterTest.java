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
}
