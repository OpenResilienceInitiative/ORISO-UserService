package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

/**
 * ORISO-Admin#998: the counsellor wizard creates the Beratungsstelle its invite reserved. Every
 * AgencyService rejection used to leave this client unmapped, so the public onboarding endpoint
 * answered a bare 500 and the reason lived only in AgencyService's log.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgencyCreationClientTest {

  private static final Long RESERVED_AGENCY_ID = 16L;
  private static final Long TENANT_ID = 25L;

  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private IdentityAuthentication identityAuthentication;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private AgencyAdminServiceApiControllerFactory controllerFactory;
  @Mock private AdminAgencyControllerApi adminAgencyControllerApi;
  @Mock private ApiClient apiClient;

  private AgencyCreationClient client;

  @BeforeEach
  void setUp() {
    var technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityAuthentication.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("access-token", 60, 60, "refresh-token"));
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(anyString()))
        .thenReturn(new HttpHeaders());
    when(controllerFactory.createControllerApi()).thenReturn(adminAgencyControllerApi);
    when(adminAgencyControllerApi.getApiClient()).thenReturn(apiClient);

    client =
        new AgencyCreationClient(
            securityHeaderSupplier, identityAuthentication, identityClientConfig, controllerFactory);
    ReflectionTestUtils.setField(client, "defaultConsultingType", 1);
  }

  private void answerWith(HttpStatus status) {
    when(adminAgencyControllerApi.createAgency(any(AgencyDTO.class)))
        .thenThrow(HttpClientErrorException.create(status, status.name(), null, null, null));
  }

  private void createAgency() {
    client.createAgencyWithReservedId(RESERVED_AGENCY_ID, "Beratungsstelle", TENANT_ID, List.of(3L));
  }

  /**
   * The regression: AgencyService validates the consulting type before it creates anything and
   * answers 400 when it does not exist. That answer escaped unmapped and reached the invitee as an
   * unexplained 500 with nothing logged on this side.
   */
  @Test
  void createAgencyWithReservedId_upstreamBadRequest_isTranslatedInsteadOfEscapingRaw() {
    answerWith(HttpStatus.BAD_REQUEST);

    assertThatThrownBy(this::createAgency)
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("400");
  }

  /** The documented single-use outcome keeps its own status. */
  @Test
  void createAgencyWithReservedId_upstreamConflict_staysAConflict() {
    answerWith(HttpStatus.CONFLICT);

    assertThatThrownBy(this::createAgency).isInstanceOf(ConflictException.class);
  }

  /** A server-side outage upstream is reported as such, not as a raw client exception. */
  @Test
  void createAgencyWithReservedId_upstreamServerError_isTranslated() {
    when(adminAgencyControllerApi.createAgency(any(AgencyDTO.class)))
        .thenThrow(
            org.springframework.web.client.HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "unavailable", null, null, null));

    assertThatThrownBy(this::createAgency)
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("503");
  }

  /**
   * The consulting type is the field that broke this flow, so pin what the request carries: the
   * configured value, not the hard-coded 0 the client used to default to.
   */
  @Test
  void createAgencyWithReservedId_sendsTheConfiguredConsultingType() {
    answerWith(HttpStatus.BAD_REQUEST);

    assertThatThrownBy(this::createAgency).isInstanceOf(InternalServerErrorException.class);

    ArgumentCaptor<AgencyDTO> sent = ArgumentCaptor.forClass(AgencyDTO.class);
    verify(adminAgencyControllerApi).createAgency(sent.capture());
    assertThat(sent.getValue().getConsultingType()).isEqualTo(1);
    assertThat(sent.getValue().getReservedAgencyId()).isEqualTo(RESERVED_AGENCY_ID);
    assertThat(sent.getValue().getTenantId()).isEqualTo(TENANT_ID);
  }
}
