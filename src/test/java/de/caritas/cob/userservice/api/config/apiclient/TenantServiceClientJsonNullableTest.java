package de.caritas.cob.userservice.api.config.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.JsonNullableJacksonConfig;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Dev login outage 2026-10-21: TenantService began sending {@code "active": true}; the Jackson 3
 * mapper behind the shared RestTemplate could not build {@code JsonNullable}, so every request
 * through HttpTenantFilter ended in 403. Uses the real Boot converter wiring.
 */
class TenantServiceClientJsonNullableTest {

  private static final String BASE_URL = "http://tenantservice:8081";

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  HttpMessageConvertersAutoConfiguration.class,
                  RestTemplateAutoConfiguration.class))
          .withUserConfiguration(JsonNullableJacksonConfig.class);

  @ParameterizedTest
  @CsvSource(
      value = {
        "'\"active\": true,' | true",
        "'\"active\": false,' | false",
        "'\"active\": null,' | null",
        "'' | null"
      },
      delimiter = '|',
      nullValues = "null")
  void deserializesRealShapedRestrictedTenant(String activeField, Boolean expected) {
    context.run(
        ctx -> {
          RestTemplate restTemplate = ctx.getBean(RestTemplateBuilder.class).build();
          var server = MockRestServiceServer.bindTo(restTemplate).build();
          server
              .expect(requestTo(BASE_URL + "/tenant/public/id/1"))
              .andRespond(
                  withSuccess(
                      "{"
                          + activeField
                          + "\"id\": 1, \"name\": \"Träger\", \"subdomain\": \"dev\","
                          + " \"theming\": {}, \"content\": {}, \"settings\": {}}",
                      MediaType.APPLICATION_JSON));
          var factory = new TenantServiceApiControllerFactory();
          ReflectionTestUtils.setField(factory, "restTemplate", restTemplate);
          ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", BASE_URL);

          RestrictedTenantDTO tenant =
              factory.createControllerApi().getRestrictedTenantDataByTenantId(1L);

          assertThat(tenant.getId()).isEqualTo(1L);
          assertThat(tenant.getActive()).isEqualTo(expected);
          server.verify();
        });
  }
}
