package de.caritas.cob.userservice.api.testHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;

/**
 * Explicit web-slice import of the production Jackson2 mapper configuration. Not component scanned.
 */
public class CreationJsonMapperFixture {
  @Bean
  public ObjectMapper creationJsonObjectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
