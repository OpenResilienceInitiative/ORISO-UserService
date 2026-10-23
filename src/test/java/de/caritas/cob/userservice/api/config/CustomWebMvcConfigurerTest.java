package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.adapters.web.dto.ConversationType;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

class CustomWebMvcConfigurerTest {

  private org.springframework.web.filter.CorsFilter accountCorsFilter() {
    var configurer = new CustomWebMvcConfigurer(mock(ObjectProvider.class));
    org.springframework.test.util.ReflectionTestUtils.setField(
        configurer, "allowedPaths", java.util.List.of("/service/users/password/change"));
    org.springframework.test.util.ReflectionTestUtils.setField(
        configurer, "allowedOrigins", new String[] {"https://app.fixture.invalid"});
    return configurer.corsFilterRegistrationBean().getFilter();
  }

  @Test
  void anAlreadyAllowedBrowserCanReadTheBoundMutationOutcome() throws Exception {
    var request =
        new org.springframework.mock.web.MockHttpServletRequest(
            "POST", "/service/users/password/change");
    request.addHeader("Origin", "https://app.fixture.invalid");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    var reached = new java.util.concurrent.atomic.AtomicBoolean();
    accountCorsFilter()
        .doFilter(
            request,
            response,
            (incoming, outgoing) -> {
              reached.set(true);
              response.setHeader("X-ORISO-Mutation-Outcome", "NO_EFFECT");
              response.setHeader(
                  "X-ORISO-Mutation-Operation", "11111111-1111-4111-8111-111111111111");
              response.setStatus(409);
            });
    assertThat(reached.get()).isTrue();
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getHeader("Access-Control-Allow-Origin"))
        .isEqualTo("https://app.fixture.invalid");
    assertThat(response.getHeader("Access-Control-Expose-Headers"))
        .contains("X-Reason", "X-ORISO-Mutation-Outcome", "X-ORISO-Mutation-Operation");
  }

  @Test
  void mutationOutcomeExposureDoesNotAdmitAnotherOriginOrPath() throws Exception {
    for (var input :
        java.util.List.of(
            java.util.List.of("/service/users/password/change", "https://foreign.fixture.invalid"),
            java.util.List.of("/service/users/another-operation", "https://app.fixture.invalid"))) {
      var request = new org.springframework.mock.web.MockHttpServletRequest("POST", input.get(0));
      request.addHeader("Origin", input.get(1));
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      var reached = new java.util.concurrent.atomic.AtomicBoolean();
      accountCorsFilter().doFilter(request, response, (incoming, outgoing) -> reached.set(true));
      assertThat(reached.get()).isFalse();
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
      assertThat(response.getHeader("Access-Control-Expose-Headers")).isNull();
    }
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
