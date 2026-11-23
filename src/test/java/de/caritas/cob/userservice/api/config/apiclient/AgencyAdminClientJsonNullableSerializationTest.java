package de.caritas.cob.userservice.api.config.apiclient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.UpdateLegalTextDTO;
import de.caritas.cob.userservice.api.config.JsonNullableJacksonConfig;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Wire format of a generated request DTO's {@link JsonNullable} field through the shared
 * RestTemplate: undefined is omitted, explicit null is sent as null (Jackson 2 module semantics).
 * Own (non-generated) DTOs keep writing undefined as null.
 */
class AgencyAdminClientJsonNullableSerializationTest {

  private static final String URL = "http://agencyadminservice:8084/legal-text";

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  HttpMessageConvertersAutoConfiguration.class,
                  RestTemplateAutoConfiguration.class))
          .withUserConfiguration(JsonNullableJacksonConfig.class);

  static Stream<Arguments> publishValues() {
    return Stream.of(
        Arguments.of(
            JsonNullable.undefined(), "{\"label\":\"Impressum\",\"content\":{\"de\":\"x\"}}"),
        Arguments.of(
            JsonNullable.of(null),
            "{\"label\":\"Impressum\",\"content\":{\"de\":\"x\"},\"publish\":null}"),
        Arguments.of(
            JsonNullable.of(true),
            "{\"label\":\"Impressum\",\"content\":{\"de\":\"x\"},\"publish\":true}"));
  }

  @ParameterizedTest
  @MethodSource("publishValues")
  void sendsExactJson(JsonNullable<Boolean> publish, String expectedJson) {
    context.run(
        ctx -> {
          RestTemplate restTemplate = ctx.getBean(RestTemplateBuilder.class).build();
          var server = MockRestServiceServer.bindTo(restTemplate).build();
          server
              .expect(requestTo(URL))
              .andExpect(content().string(expectedJson))
              .andRespond(withSuccess());
          var dto = new UpdateLegalTextDTO().label("Impressum").content(Map.of("de", "x"));
          dto.setPublish_JsonNullable(publish);

          restTemplate.postForEntity(URL, dto, Void.class);

          server.verify();
        });
  }

  @Test
  void ownDtoKeepsUndefinedAsNull() {
    context.run(
        ctx -> {
          RestTemplate restTemplate = ctx.getBean(RestTemplateBuilder.class).build();
          var server = MockRestServiceServer.bindTo(restTemplate).build();
          server
              .expect(requestTo(URL))
              .andExpect(content().string("{\"publish\":null}"))
              .andRespond(withSuccess());

          restTemplate.postForEntity(URL, new OwnDto(), Void.class);

          server.verify();
        });
  }

  static class OwnDto {
    public JsonNullable<Boolean> getPublish() {
      return JsonNullable.undefined();
    }
  }
}
