package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class ConsultantCreationWebConfiguration implements WebMvcConfigurer {
  private final ObjectMapper mapper;

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.addFirst(new ConsultantCreationJsonConverter(mapper));
  }
}
