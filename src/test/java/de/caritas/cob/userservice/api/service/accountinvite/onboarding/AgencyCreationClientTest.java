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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

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
            securityHeaderSupplier,
            identityAuthentication,
            identityClientConfig,
            controllerFactory);
    ReflectionTestUtils.setField(client, "defaultConsultingType", 1);
  }

  private void answerWith(HttpStatus status) {
    when(adminAgencyControllerApi.createAgency(any(AgencyDTO.class)))
        .thenThrow(HttpClientErrorException.create(status, status.name(), null, null, null));
  }

  private void createAgency() {
    client.createAgencyWithReservedId(
        RESERVED_AGENCY_ID, "Beratungsstelle", TENANT_ID, List.of(3L));
  }

  /**
   * The regression: AgencyService validates the consulting type before it creates anything and
   * answers 400 when it does not exist. That answer escaped unmapped and reached the invitee as an
   * unexplained 500 with nothing logged on this side. It stays a 500 deliberately: a rejected
   * request is this service's own configuration being wrong, and repeating it cannot succeed.
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

  /**
   * docs/api-error-contract.md: a downstream service that failed is 424/502, never 500. An
   * AgencyService outage is retryable and must not look like a fault of this service.
   */
  @Test
  void createAgencyWithReservedId_upstreamServerError_isADownstreamFailure() {
    when(adminAgencyControllerApi.createAgency(any(AgencyDTO.class)))
        .thenThrow(
            HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "unavailable", null, null, null));

    assertThatThrownBy(this::createAgency)
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.BAD_GATEWAY);
  }

  /** An unreachable AgencyService carries no status, and is the same class of failure as a 5xx. */
  @Test
  void createAgencyWithReservedId_unreachableUpstream_isADownstreamFailure() {
    when(adminAgencyControllerApi.createAgency(any(AgencyDTO.class)))
        .thenThrow(new ResourceAccessException("connection refused"));

    assertThatThrownBy(this::createAgency)
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.BAD_GATEWAY);
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

  /**
   * The tests above construct the client directly, so they would stay green if the {@code @Value}
   * fallback went back to the 0 that broke onboarding. Resolve it through Spring instead, with the
   * property absent, so the shipped default itself is pinned.
   */
  private int resolveConfiguredConsultingType(Map<String, Object> properties) {
    try (var context = new AnnotationConfigApplicationContext()) {
      if (!properties.isEmpty()) {
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new MapPropertySource("test", properties));
      }
      context.registerBean(PropertySourcesPlaceholderConfigurer.class);
      context.registerBean(SecurityHeaderSupplier.class, () -> securityHeaderSupplier);
      context.registerBean(IdentityAuthentication.class, () -> identityAuthentication);
      context.registerBean(IdentityClientConfig.class, () -> identityClientConfig);
      context.registerBean(AgencyAdminServiceApiControllerFactory.class, () -> controllerFactory);
      context.registerBean(AgencyCreationClient.class);
      context.refresh();
      return (Integer)
          ReflectionTestUtils.getField(
              context.getBean(AgencyCreationClient.class), "defaultConsultingType");
    }
  }

  /** Without the property the client must use 1, the consulting type every installation ships. */
  @Test
  void defaultConsultingType_withoutConfiguration_fallsBackToTheShippedConsultingType() {
    assertThat(resolveConfiguredConsultingType(Map.of())).isEqualTo(1);
  }

  /** The default stays overridable, so an operator can move it without a code change. */
  @Test
  void defaultConsultingType_withConfiguration_usesTheConfiguredValue() {
    assertThat(
            resolveConfiguredConsultingType(
                Map.of("counsellor.onboarding.agency.default-consulting-type", "7")))
        .isEqualTo(7);
  }
}
