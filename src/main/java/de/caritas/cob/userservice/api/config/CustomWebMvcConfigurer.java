package de.caritas.cob.userservice.api.config;

import java.util.List;
import lombok.NonNull;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

@Component
public class CustomWebMvcConfigurer implements WebMvcConfigurer {

  @Value("${springfox.docuPath}")
  private String docuPath;

  @Value("${registration.cors.allowed.paths}")
  private List<String> allowedPaths;

  @Value("${registration.cors.allowed.origins}")
  private String[] allowedOrigins;

  private final ObjectProvider<ConsultantActivityInterceptor> consultantActivityInterceptor;

  public CustomWebMvcConfigurer(
      ObjectProvider<ConsultantActivityInterceptor> consultantActivityInterceptor) {
    this.consultantActivityInterceptor = consultantActivityInterceptor;
  }

  @Override
  public void addInterceptors(@NonNull InterceptorRegistry registry) {
    // Absent in web-layer test slices that don't load the service beans; registered in the app.
    var interceptor = consultantActivityInterceptor.getIfAvailable();
    if (interceptor != null) {
      registry.addInterceptor(interceptor);
    }
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler(docuPath + "/**")
        .addResourceLocations("classpath:/META-INF/resources/");
  }

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    var module = new SimpleModule("OpenApiJsonNullable");
    module.addSerializer(JsonNullable.class, new JsonNullableSerializer());

    for (int index = 0; index < converters.size(); index++) {
      if (converters.get(index) instanceof JacksonJsonHttpMessageConverter converter) {
        var mapper = (JsonMapper) converter.getMapper().rebuild().addModule(module).build();
        var replacement = new JacksonJsonHttpMessageConverter(mapper);
        replacement.setSupportedMediaTypes(converter.getSupportedMediaTypes());
        converters.set(index, replacement);
      }
    }
  }

  private static class JsonNullableSerializer extends ValueSerializer<JsonNullable> {

    @Override
    public void serialize(JsonNullable value, JsonGenerator generator, SerializationContext context)
        throws JacksonException {
      context.writeValue(generator, value.orElse(null));
    }
  }

  @Bean
  public FilterRegistrationBean<CorsFilter> corsFilterRegistrationBean() {
    var configuration = new CorsConfiguration();
    configuration.setAllowCredentials(true);
    configuration.setAllowedOrigins(List.of(allowedOrigins));
    configuration.setAllowedMethods(List.of("OPTIONS", "GET", "POST", "PUT", "PATCH", "DELETE"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setExposedHeaders(List.of("X-Reason"));

    var source = new UrlBasedCorsConfigurationSource();
    allowedPaths.forEach(path -> source.registerCorsConfiguration(path, configuration));

    var registrationBean = new FilterRegistrationBean<>(new CorsFilter(source));
    registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registrationBean;
  }
}
