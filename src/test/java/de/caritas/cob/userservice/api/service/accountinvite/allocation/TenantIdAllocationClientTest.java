package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TenantIdAllocationClientTest {

  private static final String BASE_URL = "https://tenant.example.org";
  private static final String RELEASE_URL =
      BASE_URL + "/tenantadmin/tenant-ids/reservations/21/release";
  private static final String PROOF_URL = BASE_URL + "/tenantadmin/tenant-ids/reservations/21";
  private static final String PROOF = "synthetic-reservation-proof";

  private final RestTemplate rest = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
  private final SecurityHeaderSupplier headers = mock(SecurityHeaderSupplier.class);
  private final TenantAdminServiceApiControllerFactory factory =
      new TenantAdminServiceApiControllerFactory();
  private final TenantIdAllocationClient client = new TenantIdAllocationClient(headers, factory);

  @BeforeEach
  void setUp() {
    var authorization = new HttpHeaders();
    authorization.setBearerAuth("synthetic-service-token");
    when(headers.getKeycloakAndCsrfHttpHeaders()).thenReturn(authorization);
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", BASE_URL);
    ReflectionTestUtils.setField(factory, "restTemplate", rest);
  }

  @Test
  void release_Should_sendTheProofInAHeaderAndConfirmOnlyNoContent() {
    server
        .expect(requestTo(RELEASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer synthetic-service-token"))
        .andExpect(header("X-Reservation-Token", PROOF))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    assertThat(client.release(21L, PROOF)).isTrue();
    server.verify();
  }

  @Test
  void release_WhenTheProviderLacksTheNewOperation_Should_leaveTheTaskPendingAndRedactTheProof() {
    server
        .expect(requestTo(RELEASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Reservation-Token", PROOF))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body(PROOF));

    try (var logs = LogbackCaptor.forClass(TenantIdAllocationClient.class)) {
      assertThat(client.release(21L, PROOF)).isFalse();
      assertThat(logs.events())
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getFormattedMessage())
                    .contains("remains pending")
                    .doesNotContain(PROOF);
                assertThat(event.getThrowableProxy()).isNull();
              });
    }
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {204, 404, 405})
  void reservationProofRequiresConfirmationFromTheProvider(int status) {
    server
        .expect(requestTo(PROOF_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Reservation-Token", PROOF))
        .andRespond(withStatus(HttpStatus.valueOf(status)));

    assertThat(client.hasReservationProof(21L, PROOF)).isEqualTo(status == 204);
    server.verify();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void release_WithoutAProof_Should_notReachTheProvider(String proof) {
    assertThat(client.release(21L, proof)).isFalse();
    server.verify();
  }
}
