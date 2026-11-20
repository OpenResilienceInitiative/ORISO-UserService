package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.IdentityObservationRequestDTO;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The generated request keeps normal JSON/validation semantics without framework body-log leaks.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityObservationJsonBoundaryConfiguration implements WebMvcConfigurer {
  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    for (int index = 0; index < converters.size(); index++) {
      if (converters.get(index) instanceof JacksonJsonHttpMessageConverter jackson) {
        converters.add(
            index, new SafeRequestConverter(jackson.getMapper(), jackson.getSupportedMediaTypes()));
        return;
      }
    }
    throw new IllegalStateException(
        "Identity observation request boundary requires a JacksonJsonHttpMessageConverter");
  }

  private static final class SafeRequestConverter
      extends AbstractHttpMessageConverter<IdentityObservationRequestDTO> {
    private final ObjectMapper mapper;

    SafeRequestConverter(ObjectMapper mapper, List<MediaType> mediaTypes) {
      super(mediaTypes.toArray(MediaType[]::new));
      this.mapper = mapper;
    }

    @Override
    protected boolean supports(Class<?> type) {
      return type == IdentityObservationRequestDTO.class;
    }

    @Override
    public boolean canWrite(Class<?> type, MediaType mediaType) {
      return false;
    }

    @Override
    protected IdentityObservationRequestDTO readInternal(
        Class<? extends IdentityObservationRequestDTO> type, HttpInputMessage input)
        throws IOException {
      try {
        return mapper.readValue(input.getBody(), SafeRequest.class);
      } catch (JacksonException | IOException invalidJson) {
        // A cause/message can contain rejected tokens; framework DEBUG logging runs before advice.
        throw new HttpMessageNotReadableException(
            IdentityObservationHttpAdapter.INVALID_SELECTOR_MESSAGE, input);
      }
    }

    @Override
    protected void writeInternal(IdentityObservationRequestDTO value, HttpOutputMessage output) {
      throw new UnsupportedOperationException("Request-only converter");
    }
  }

  /** Only toString changes; inherited generated properties and bean constraints remain intact. */
  public static final class SafeRequest extends IdentityObservationRequestDTO {
    @Override
    public String toString() {
      return "IdentityObservationRequestDTO[redacted]";
    }
  }
}
