package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.TenantCaseHandoverPolicyCacheRepository;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seam is the wire between UserService and TenantService: the Admin's raw JSON goes in through
 * the real runtime mapper, and the assertion is on the HTTP body that actually leaves this service.
 * Everything on the policy path is real — only the socket, the identity provider and the cache
 * store are stubbed. A Mockito verify on {@code updateEffective} cannot disagree with the code;
 * this can.
 */
class CaseHandoverPolicyHttpContractTest {

  private static final String POLICY_URL =
      "https://tenant.example.org/tenantadmin/7/permission-policies";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final RestTemplate rest = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
  private final IdentityAuthentication identity = mock(IdentityAuthentication.class);
  private final IdentityClientConfig identityConfig = mock(IdentityClientConfig.class);
  private final SecurityHeaderSupplier headers =
      new SecurityHeaderSupplier(mock(AuthenticatedUser.class));
  private final TenantAdminServiceApiControllerFactory factory =
      new TenantAdminServiceApiControllerFactory();
  private final TenantCaseHandoverPolicyCacheRepository cacheRepository =
      mock(TenantCaseHandoverPolicyCacheRepository.class);
  private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);

  private CaseHandoverService caseHandoverService;

  @BeforeEach
  void setUp() {
    var technical = new TechnicalUserConfig();
    technical.setUsername("synthetic-service");
    technical.setPassword("synthetic-password");
    when(identityConfig.getTechnicalUser()).thenReturn(technical);
    when(identity.login("synthetic-service", "synthetic-password"))
        .thenReturn(new IdentityLogin("synthetic-token", 60, 120, "synthetic-refresh"));
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", "https://tenant.example.org");
    ReflectionTestUtils.setField(factory, "restTemplate", rest);

    lenient().when(claims.tryClaim(anyString(), any())).thenReturn(true);
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
    lenient().when(cacheRepository.findById(any())).thenReturn(Optional.empty());

    var policyClient =
        new TenantCaseHandoverPolicyReadClient(factory, identity, identityConfig, headers);
    var cacheService =
        new CaseHandoverPolicyCacheService(
            cacheRepository, policyClient, claims, clock, transactionManager);

    caseHandoverService =
        new CaseHandoverService(
            mock(de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository.class),
            mock(de.caritas.cob.userservice.api.facade.SessionSupervisorFacade.class),
            mock(de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository.class),
            cacheService,
            mock(de.caritas.cob.userservice.api.port.out.SessionRepository.class),
            mock(de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository.class),
            mock(de.caritas.cob.userservice.api.service.user.UserAccountService.class),
            mock(
                de.caritas.cob.userservice.api.service.notification.EventNotificationService.class),
            mock(de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.class),
            mock(de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService.class),
            mock(
                de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService
                    .class),
            claims,
            clock,
            transactionManager);

    TenantContext.setCurrentTenant(7L);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  /**
   * #1131, the "duration always reads 180" half. A tenant seeded before the duration policy existed
   * has no {@code maxAccessDurationMinutes} object on the stored reason — TenantService's own
   * resolver returns null for it when the parent carries none. The admin's new duration must still
   * reach the wire; today it is dropped without a word.
   */
  @Test
  void aChangedDurationReachesTenantServiceEvenWhenTheStoredReasonCarriesNoDurationYet() {
    String storedWithoutDuration =
        """
        {"tenantId":7,"policies":{},"caseHandoverPolicies":{"reasons":{
          "COUNSELLOR_ASKED_FOR_ADVICE":{
            "code":"COUNSELLOR_ASKED_FOR_ADVICE",
            "labels":{"value":{"de":"Rat benötigt"},"mode":"SUGGESTED"},
            "enabled":{"value":true,"mode":"SUGGESTED"},
            "accessAllowed":{"value":true,"mode":"SUGGESTED"},
            "clientConsent":{"value":"OPT_IN","mode":"SUGGESTED"},
            "clientConsentRequired":{"value":true,"mode":"SUGGESTED"},
            "approvalRoles":{"value":["CLIENT"],"mode":"SUGGESTED"},
            "clientNotificationTemplates":{"value":{"de":"x"},"mode":"SUGGESTED"}
          }}}}
        """;

    server
        .expect(ExpectedCount.twice(), requestTo(POLICY_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(storedWithoutDuration, MediaType.APPLICATION_JSON));

    var sentBody = new java.util.concurrent.atomic.AtomicReference<String>();
    server
        .expect(requestTo(POLICY_URL))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(
            request ->
                sentBody.set(
                    ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                        .getBodyAsString()))
        .andRespond(withSuccess(storedWithoutDuration, MediaType.APPLICATION_JSON));

    caseHandoverService.updateReasonPolicies(
        List.of(
            MAPPER.readValue(
                """
                {"code":"COUNSELLOR_ASKED_FOR_ADVICE","label":"Rat benötigt",
                 "clientConsent":{"value":"OPT_IN","mode":"SUGGESTED"},
                 "clientConsentMode":"SUGGESTED","clientConsentRequired":true,
                 "accessAllowed":true,"enabled":true,"displayOrder":10,
                 "maxAccessDurationMinutes":90}
                """,
                CaseHandoverService.CaseHandoverReason.class)));

    server.verify();
    JsonNode advice =
        MAPPER
            .readTree(sentBody.get())
            .path("caseHandoverPolicies")
            .path("reasons")
            .path("COUNSELLOR_ASKED_FOR_ADVICE");
    assertThat(advice.path("maxAccessDurationMinutes").path("value").asInt())
        .as("the duration the admin chose has to be on the wire, not silently dropped")
        .isEqualTo(90);
  }
}
