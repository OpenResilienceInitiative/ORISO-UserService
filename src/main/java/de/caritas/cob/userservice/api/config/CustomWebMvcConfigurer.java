package de.caritas.cob.userservice.api.config;

import java.util.List;
import lombok.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
  public void addCorsMappings(@NonNull CorsRegistry registry) {
    allowedPaths.forEach(path -> addCorsMapping(registry, path));
  }

  private void addCorsMapping(CorsRegistry registry, String path) {
    registry
        .addMapping(path)
        .allowCredentials(true)
        .allowedMethods("OPTIONS", "GET", "POST", "PUT", "PATCH", "DELETE")
        .allowedOrigins(allowedOrigins)
        .allowedHeaders("*")
        .exposedHeaders("X-Reason");
  }
}
