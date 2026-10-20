package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.*;

import de.caritas.cob.userservice.api.adapters.web.dto.IdentityObservationRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.IdentityObservationResponseDTO;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

class IdentityObservationJsonBoundaryConfigurationTest {
  List<HttpMessageConverter<?>> converters() {
    var jackson =
        new JacksonJsonHttpMessageConverter(
            JsonMapper.builder().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES).build());
    var converters = new ArrayList<HttpMessageConverter<?>>();
    converters.add(jackson);
    new IdentityObservationJsonBoundaryConfiguration().extendMessageConverters(converters);
    return converters;
  }

  @Test
  void onlyTheExactRequestDtoAndJsonMediaUseTheBoundary() {
    var converters = converters();
    var boundary = converters.getFirst();
    assertThat(boundary.canRead(IdentityObservationRequestDTO.class, MediaType.APPLICATION_JSON))
        .isTrue();
    assertThat(
            boundary.canRead(
                IdentityObservationRequestDTO.class,
                MediaType.parseMediaType("application/vnd.oriso+json")))
        .isTrue();
    assertThat(boundary.canRead(IdentityObservationRequestDTO.class, MediaType.APPLICATION_XML))
        .isFalse();
    assertThat(boundary.canRead(IdentityObservationResponseDTO.class, MediaType.APPLICATION_JSON))
        .isFalse();
    assertThat(boundary.canRead(String.class, MediaType.APPLICATION_JSON)).isFalse();
    assertThat(boundary.canWrite(IdentityObservationRequestDTO.class, MediaType.APPLICATION_JSON))
        .isFalse();
    assertThat(converters.get(1)).isInstanceOf(JacksonJsonHttpMessageConverter.class);
    assertThat(
            converters
                .get(1)
                .canRead(IdentityObservationResponseDTO.class, MediaType.APPLICATION_JSON))
        .isTrue();
  }

  @Test
  void configuredMapperAndGeneratedPropertiesArePreservedWhileToStringIsSafe() throws Exception {
    var input =
        new MockHttpInputMessage(
            "{\"USERNAME\":\"PrivateMarkerSuccess\"}".getBytes(StandardCharsets.UTF_8));
    var result = (IdentityObservationRequestDTO) read(converters().getFirst(), input);
    assertThat(result.getUsername()).isEqualTo("PrivateMarkerSuccess");
    assertThat(result.toString()).doesNotContain("PrivateMarkerSuccess");
  }

  @Test
  void rejectedJsonCannotReachFrameworkLoggingThroughExceptionOrCause() {
    var input =
        new MockHttpInputMessage(
            "{\"username\":PRIVATE_INVALID_TOKEN}".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> read(converters().getFirst(), input))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessage(IdentityObservationHttpAdapter.INVALID_SELECTOR_MESSAGE)
        .hasNoCause();
  }

  @SuppressWarnings("unchecked")
  private Object read(HttpMessageConverter<?> converter, MockHttpInputMessage input)
      throws Exception {
    return ((HttpMessageConverter<IdentityObservationRequestDTO>) converter)
        .read(IdentityObservationRequestDTO.class, input);
  }

  @Test
  void inheritedGeneratedSelectorConstraintsRemainActive() throws Exception {
    var input = new MockHttpInputMessage("{\"username\":\"\"}".getBytes(StandardCharsets.UTF_8));
    var request = (IdentityObservationRequestDTO) read(converters().getFirst(), input);
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request))
          .extracting(violation -> violation.getPropertyPath().toString())
          .containsExactly("username");
    }
  }

  @Test
  void missingRequiredJacksonConverterFailsConfigurationClosed() {
    for (var converters :
        List.of(
            new ArrayList<HttpMessageConverter<?>>(),
            new ArrayList<HttpMessageConverter<?>>(
                List.of(new org.springframework.http.converter.StringHttpMessageConverter())))) {
      assertThatThrownBy(
              () ->
                  new IdentityObservationJsonBoundaryConfiguration()
                      .extendMessageConverters(converters))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Identity observation request boundary requires a JacksonJsonHttpMessageConverter");
    }
  }
}
