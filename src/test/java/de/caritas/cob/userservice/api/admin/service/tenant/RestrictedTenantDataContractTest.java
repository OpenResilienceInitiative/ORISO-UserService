package de.caritas.cob.userservice.api.admin.service.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * Contract guard for the tenant payload consumed by {@link
 * de.caritas.cob.userservice.api.adapters.web.controller.interceptor.HttpTenantFilter}.
 *
 * <p>TenantService serializes {@code termsAndConditionsConfirmation} and {@code
 * dataPrivacyConfirmation} from {@code LocalDateTime} columns, so the wire value has no UTC offset:
 * {@code "2026-07-27T14:36:57"}. When {@code services/tenantservice.yaml} declares those fields as
 * {@code format: date-time}, the generated client types them as {@code OffsetDateTime} and Jackson
 * refuses the value. That failure happens inside the tenant filter, which runs for every
 * non-whitelisted request, so it turns into a blanket HTTP 403 for the whole tenant.
 *
 * <p>See UserService#872 — Pre-Dev was down for tenant 1 from 2026-07-27T23:13Z until this guard
 * existed.
 */
class RestrictedTenantDataContractTest {

  /** Verbatim shape returned by GET /tenant/public/id/1 on Pre-Dev. */
  private static final String PRE_DEV_PAYLOAD =
      """
      {
        "id": 1,
        "name": "Caritas Berlin",
        "subdomain": "caritas-berlin",
        "content": {
          "claim": "Beratung & Hilfe",
          "impressum": "Llorem ipsum...",
          "privacy": "Llorem ipsum...",
          "termsAndConditions": "Llorem ipsum...",
          "dataPrivacyConfirmation": "2026-07-27T14:36:57",
          "termsAndConditionsConfirmation": "2026-07-27T14:36:57"
        }
      }
      """;

  private final MappingJackson2HttpMessageConverter converter =
      new MappingJackson2HttpMessageConverter();

  @Test
  @DisplayName("a local-date-time privacy stamp does not break tenant deserialization")
  void deserializesOffsetLessConfirmationTimestamps() throws Exception {
    assertThatCode(this::readPayload).doesNotThrowAnyException();

    var tenant = readPayload();

    assertThat(tenant.getSubdomain()).isEqualTo("caritas-berlin");
    assertThat(tenant.getContent()).isNotNull();
  }

  @Test
  @DisplayName("the confirmation stamps stay plain strings, never OffsetDateTime")
  void keepsConfirmationStampsUntyped() throws Exception {
    var content = readPayload().getContent();

    // A String-typed getter is the whole point: the moment `format: date-time`
    // comes back, this stops compiling and the guard has done its job.
    String dataPrivacyConfirmation = content.getDataPrivacyConfirmation();
    String termsAndConditionsConfirmation = content.getTermsAndConditionsConfirmation();

    assertThat(dataPrivacyConfirmation).isEqualTo("2026-07-27T14:36:57");
    assertThat(termsAndConditionsConfirmation).isEqualTo("2026-07-27T14:36:57");
  }

  private RestrictedTenantDTO readPayload() throws Exception {
    return (RestrictedTenantDTO)
        converter.read(RestrictedTenantDTO.class, jsonMessage(PRE_DEV_PAYLOAD));
  }

  private static HttpInputMessage jsonMessage(String body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return stream;
      }

      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    };
  }
}
