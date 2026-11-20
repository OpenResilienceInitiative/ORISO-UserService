package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.adapters.web.dto.ConversationType;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class CustomWebMvcConfigurerTest {

  @Test
  void corsFilterExposesMatrixRetryAfterToAnAllowedOrigin() throws Exception {
    var path = "/service/matrix/me/token";
    var origin = "https://frontend.example.test";
    var configurer = new CustomWebMvcConfigurer(mock(ObjectProvider.class));
    ReflectionTestUtils.setField(configurer, "allowedPaths", List.of(path));
    ReflectionTestUtils.setField(configurer, "allowedOrigins", new String[] {origin});
    var request = new MockHttpServletRequest("GET", path);
    request.addHeader(HttpHeaders.ORIGIN, origin);
    var response = new MockHttpServletResponse();

    configurer
        .corsFilterRegistrationBean()
        .getFilter()
        .doFilter(
            request,
            response,
            (ignoredRequest, ignoredResponse) -> {
              response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
              response.setHeader(HttpHeaders.RETRY_AFTER, "17");
            });

    assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(origin);
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS).split(",\\s*"))
        .contains("X-Reason", HttpHeaders.RETRY_AFTER);
  }

  @Test
  void extendMessageConvertersShouldSerializeJsonNullableAsApiValueWithJackson3() throws Exception {
    var converters = new ArrayList<HttpMessageConverter<?>>();
    converters.add(new JacksonJsonHttpMessageConverter());
    var configurer = new CustomWebMvcConfigurer(mock(ObjectProvider.class));

    configurer.extendMessageConverters(converters);

    var converter = (JacksonJsonHttpMessageConverter) converters.getFirst();
    var json =
        converter
            .getMapper()
            .writeValueAsString(new SessionDTO().conversationType(ConversationType.LIVE_CHAT));
    assertThat(json)
        .contains("\"conversationType\":\"LIVE_CHAT\"")
        .doesNotContain("\"conversationType\":{\"present\":true}");
  }
}
