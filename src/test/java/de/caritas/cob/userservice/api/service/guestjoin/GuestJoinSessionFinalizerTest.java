package de.caritas.cob.userservice.api.service.guestjoin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.GuestJoinTarget;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.*;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace =
        org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = "spring.sql.init.mode=never")
@ActiveProfiles("testing")
@Import({
  GuestJoinAttemptStore.class,
  GuestJoinSessionFinalizer.class,
  GuestJoinEligibility.class,
  UserService.class,
  SessionService.class,
  UserHelper.class,
  UsernameTranscoder.class,
  JpaAuditingConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuestJoinSessionFinalizerTest {
  private static final String USER_ID = "00000000-0000-0000-0000-000000000123";
  private static final GuestJoinCapability KEY =
      GuestJoinCapability.parse(
          Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
  private static final RecoveryPolicySnapshot POLICY =
      new RecoveryPolicySnapshot("LOGIN_PASSWORD", 7);
  @Autowired GuestJoinAttemptStore store;
  @Autowired UserService userService;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired GuestJoinSessionFinalizer finalizer;
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

  @BeforeEach
  void prepare() {
    when(identityConfig.getEmailDummySuffix()).thenReturn("@example.test");
    var settings = new ExtendedConsultingTypeResponseDTO();
    settings.setId(1);
    when(consultingTypes.getConsultingTypeSettings(anyString())).thenReturn(settings);
    var invite =
        invites.saveAndFlush(
            AgencyInviteLink.builder()
                .token("local-session-proof")
                .tenantId(7L)
                .topicId(9L)
                .consultingTypeId(1)
                .linkKind("TENANT")
                .chatType("LIVE_CHAT")
                .anonymity("FULL")
                .status("ACTIVE")
                .createdByUserId("local-admin")
                .createDate(LocalDateTime.now())
                .build());
    var attempt =
        store.prepare(
            KEY,
            new GuestJoinTarget(invite.getId(), 7L, 9L, 1),
            "biene_rayan_1234",
            "bee.svg",
            true,
            LocalDateTime.now().plusHours(1));
    attempt.beginIdentity();
    attempt.identityReady(USER_ID);
    attempt.beginMatrix();
    attempt.matrixReady("@biene_rayan_1234:matrix.example");
    attempts.saveAndFlush(attempt);
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
  void lostResponseReplayReturnsSameSessionAndKeepsAnonymousConsentGate() {
    var caller = new TenantData(99L, null);
    TenantContext.setCurrentTenantData(caller);
    var first = finalizer.finish(KEY, POLICY);
    var replay = finalizer.finish(KEY, null);
    assertThat(replay.sessionId()).isEqualTo(first.sessionId());
    assertThat(TenantContext.getCurrentTenantData()).isSameAs(caller);
    assertThat(sessions.count()).isEqualTo(1);
    assertThat(users.count()).isEqualTo(1);
    var user = users.findById(USER_ID).orElseThrow();
    assertThat(user.getTermsAndConditionsConfirmation()).isNull();
    assertThat(user.getDataPrivacyConfirmation()).isNull();
    assertThat(user.getChatRecoveryMode()).isEqualTo("LOGIN_PASSWORD");
    assertThat(user.getChatRecoveryPolicyRevision()).isEqualTo(7);
    assertThat(user.getTenantId()).isEqualTo(7);
    assertThat(sessions.findById(first.sessionId()).orElseThrow().getRegistrationType())
        .isEqualTo(Session.RegistrationType.ANONYMOUS);
  }

  @Test
  void failureAfterUserCreationRollsBackUserSessionAndCompletionTogether() {
    when(consultingTypes.getConsultingTypeSettings(anyString()))
        .thenThrow(new IllegalStateException("settings unavailable"));
    assertThatThrownBy(() -> finalizer.finish(KEY, POLICY))
        .isInstanceOf(IllegalStateException.class);
    assertThat(users.count()).isZero();
    assertThat(sessions.count()).isZero();
    assertThat(attempts.findByKeyHash(KEY.attemptHash()).orElseThrow().getPhase().name())
        .isEqualTo("MATRIX_READY");
  }

  @Test
  void endedSessionIsNotReopenedOrReplaced() {
    var first = finalizer.finish(KEY, POLICY);
    var session = sessions.findById(first.sessionId()).orElseThrow();
    session.setStatus(Session.SessionStatus.DONE);
    sessions.save(session);
    assertThatThrownBy(() -> finalizer.finish(KEY, null)).isInstanceOf(ForbiddenException.class);
    assertThat(sessions.count()).isEqualTo(1);
  }

  @Test
  void simultaneousFinishesCreateOneUserAndOneSession() throws Exception {
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<GuestJoinSessionFinalizer.JoinedSession> request =
          () -> {
            assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
              return finalizer.finish(KEY, POLICY);
            } catch (
                de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                    busy) {
              assertThat(busy)
                  .hasMessage("Guest Join is being completed; retry the same request")
                  .hasNoCause();
              return null;
            }
          };
      var first = executor.submit(request);
      var second = executor.submit(request);
      start.countDown();
      var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(a != null || b != null).isTrue();
      if (a == null) a = finalizer.finish(KEY, POLICY);
      if (b == null) b = finalizer.finish(KEY, POLICY);
      assertThat(a.sessionId()).isEqualTo(b.sessionId());
      assertThat(a.created()).isNotEqualTo(b.created());
    }
    assertThat(users.count()).isEqualTo(1);
    assertThat(sessions.count()).isEqualTo(1);
  }

  @Test
  void unrelatedExistingLocalAccountIsNeverOverwritten() {
    TenantContext.setCurrentTenant(7L);
    var existing =
        userService.createUser(USER_ID, "previous_identity", "previous@example.test", true);
    existing.setDeleteDate(LocalDateTime.now());
    users.save(existing);
    assertThatThrownBy(() -> finalizer.finish(KEY, POLICY))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    assertThat(users.findById(USER_ID).orElseThrow().getUsername()).isEqualTo("previous_identity");
    assertThat(users.findById(USER_ID).orElseThrow().getDeleteDate()).isNotNull();
    assertThat(sessions.count()).isZero();
    assertThat(attempts.findByKeyHash(KEY.attemptHash()).orElseThrow().getPhase().name())
        .isEqualTo("MATRIX_READY");
  }

  @Test
  void revokedInvitationCannotCreateOrResumeSession() {
    var first = finalizer.finish(KEY, POLICY);
    var invite = invites.findAll().getFirst();
    invite.setStatus("REVOKED");
    invites.saveAndFlush(invite);
    assertThatThrownBy(() -> finalizer.finish(KEY, null)).isInstanceOf(ForbiddenException.class);
    assertThat(sessions.count()).isEqualTo(1);
    assertThat(sessions.findById(first.sessionId()).orElseThrow().getStatus())
        .isEqualTo(Session.SessionStatus.NEW);
  }

  @Test
  void boundedLockFailureCanBeRetriedWithoutCreatingAnotherSession() throws Exception {
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var holder =
          executor.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(
                          transactionManager)
                      .executeWithoutResult(
                          status -> {
                            attempts.findByKeyHashForUpdate(KEY.attemptHash()).orElseThrow();
                            locked.countDown();
                            try {
                              assertThat(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                                  .isTrue();
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(interrupted);
                            }
                          }));
      try {
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> finalizer.finish(KEY, POLICY))
            .isInstanceOf(
                de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                    .class)
            .hasMessage("Guest Join is being completed; retry the same request")
            .hasNoCause();
      } finally {
        release.countDown();
      }
      holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    var first = finalizer.finish(KEY, POLICY);
    assertThat(finalizer.finish(KEY, null).sessionId()).isEqualTo(first.sessionId());
    assertThat(users.count()).isEqualTo(1);
    assertThat(sessions.count()).isEqualTo(1);
  }
}
