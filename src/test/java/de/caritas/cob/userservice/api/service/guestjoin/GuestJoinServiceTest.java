package de.caritas.cob.userservice.api.service.guestjoin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.exception.httpresponses.*;
import de.caritas.cob.userservice.api.helper.*;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.GuestJoinAttempt;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService;
import de.caritas.cob.userservice.api.service.consultingtype.*;
import de.caritas.cob.userservice.api.service.identity.GuestIdentityCatalog;
import de.caritas.cob.userservice.api.service.identity.GuestUsernameAvailability;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.*;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("testing")
@Import({
  GuestJoinService.class,
  GuestJoinAttemptStore.class,
  GuestJoinProvisioner.class,
  GuestJoinSessionFinalizer.class,
  GuestJoinEligibility.class,
  GuestJoinNotifications.class,
  GuestIdentityCatalog.class,
  AgencyInviteLinkService.class,
  ChatRecoveryEnrollmentPolicyService.class,
  UserService.class,
  SessionService.class,
  UserHelper.class,
  UsernameTranscoder.class,
  JpaAuditingConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuestJoinServiceTest {
  private static final String NAME = "biene_rayan_1234";
  private static final String USER_ID = "00000000-0000-0000-0000-000000000123";
  private static final String KEY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
  @Autowired GuestJoinService service;
  @Autowired GuestJoinAttemptRepository attempts;
  @Autowired AgencyInviteLinkRepository invites;
  @Autowired UserRepository users;
  @Autowired SessionRepository sessions;
  @MockitoBean IdentityClientConfig identityConfig;
  @MockitoBean ConsultingTypeManager consultingTypes;
  @MockitoBean AgencyService agencies;
  @MockitoBean ConsultantService consultants;
  @MockitoBean ConsultantSessionTopicEnrichmentService topicEnrichment;
  @MockitoBean SessionSupervisionMarkerService supervision;
  @MockitoBean AuthenticatedUser authenticatedUser;
  @MockitoBean TopicService topics;
  @MockitoBean ConsultingTypeService types;

  @MockitoBean
  de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade legacyCreation;

  @MockitoBean de.caritas.cob.userservice.api.admin.service.tenant.TenantService tenants;
  @MockitoBean TopicConsultantRoutingService routing;
  @MockitoBean ConsultantAgencyService consultantAgencies;
  @MockitoBean EventNotificationService notifications;
  @MockitoBean GuestIdentityAccount identity;
  @MockitoBean GuestChatIdentity matrix;
  @MockitoBean IdentityAuthentication authentication;
  @MockitoBean GuestUsernameAvailability availability;

  @BeforeEach
  void setup() {
    when(identityConfig.getEmailDummySuffix()).thenReturn("@example.test");
    when(consultingTypes.getConsultingTypeSettings(anyString()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO().id(1));
    when(tenants.getRestrictedTenantDataFresh(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .settings(
                    new Settings()
                        .tenantAdminControls(
                            new TenantAdminControls()
                                .chatRecoverySettings(
                                    new ChatRecoverySettings()
                                        .asker(ChatRecoveryMode.LOGIN_PASSWORD)
                                        .revision(7L)))));
    invites.saveAndFlush(
        AgencyInviteLink.builder()
            .token("join-proof-invite")
            .tenantId(7L)
            .topicId(9L)
            .consultingTypeId(1)
            .linkKind("TENANT")
            .chatType("LIVE_CHAT")
            .anonymity("FULL")
            .status("ACTIVE")
            .createdByUserId("test-admin")
            .createDate(LocalDateTime.now())
            .build());
    when(identity.findOwned(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
    when(identity.createOnly(anyString(), anyString(), anyLong(), anyString())).thenReturn(USER_ID);
    when(matrix.ensureOwned(anyString(), anyString())).thenReturn("@" + NAME + ":matrix.example");
    when(matrix.createOnly(anyString(), anyString())).thenReturn("@" + NAME + ":matrix.example");
    when(authentication.login(eq(NAME), anyString()))
        .thenReturn(new IdentityLogin("test-access", 300, 1800, "test-refresh"));
    when(routing.findEligibleConsultantIds(9L)).thenReturn(List.of("consultant-id"));
    when(availability.isAvailable(anyString())).thenReturn(true);
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    sessions.deleteAll();
    users.deleteAll();
    attempts.deleteAll();
    invites.deleteAll();
  }

  @Test
  void explicitJoinCreatesTheChosenAccountAndReplayReturnsTheSameSession() {
    var caller = new TenantData(99L, null);
    TenantContext.setCurrentTenantData(caller);
    var first = join();
    var replay = join();
    assertThat(first.userName()).isEqualTo(NAME);
    assertThat(first.avatarKey()).isEqualTo("bee.svg");
    assertThat(first.sessionId()).isEqualTo(replay.sessionId());
    assertThat(first.accessToken()).isEqualTo("test-access");
    assertThat(first.tenantId()).isEqualTo(7L);
    assertThat(TenantContext.getCurrentTenantData()).isSameAs(caller);
    assertThat(users.count()).isEqualTo(1);
    assertThat(sessions.count()).isEqualTo(1);
    verify(identity, times(1)).createOnly(eq(NAME), anyString(), eq(7L), anyString());
    verify(matrix, times(1)).createOnly(eq(NAME), anyString());
    verify(tenants, times(1)).getRestrictedTenantDataFresh(7L);
    verify(notifications, times(1))
        .createWaitingRoomClientJoinedNotifications(any(), eq(List.of("consultant-id")));
    verifyNoInteractions(legacyCreation);
  }

  @Test
  void failedLoginRetriesAfterCommittedSessionWithoutAnotherAccountOrPolicyLookup() {
    when(authentication.login(eq(NAME), anyString()))
        .thenThrow(new IllegalStateException("private upstream diagnostic"))
        .thenReturn(new IdentityLogin("test-access", 300, 1800, "test-refresh"));
    assertThatThrownBy(this::join).isInstanceOf(ServiceUnavailableException.class).hasNoCause();
    assertThat(sessions.count()).isEqualTo(1);
    assertThat(join().sessionId()).isEqualTo(sessions.findAll().iterator().next().getId());
    verify(identity, times(1)).createOnly(anyString(), anyString(), anyLong(), anyString());
    verify(tenants, times(1)).getRestrictedTenantDataFresh(7L);
  }

  @Test
  void mismatchedNameAndAvatarCannotCreateAnyAccount() {
    assertThatThrownBy(() -> service.join("join-proof-invite", KEY, NAME, "cat.svg", true))
        .isInstanceOf(BadRequestException.class);
    assertThat(attempts.count()).isZero();
    verifyNoInteractions(identity, matrix, authentication);
  }

  @Test
  void missingServerPolicyPreventsExternalWrites() {
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(null);
    assertThatThrownBy(this::join).isInstanceOf(CustomValidationHttpStatusException.class);
    verifyNoInteractions(identity, matrix, authentication);
    assertThat(users.count()).isZero();
  }

  @Test
  void endingTheSessionDuringLoginRevokesCredentialsAndNeverReturnsSuccess() {
    when(authentication.login(eq(NAME), anyString()))
        .thenAnswer(
            call -> {
              var session = sessions.findAll().iterator().next();
              session.setStatus(de.caritas.cob.userservice.api.model.Session.SessionStatus.DONE);
              sessions.save(session);
              return new IdentityLogin("test-access", 300, 1800, "test-refresh");
            });
    assertThatThrownBy(this::join).isInstanceOf(ForbiddenException.class);
    verify(authentication).logout("test-refresh", "test-access");
    assertThat(sessions.count()).isEqualTo(1);
  }

  @Test
  void incompleteLoginResponseIsRevokedAndRetryUsesTheCommittedSession() {
    when(authentication.login(eq(NAME), anyString()))
        .thenReturn(new IdentityLogin("test-access", 0, 1800, "test-refresh"));
    assertThatThrownBy(this::join).isInstanceOf(ServiceUnavailableException.class).hasNoCause();
    verify(authentication).logout("test-refresh", "test-access");
    assertThat(sessions.count()).isEqualTo(1);
  }

  @Test
  void publicHttpJoinAndReplayUseRealSecurityAndTheSameDatabaseSession() throws Exception {
    try (var web = httpContext()) {
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
              .apply(
                  org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                      .springSecurity())
              .build();
      String body =
          "{\"retryKey\":\""
              + KEY
              + "\",\"username\":\""
              + NAME
              + "\",\"avatarKey\":\"bee.svg\",\"languageFormal\":true}";
      for (int request = 0; request < 2; request++) {
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/users/invitelinks/join-proof-invite/join")
                    .contentType("application/json")
                    .content(body))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Cache-Control", "no-store"))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.userName")
                    .value(NAME))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.accessToken")
                    .value("test-access"))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.retryKey")
                    .doesNotExist());
      }
      assertThat(sessions.count()).isEqualTo(1);
      assertThat(users.count()).isEqualTo(1);
    }
  }

  @Test
  void publicConflictCommitsEvidenceAndReplayCannotCreateAnotherAccount() throws Exception {
    when(identity.createOnly(anyString(), anyString(), anyLong(), anyString()))
        .thenThrow(new ConflictException("Existing unrelated account"));
    try (var web = httpContext()) {
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
              .apply(
                  org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                      .springSecurity())
              .build();
      String body =
          "{\"retryKey\":\""
              + KEY
              + "\",\"username\":\""
              + NAME
              + "\",\"avatarKey\":\"bee.svg\",\"languageFormal\":true}";
      Long attemptId = null;
      for (int request = 0; request < 2; request++) {
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/users/invitelinks/join-proof-invite/join")
                    .contentType("application/json")
                    .content(body))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isConflict());
        var persisted =
            attempts.findByKeyHash(GuestJoinCapability.parse(KEY).attemptHash()).orElseThrow();
        if (attemptId == null) attemptId = persisted.getId();
        assertThat(persisted.getId()).isEqualTo(attemptId);
        assertThat(persisted.getPhase().name()).isEqualTo("IDENTITY_COLLISION");
        assertThat(persisted.getOriginalUsername()).isEqualTo(NAME);
        assertThat(persisted.getOriginalAvatarKey()).isEqualTo("bee.svg");
        assertThat(persisted.getIdentityUserId()).isNull();
        assertThat(persisted.getSessionId()).isNull();
        assertThat(users.count()).isZero();
        assertThat(sessions.count()).isZero();
      }
    }
    verify(identity, times(1)).findOwned(eq(NAME), eq(7L), anyString());
    verify(identity, times(1)).createOnly(eq(NAME), anyString(), eq(7L), anyString());
    verify(identity, never()).completeOwned(anyString(), anyString(), anyLong(), anyString());
    verify(identity, never()).deleteOwned(anyString(), anyString(), anyLong(), anyString());
    verifyNoInteractions(matrix, authentication, notifications, legacyCreation);
  }

  @Test
  void publicMatrixCollisionCommitsBeforeReportingAndReplayDoesNotDispatchOrCleanUp()
      throws Exception {
    // Every candidate collides, so the attempt runs out of names and the caller does get the
    // conflict. What must not happen on the way there is an account left behind.
    when(matrix.createOnly(anyString(), anyString()))
        .thenThrow(new ConflictException("Existing unrelated Matrix account"));
    try (var web = httpContext()) {
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
              .apply(
                  org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                      .springSecurity())
              .build();
      String body =
          "{\"retryKey\":\""
              + KEY
              + "\",\"username\":\""
              + NAME
              + "\",\"avatarKey\":\"bee.svg\",\"languageFormal\":true}";
      Long attemptId = null;
      for (int request = 0; request < 2; request++) {
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/users/invitelinks/join-proof-invite/join")
                    .contentType("application/json")
                    .content(body))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isConflict());
        var persisted =
            attempts.findByKeyHash(GuestJoinCapability.parse(KEY).attemptHash()).orElseThrow();
        if (attemptId == null) attemptId = persisted.getId();
        assertThat(persisted.getId()).isEqualTo(attemptId);
        assertThat(persisted.getPhase().name()).isEqualTo("MATRIX_COLLISION");
        assertThat(persisted.getIdentityUserId()).isEqualTo(USER_ID);
        assertThat(persisted.getMatrixUserId()).isNull();
        assertThat(persisted.getSessionId()).isNull();
        assertThat(users.count()).isZero();
        assertThat(sessions.count()).isZero();
      }
    }
    verify(identity, times(1)).createOnly(eq(NAME), anyString(), eq(7L), anyString());
    verify(matrix, times(1)).createOnly(eq(NAME), anyString());
    verify(matrix, never()).ensureOwned(anyString(), anyString());
    // Every candidate that owned an account had it released. A replay repeats the release, which
    // the primitive defines as idempotent for an account that is already gone — so the floor is
    // what matters here, not an exact count.
    verify(identity, atLeast(GuestJoinAttempt.MAX_CANDIDATES))
        .deleteOwned(anyString(), anyString(), anyLong(), anyString());
    verifyNoInteractions(authentication, notifications, legacyCreation);
  }

  @Test
  void invalidPublicHttpBodyCannotStartProvisioning() throws Exception {
    try (var web = httpContext()) {
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
              .apply(
                  org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                      .springSecurity())
              .build();
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/users/invitelinks/join-proof-invite/join")
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest());
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/users/invitelinks/join-proof-invite/administration")
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isUnauthorized());
      verifyNoInteractions(identity, matrix, authentication);
      assertThat(attempts.count()).isZero();
    }
  }

  private org.springframework.web.context.support.AnnotationConfigWebApplicationContext
      httpContext() {
    var web = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
    web.setServletContext(new org.springframework.mock.web.MockServletContext());
    web.getEnvironment()
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "join-http", Map.of("multitenancy.enabled", "true")));
    web.register(GuestJoinHttpTestConfig.class);
    web.addBeanFactoryPostProcessor(
        factory -> factory.registerSingleton("guestJoinService", service));
    web.refresh();
    return web;
  }

  @Test
  void changedLanguagePreferenceCannotRebindAnExistingJoin() {
    join();
    assertThatThrownBy(() -> service.join("join-proof-invite", KEY, NAME, "bee.svg", false))
        .isInstanceOf(ConflictException.class);
    assertThat(users.findById(USER_ID).orElseThrow().isLanguageFormal()).isTrue();
    verify(authentication, times(1)).login(anyString(), anyString());
  }

  @Test
  void theGeneratedUsersContractServesJoinRatherThanAnUnimplementedStub() throws Exception {
    try (var web = httpContext()) {
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
              .apply(
                  org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                      .springSecurity())
              .build();
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/users/invitelinks/join-proof-invite/join")
                  .contentType("application/json")
                  .content(
                      "{\"retryKey\":\""
                          + KEY
                          + "\",\"username\":\""
                          + NAME
                          + "\",\"avatarKey\":\"bee.svg\",\"languageFormal\":true}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
      assertThat(sessions.count()).isEqualTo(1);
    }
  }

  @Test
  void aNameTakenElsewhereInTheNamespaceIsRefusedBeforeAnyProviderWrite() {
    when(availability.isAvailable(NAME)).thenReturn(false);
    assertThatThrownBy(this::join).isInstanceOf(ConflictException.class);
    verifyNoInteractions(identity, matrix, authentication);
    assertThat(users.count()).isZero();
    assertThat(sessions.count()).isZero();
  }

  @Test
  void aConfirmedCollisionOffersTheNextCandidateInsteadOfADeadEnd() {
    // The first candidate is definitely taken: the create itself was refused, so nothing of it is
    // owned and nothing has to be cleaned up before another name may be tried.
    when(identity.createOnly(eq(NAME), anyString(), anyLong(), anyString()))
        .thenThrow(new ConflictException("Selected guest name is occupied"));
    // The replacement is a name the server picks, so the fixture cannot know it in advance.
    when(matrix.createOnly(anyString(), anyString())).thenReturn("@replacement:matrix.example");
    when(authentication.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("test-access", 300, 1800, "test-refresh"));

    var joined = join();

    assertThat(joined.userName()).isNotEqualTo(NAME);
    assertThat(joined.sessionId()).isNotNull();
    assertThat(users.count()).isEqualTo(1);
    assertThat(sessions.count()).isEqualTo(1);
    var attempt =
        attempts.findByKeyHash(GuestJoinCapability.parse(KEY).attemptHash()).orElseThrow();
    assertThat(attempt.getOriginalUsername())
        .as("the guest's own request stays bound, whatever the server had to fall back to")
        .isEqualTo(NAME);
  }

  @Test
  void aMatrixCollisionCleansUpItsOwnedAccountBeforeAnotherNameIsBound() {
    // Keycloak already said yes for this candidate, so the attempt owns an account. Moving on
    // without removing it would leave a guest account nobody will ever use or find.
    when(matrix.createOnly(eq(NAME), anyString()))
        .thenThrow(new ConflictException("Selected guest name is occupied"));
    when(matrix.createOnly(argThat(other -> !NAME.equals(other)), anyString()))
        .thenReturn("@replacement:matrix.example");
    when(authentication.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("test-access", 300, 1800, "test-refresh"));

    var joined = join();

    assertThat(joined.userName()).isNotEqualTo(NAME);
    verify(identity).deleteOwned(eq(USER_ID), eq(NAME), eq(7L), anyString());
    assertThat(users.count()).isEqualTo(1);
    assertThat(sessions.count()).isEqualTo(1);
  }

  @Test
  void anUnknownCleanupKeepsTheCandidateRatherThanOrphaningTheAccount() {
    when(matrix.createOnly(eq(NAME), anyString()))
        .thenThrow(new ConflictException("Selected guest name is occupied"));
    doThrow(new ServiceUnavailableException("Cleanup outcome is unknown"))
        .when(identity)
        .deleteOwned(anyString(), anyString(), anyLong(), anyString());

    assertThatThrownBy(this::join).isInstanceOf(ServiceUnavailableException.class);

    var attempt =
        attempts.findByKeyHash(GuestJoinCapability.parse(KEY).attemptHash()).orElseThrow();
    assertThat(attempt.getIdentityUserId())
        .as("the owned account stays recorded so the next attempt can finish removing it")
        .isEqualTo(USER_ID);
    assertThat(attempt.candidateOrdinal()).isEqualTo(1);
    assertThat(users.count()).isZero();
    assertThat(sessions.count()).isZero();
  }

  GuestJoinService.JoinResponse join() {
    return service.join("join-proof-invite", KEY, NAME, "bee.svg", true);
  }
}
