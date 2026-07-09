package de.caritas.cob.userservice.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.client.RestTemplate;

/** Contains some general spring boot application configurations */
@Configuration
@ComponentScan(basePackages = {"de.caritas.cob.userservice"})
@PropertySources({@PropertySource("classpath:messages.properties")})
public class AppConfig {

  /**
   * Activate the messages.properties for validation messages
   *
   * @param messageSource
   * @return
   */
  @Bean
  public LocalValidatorFactoryBean validator(MessageSource messageSource) {
    LocalValidatorFactoryBean validatorFactoryBean = new LocalValidatorFactoryBean();
    validatorFactoryBean.setValidationMessageSource(messageSource);
    return validatorFactoryBean;
  }

  // RestTemplate Bean
  @Bean
  @Primary
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
        .connectTimeout(RestTemplateTimeouts.CONNECT_TIMEOUT)
        .readTimeout(RestTemplateTimeouts.READ_TIMEOUT)
        .build();
  }

  @Bean("matrixLongPollRestTemplate")
  public RestTemplate matrixLongPollRestTemplate(RestTemplateBuilder builder) {
    return builder
        .connectTimeout(RestTemplateTimeouts.CONNECT_TIMEOUT)
        .readTimeout(RestTemplateTimeouts.MATRIX_LONG_POLL_READ_TIMEOUT)
        .build();
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
