package de.caritas.cob.userservice.api.admin.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Admin UI edits send bounded profile patches; provider-owned claim attributes never enter the
 * request. Native provider merge behavior is covered by the real-image contract suite.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminEditKeepsKeycloakUserIdAttributeTest {

  private static final String ADMIN_ID = "8ed43c2c-1f51-4169-a7d9-c75de7eaf830";
  private static final String ENCODED_USERNAME = "enc.nbswy3dp"; // base32("hello")

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private UserAccountInputValidator userAccountInputValidator;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private KeycloakClient keycloakClient;
  @Mock private KeycloakMapper keycloakMapper;
  @Mock private UserHelper userHelper;
  @Mock private KeycloakAuthClient keycloakAuthClient;
  private com.sun.net.httpserver.HttpServer receiver;
  private Map<String, Object> received;
  private Throwable receiverFailure;
  private Admin persistedAdmin;
  private de.caritas.cob.userservice.api.admin.service.admin.AdminScope scope;
  private static final byte[] KEY =
      "different-test-only-origin-key-32".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  @Mock private AdminRepository adminRepository;
  @Mock private RetrieveAdminService retrieveAdminService;

  @Mock private ConsultantService consultantService;
  @Mock private ConsultantPublicSlugService consultantPublicSlugService;
  @Mock private MatrixUserClient matrixUserClient;
  @Mock private AppointmentService appointmentService;
  @Mock private SessionRepository sessionRepository;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private ConsultantTopicAgencyCompatibilityValidator topicAgencyCompatibilityValidator;

  private KeycloakService keycloakService;
  private UpdateAdminService updateAdminService;
  private ConsultantUpdateService consultantUpdateService;

  @BeforeEach
  void setUp() throws Exception {
    keycloakService =
        new KeycloakService(
            authenticatedUser,
            userAccountInputValidator,
            identityClientConfig,
            keycloakClient,
            keycloakMapper,
            userHelper,
            keycloakAuthClient,
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.config.auth.TaskIdentityTokenVerifier.class));
    setField(keycloakService, "multiTenancyEnabled", true);
    setField(keycloakService, "genericKeycloakError", "keycloak error");

    updateAdminService =
        new UpdateAdminService(
            keycloakService, userAccountInputValidator, adminRepository, retrieveAdminService);
    consultantUpdateService =
        new ConsultantUpdateService(
            keycloakService,
            keycloakService,
            consultantService,
            consultantPublicSlugService,
            userAccountInputValidator,
            matrixUserClient,
            appointmentService,
            sessionRepository,
            eventNotificationService,
            topicAgencyCompatibilityValidator,
            new de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver());

    var identities = new de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration();
    identities
        .getTasks()
        .put(
            "account-maintenance",
            new de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials(
                "backend-account-maintenance", "test-maintenance-secret", "maintenance-subject"));
    var grants =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant.class);
    when(grants.token(de.caritas.cob.userservice.api.config.auth.TaskIdentity.ACCOUNT_MAINTENANCE))
        .thenReturn("bounded-maintenance-token");
    receiver =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext(
        "/realms/test/oriso-commands/v1/accounts/" + ADMIN_ID + "/profile",
        exchange -> {
          try {
            assertThat(exchange.getRequestMethod(), is("PATCH"));
            assertThat(
                exchange.getRequestHeaders().getFirst("Authorization"),
                is("Bearer bounded-maintenance-token"));
            var proof =
                com.nimbusds.jose.JWSObject.parse(
                    exchange.getRequestHeaders().getFirst("X-ORISO-Origin-Authorization"));
            assertThat(proof.verify(new com.nimbusds.jose.crypto.MACVerifier(KEY)), is(true));
            assertThat(proof.getPayload().toJSONObject().get("originKind"), is("HUMAN_ADMIN"));
            assertThat(proof.getPayload().toJSONObject().get("target"), is(ADMIN_ID));
            assertThat(proof.getPayload().toJSONObject().get("operation"), is("account.profile"));
            received =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(exchange.getRequestBody(), Map.class);
            org.assertj.core.api.Assertions.assertThat(received.keySet())
                .isSubsetOf("username", "email", "firstName", "lastName", "tenantId");
            exchange.sendResponseHeaders(204, -1);
          } catch (Throwable failure) {
            receiverFailure = failure;
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    receiver.start();
    var commands =
        new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands(
            new org.springframework.web.client.RestTemplate(
                new org.springframework.http.client.JdkClientHttpRequestFactory()),
            identities,
            grants,
            new de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityOriginProof(
                java.util.Base64.getEncoder().encodeToString(new byte[32]),
                java.util.Base64.getEncoder().encodeToString(KEY),
                java.time.Clock.systemUTC()),
            "http://127.0.0.1:" + receiver.getAddress().getPort(),
            "test");
    scope =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class);
    when(adminRepository.findById(ADMIN_ID))
        .thenAnswer(call -> Optional.ofNullable(persistedAdmin));
    var consultants =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.port.out.ConsultantRepository.class);
    when(consultants.findById(ADMIN_ID))
        .thenAnswer(call -> consultantService.getConsultant(ADMIN_ID));
    setField(keycloakService, "taskCommands", commands);
    setField(
        keycloakService,
        "commandOrigins",
        new de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityMaintenanceOrigins(
            scope,
            adminRepository,
            consultants,
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.port.out.UserRepository.class)));
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified-human-session")
            .header("alg", "RS256")
            .subject("operator")
            .claim("realm_access", Map.of("roles", List.of("user-admin")))
            .build();
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            new org.springframework.security.oauth2.server.resource.authentication
                .JwtAuthenticationToken(
                jwt,
                List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "AUTHORIZATION_USER_ADMIN"),
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "AUTHORIZATION_TENANT_ADMIN"))));
  }

  @org.junit.jupiter.api.AfterEach
  void release() {
    if (receiver != null) receiver.stop(0);
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  private Map<String, Object> representationSentToKeycloak() {
    org.assertj.core.api.Assertions.assertThat(receiverFailure).isNull();
    org.assertj.core.api.Assertions.assertThat(received)
        .isNotNull()
        .doesNotContainKeys("attributes", "userId", "locale", "roles", "enabled");
    verify(scope).assertMay(any());
    verify(keycloakClient, org.mockito.Mockito.never()).getUsersResource();
    return received;
  }

  private Admin storedAdmin(Long tenantId) {
    persistedAdmin =
        Admin.builder()
            .id(ADMIN_ID)
            .username(ENCODED_USERNAME)
            .tenantId(tenantId)
            .email("old@example.org")
            .firstName("Old")
            .lastName("Name")
            .build();
    return persistedAdmin;
  }

  @Test
  void updateAgencyAdmin_Should_sendBoundedProfileWithoutProviderOwnedAttributes() {
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(2L));
    var update =
        new UpdateAgencyAdminDTO().firstname("New").lastname("Name").email("new@example.org");

    updateAdminService.updateAgencyAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.get("firstName"), is("New"));
    assertThat(sent.get("email"), is("new@example.org"));
    assertThat(sent.get("username"), is("hello"));
    assertThat(sent.get("tenantId"), is("2"));
  }

  @Test
  void updateAgencyAdmin_Should_omitTenantAndProviderOwnedAttributes_When_adminHasNoTenant() {
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(null));
    var update =
        new UpdateAgencyAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.updateAgencyAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    org.assertj.core.api.Assertions.assertThat(sent).doesNotContainKey("tenantId");
  }

  @Test
  void patchAgencyAdmin_Should_sendBoundedProfileWithoutProviderOwnedAttributes() {
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(2L));
    var patch = new PatchAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.patchAgencyAdmin(ADMIN_ID, patch);

    var sent = representationSentToKeycloak();
  }

  @Test
  void updateTenantAdmin_Should_sendOnlyBoundedProfileAndRequestedTenant() {
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.TENANT))
        .thenReturn(storedAdmin(2L));
    var update =
        new UpdateTenantAdminDTO()
            .firstname("New")
            .lastname("Name")
            .email("old@example.org")
            .tenantId(5);

    updateAdminService.updateTenantAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.get("tenantId"), is("5"));
  }

  @Test
  void patchTenantAdmin_Should_sendBoundedProfileWithoutProviderOwnedAttributes() {
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.TENANT))
        .thenReturn(storedAdmin(2L));
    var patch = new PatchAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.patchTenantAdmin(ADMIN_ID, patch);

    representationSentToKeycloak();
  }

  @Test
  void updateAgencyAdmin_Should_omitTenant_When_multiTenancyIsDisabled() {
    setField(keycloakService, "multiTenancyEnabled", false);
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(2L));
    var update =
        new UpdateAgencyAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.updateAgencyAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    org.assertj.core.api.Assertions.assertThat(sent).doesNotContainKey("tenantId");
  }

  @Test
  void updateConsultant_Should_sendBoundedProfileWithoutProviderOwnedAttributes() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
    consultant.setId(ADMIN_ID);
    consultant.setUsername(ENCODED_USERNAME);
    consultant.setTenantId(2L);
    consultant.setFirstName("Old");
    consultant.setLastName("Name");
    consultant.setEmail("old@example.org");
    consultant.setMatrixUserId(null);
    when(consultantService.getConsultant(ADMIN_ID)).thenReturn(Optional.of(consultant));
    when(consultantService.saveConsultant(any())).thenReturn(consultant);
    when(sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of());
    var update =
        new UpdateAdminConsultantDTO()
            .firstname("New")
            .lastname("Name")
            .email("old@example.org")
            .absent(false)
            .formalLanguage(false)
            .languages(List.of("de"))
            .topicIds(List.of());

    consultantUpdateService.updateConsultant(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.get("firstName"), is("New"));
    assertThat(sent.get("username"), is("hello"));
    assertThat(sent.get("tenantId"), is("2"));
  }

  @Test
  void updateConsultant_Should_omitTenant_When_multiTenancyIsDisabled() {
    setField(keycloakService, "multiTenancyEnabled", false);
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
    consultant.setId(ADMIN_ID);
    consultant.setUsername("plainname");
    consultant.setTenantId(null);
    consultant.setFirstName("Old");
    consultant.setLastName("Name");
    consultant.setEmail("old@example.org");
    consultant.setMatrixUserId(null);
    when(consultantService.getConsultant(ADMIN_ID)).thenReturn(Optional.of(consultant));
    when(consultantService.saveConsultant(any())).thenReturn(consultant);
    when(sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of());
    var update =
        new UpdateAdminConsultantDTO()
            .firstname("New")
            .lastname("Name")
            .email("new@example.org")
            .absent(false)
            .formalLanguage(false)
            .languages(List.of("de"))
            .topicIds(List.of());

    consultantUpdateService.updateConsultant(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.get("email"), is("new@example.org"));
    assertThat(sent.get("username"), is("plainname"));
    org.assertj.core.api.Assertions.assertThat(sent).doesNotContainKey("tenantId");
  }

  @Test
  void updateConsultant_Should_notTouchKeycloak_When_identityDataIsUnchanged() {
    // guards the caller contract: no update call means nothing can be wiped
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
    consultant.setId(ADMIN_ID);
    consultant.setFirstName("Same");
    consultant.setLastName("Name");
    consultant.setEmail("same@example.org");
    consultant.setAbsent(false);
    consultant.setMatrixUserId(null);
    when(consultantService.getConsultant(ADMIN_ID)).thenReturn(Optional.of(consultant));
    when(consultantService.saveConsultant(any())).thenReturn(consultant);
    var update =
        new UpdateAdminConsultantDTO()
            .firstname("Same")
            .lastname("Name")
            .email("same@example.org")
            .absent(false)
            .formalLanguage(false)
            .languages(List.of("de"))
            .topicIds(List.of());

    consultantUpdateService.updateConsultant(ADMIN_ID, update);

    org.assertj.core.api.Assertions.assertThat(received).isNull();
  }

  @Test
  void updateAgencyAdmin_Should_notSendNativeAttributeRepresentations() {
    when(retrieveAdminService.findAdmin(ADMIN_ID, Admin.AdminType.AGENCY))
        .thenReturn(storedAdmin(2L));
    var update =
        new UpdateAgencyAdminDTO().firstname("New").lastname("Name").email("old@example.org");

    updateAdminService.updateAgencyAdmin(ADMIN_ID, update);

    var sent = representationSentToKeycloak();
    assertThat(sent.get("tenantId"), is("2"));
    org.assertj.core.api.Assertions.assertThat(sent).doesNotContainKeys("attributes", "userId");
  }
}
