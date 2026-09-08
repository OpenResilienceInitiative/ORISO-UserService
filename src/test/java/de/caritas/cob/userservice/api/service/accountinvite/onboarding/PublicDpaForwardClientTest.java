package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.tenantservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaSignInviteDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.PublicDpaForwardRequestDTO;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Status translation of the public TenantService forward call. Every answer the TenantService
 * phrases deliberately has to survive to the Admin panel with its own status — an opaque 500 makes
 * a recoverable situation look like an outage.
 */
@ExtendWith(MockitoExtension.class)
class PublicDpaForwardClientTest {

  private static final Long RESERVED_TENANT_ID = 84L;
  private static final String RESERVATION_TOKEN = "reservation-token";

  @Mock private TenantServiceApiControllerFactory controllerFactory;
  @Mock private TenantControllerApi tenantControllerApi;

  private PublicDpaForwardClient client;

  @BeforeEach
  void setUp() {
    when(controllerFactory.createControllerApi()).thenReturn(tenantControllerApi);
    client = new PublicDpaForwardClient(controllerFactory);
  }

  private void answerWith(HttpStatus status) {
    when(tenantControllerApi.createPublicDpaForwardInvite(any(PublicDpaForwardRequestDTO.class)))
        .thenThrow(HttpClientErrorException.create(status, status.name(), null, null, null));
  }

  /**
   * The regression: the TenantService caps outstanding sign links per onboarding and says so with
   * 429. That answer used to escape unmapped and reach the wizard as an opaque technical error.
   */
  @Test
  void createForwardSignLink_throttled_surfacesTooManyRequests() {
    answerWith(HttpStatus.TOO_MANY_REQUESTS);

    assertThatThrownBy(() -> client.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void createForwardSignLink_noPublishedDpa_surfacesConflict() {
    answerWith(HttpStatus.CONFLICT);

    assertThatThrownBy(() -> client.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void createForwardSignLink_reservationNoLongerAccepted_surfacesLinkDeath() {
    answerWith(HttpStatus.GONE);

    assertThatThrownBy(() -> client.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .isInstanceOf(AccountInviteLinkException.class)
        .extracting(exception -> ((AccountInviteLinkException) exception).getReason())
        .isEqualTo(AccountInviteLinkException.Reason.CONSUMED);
  }

  /** A genuine provider fault must stay a fault — it is not a phrasable end-user state. */
  @Test
  void createForwardSignLink_serverFault_isNotTranslated() {
    when(tenantControllerApi.createPublicDpaForwardInvite(any(PublicDpaForwardRequestDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "BAD_REQUEST", null, null, null));

    assertThatThrownBy(() -> client.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .isInstanceOf(HttpClientErrorException.class);
  }

  @Test
  void createForwardSignLink_success_returnsMintedInvite() {
    var invite =
        new DpaSignInviteDTO().signLink("/dpa-sign/token").expiresAt("2026-09-02T10:00:00");
    when(tenantControllerApi.createPublicDpaForwardInvite(any(PublicDpaForwardRequestDTO.class)))
        .thenReturn(invite);

    Assertions.assertThat(client.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .isSameAs(invite);
  }
}
