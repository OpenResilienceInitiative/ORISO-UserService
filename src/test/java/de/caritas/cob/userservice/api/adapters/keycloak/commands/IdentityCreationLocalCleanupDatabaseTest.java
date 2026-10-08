package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUsernameRegistry;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.*;
import de.caritas.cob.userservice.api.workflow.delete.service.*;
import de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service.TeamDiscussionPurgeWriter;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(properties = {"spring.sql.init.mode=never", "spring.jpa.show-sql=false"})
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace =
        org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles({"testing", "isolated-creation-local-cleanup"})
@Import({
  IdentityCreationJournalWriter.class,
  IdentityCreationLocalCleanup.class,
  IdentityCreationEffects.class,
  IdentityCreationEffectWriter.class,
  IdentityAnonymousBootstrapFailure.class,
  DeletionLifecycleService.class,
  ActionsRegistry.class,
  IdentityTombstoneService.class,
  DeleteAskerRoomsAndSessionsAction.class,
  DeleteDatabaseAskerAgencyAction.class,
  DeleteAnonymousRegistryIdAction.class,
  DeleteAskerDraftMessagesAction.class,
  DeleteAskerEventNotificationsAction.class,
  DeleteAskerReplyEmailDeliveriesAction.class,
  DeleteDatabaseAskerAction.class,
  DeleteDatabaseConsultantAgencyAction.class,
  DeleteCaseHandoverRequestsForConsultantAction.class,
  DeleteConsultantDraftMessagesAction.class,
  DeleteConsultantEventNotificationsAction.class,
  DeleteConsultantMessageEmailDeliveriesAction.class,
  DeleteDatabaseConsultantAction.class,
  TeamDiscussionPurgeService.class,
  TeamDiscussionPurgeWriter.class,
  IdentityCreationLocalCleanupDatabaseTest.DatabaseConfig.class
})
class IdentityCreationLocalCleanupDatabaseTest {
  @Autowired IdentityCreationLocalCleanup cleanup;
  @Autowired IdentityAnonymousBootstrapFailure bootstrap;
  @Autowired IdentityCreationAttemptRepository attempts;
  @Autowired UserRepository users;
  @Autowired AdminRepository admins;
  @Autowired AdminAgencyRepository adminAgencies;
  @Autowired ConsultantRepository consultants;
  @Autowired ConsultantAgencyRepository consultantAgencies;
  @Autowired ConsultantTopicRepository consultantTopics;
  @Autowired SessionRepository sessions;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @Autowired JdbcTemplate jdbc;
  // These partner/memory surfaces are not local DB cleanup authority. Native identity IO is proved
  // separately.
  @MockitoBean MatrixSynapseService matrix;
  @MockitoBean de.caritas.cob.userservice.api.service.appointment.AppointmentService appointments;
  @MockitoBean AnonymousUsernameRegistry ephemeralUsernameRegistry;

  @BeforeEach
  void lifecycleTable() {
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(64) PRIMARY KEY,tenant_id BIGINT,status VARCHAR(20),attempts INT)");
  }

  @Test
  void agencyAdminRecoveryDeletesItsOwnAdminAndAgencyLinkAndPreservesExistingPerson() {
    var row = row("AGENCY_ADMIN", "INVITATION");
    var admin = admin(row.getAccountId(), 42L);
    admins.saveAndFlush(admin);
    adminAgencies.save(
        AdminAgency.builder()
            .admin(admin)
            .agencyId(8L)
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
    var existing = admin("existing-admin", 42L);
    admins.saveAndFlush(existing);
    cleanup.clean(UUID.fromString(row.getId()));
    entityManager.flush();
    entityManager.clear();
    assertThat(admins.findById(admin.getId())).isEmpty();
    assertThat(adminAgencies.findByAdminId(admin.getId())).isEmpty();
    assertThat(admins.findById(existing.getId())).isPresent();
    assertThat(attempts.findById(row.getId()).orElseThrow().getStatus())
        .isEqualTo("COMPENSATION_REQUESTED");
    verifyNoInteractions(matrix);
  }

  @Test
  void hybridConsultantRecoveryRemovesTopicsAgencyLinksAndOwnedAdminRows() {
    var row = row("CONSULTANT_AGENCY_ADMIN", "INVITATION");
    var consultant =
        Consultant.builder()
            .id(row.getAccountId())
            .tenantId(42L)
            .username("test")
            .email("test@example.invalid")
            .firstName("Test")
            .lastName("Fixture")
            .encourage2fa(true)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .languageCode(com.neovisionaries.i18n.LanguageCode.de)
            .build();
    consultants.saveAndFlush(consultant);
    consultantAgencies.save(
        ConsultantAgency.builder()
            .consultant(consultant)
            .agencyId(8L)
            .tenantId(42L)
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
    consultantTopics.save(
        ConsultantTopic.builder().consultant(consultant).agencyId(8L).topicId(1L).build());
    var admin = admin(row.getAccountId(), 42L);
    admins.saveAndFlush(admin);
    adminAgencies.save(
        AdminAgency.builder()
            .admin(admin)
            .agencyId(8L)
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
    cleanup.clean(UUID.fromString(row.getId()));
    entityManager.flush();
    entityManager.clear();
    assertThat(consultants.findById(row.getAccountId())).isEmpty();
    assertThat(consultantAgencies.findByConsultantId(row.getAccountId())).isEmpty();
    assertThat(consultantTopics.count()).isZero();
    assertThat(admins.findById(row.getAccountId())).isEmpty();
    assertThat(adminAgencies.findByAdminId(row.getAccountId())).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void anonymousRecoveryPurgesItsOwnSessionAndUserWithoutGuessingRemoteMatrixOwnership() {
    var row = row("ANONYMOUS", "ANONYMOUS");
    var user = user(row.getAccountId(), 42L);
    var session = new Session(user, 1, "00000", 8L, Session.SessionStatus.NEW, false);
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    session.setIsConsultantDirectlySet(false);
    session.setTenantId(user.getTenantId());
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    sessions.save(session);
    jdbc.update("INSERT INTO account_inactivity VALUES(?,42,'ACTIVE',0)", user.getUserId());
    cleanup.clean(UUID.fromString(row.getId()));
    entityManager.flush();
    entityManager.clear();
    assertThat(users.findById(user.getUserId())).isEmpty();
    assertThat(sessions.findByUserUserId(user.getUserId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_inactivity WHERE identity_id=?",
                Integer.class,
                user.getUserId()))
        .isZero();
    verifyNoInteractions(matrix);
  }

  @Test
  void foreignPersistedTenantCannotBeAdoptedByCreationCleanup() {
    var row = row("AGENCY_ADMIN", "INVITATION");
    var foreign = admin(row.getAccountId(), 99L);
    admins.saveAndFlush(foreign);
    assertThatThrownBy(() -> cleanup.clean(UUID.fromString(row.getId())))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(admins.findById(foreign.getId())).isPresent();
    verifyNoInteractions(matrix);
  }

  @Test
  void successfulGuestBootstrapCannotBeReinterpretedAsExpiredFailure() {
    var row = row("ANONYMOUS", "ANONYMOUS");
    row.setStatus("COMMITTED");
    var user = user(row.getAccountId(), 42L);
    var session = guestSession(user);
    row.setBootstrapSessionId(session.getId());
    row.setBootstrapExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5));
    attempts.saveAndFlush(row);
    bootstrap.complete(user.getUserId(), session.getId());
    bootstrap.reconcile(UUID.fromString(row.getId()));
    assertThat(users.findById(user.getUserId()).orElseThrow().getDeleteDate()).isNull();
    assertThat(attempts.findById(row.getId()).orElseThrow().getBootstrapSessionId()).isNull();
  }

  @Test
  void caughtFinalizationFailureWaitsForConfirmedCommitThenUsesNormalDurableDeletion() {
    var row = row("ANONYMOUS", "ANONYMOUS");
    row.setStatus("COMMIT_REQUESTED");
    var user = user(row.getAccountId(), 42L);
    var session = guestSession(user);
    row.setBootstrapSessionId(session.getId());
    row.setBootstrapExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5));
    attempts.saveAndFlush(row);
    bootstrap.record(
        user.getUserId(),
        session.getId(),
        new IllegalStateException("actual activation transport failure"));
    assertThat(user.getDeleteDate()).isNull();
    assertThat(row.getBootstrapFailedAt()).isNotNull();
    row.setStatus("COMMITTED");
    attempts.saveAndFlush(row);
    bootstrap.reconcile(UUID.fromString(row.getId()));
    entityManager.flush();
    entityManager.clear();
    assertThat(users.findById(user.getUserId()).orElseThrow().getDeleteDate()).isNotNull();
    assertThat(attempts.findById(row.getId()).orElseThrow().getBootstrapSessionId()).isNull();
  }

  @Test
  void expiredGuestPhaseFencesLateTokenReturnAndMarksOnlyItsActualSessionOwner() {
    var row = row("ANONYMOUS", "ANONYMOUS");
    row.setStatus("COMMITTED");
    var user = user(row.getAccountId(), 42L);
    var session = guestSession(user);
    row.setBootstrapSessionId(session.getId());
    row.setBootstrapExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    attempts.saveAndFlush(row);
    bootstrap.reconcile(UUID.fromString(row.getId()));
    assertThat(user.getDeleteDate()).isNotNull();
    assertThatThrownBy(() -> bootstrap.complete(user.getUserId(), session.getId()))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(matrix);
  }

  @Test
  void committedGuestCannotAdoptAnotherPersistedSessionOwner() {
    var row = row("ANONYMOUS", "ANONYMOUS");
    row.setStatus("COMMITTED");
    var own = user(row.getAccountId(), 42L);
    var foreign = user(UUID.randomUUID().toString(), 42L);
    var foreignSession = guestSession(foreign);
    row.setBootstrapSessionId(foreignSession.getId());
    row.setBootstrapExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    attempts.saveAndFlush(row);
    assertThatThrownBy(() -> bootstrap.reconcile(UUID.fromString(row.getId())))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(own.getDeleteDate()).isNull();
    assertThat(foreign.getDeleteDate()).isNull();
  }

  @Test
  void existingRegisteredSessionCannotBeReclassifiedAsGuestBootstrapFailure() {
    var row = row("ANONYMOUS", "ANONYMOUS");
    row.setStatus("COMMITTED");
    var own = user(row.getAccountId(), 42L);
    var registered = guestSession(own);
    registered.setRegistrationType(Session.RegistrationType.REGISTERED);
    sessions.save(registered);
    entityManager.flush();
    row.setBootstrapSessionId(registered.getId());
    row.setBootstrapExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    attempts.saveAndFlush(row);
    assertThatThrownBy(
            () ->
                bootstrap.record(
                    own.getUserId(), registered.getId(), new IllegalStateException("login")))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(own.getDeleteDate()).isNull();
  }

  @Test
  void acknowledgedAppointmentCleanupMustFinishBeforeTerminalIntentEvenWhenLocalRowIsAbsent() {
    var row = row("CONSULTANT", "INVITATION");
    String effect = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO identity_creation_effect(id,attempt_id,account_id,tenant_id,execution_claim,effect_kind,state,requested_target,target_id,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)",
        effect,
        row.getId(),
        row.getAccountId(),
        42L,
        row.getExecutionClaim(),
        "APPOINTMENT_CONSULTANT",
        "ACKNOWLEDGED",
        row.getAccountId(),
        row.getAccountId(),
        "CREATED");
    assertThat(consultants.findById(row.getAccountId())).isEmpty();
    doThrow(new org.springframework.web.client.ResourceAccessException("cleanup unavailable"))
        .doNothing()
        .when(appointments)
        .deleteOwnedCreationConsultant(row.getAccountId(), 42L);
    assertThatThrownBy(() -> cleanup.clean(UUID.fromString(row.getId())))
        .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    assertThat(attempts.findById(row.getId()).orElseThrow().getStatus())
        .isEqualTo("LOCAL_CLEANUP_REQUESTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM identity_creation_effect WHERE id=?", String.class, effect))
        .isEqualTo("ACKNOWLEDGED");
    cleanup.clean(UUID.fromString(row.getId()));
    assertThat(attempts.findById(row.getId()).orElseThrow().getStatus())
        .isEqualTo("COMPENSATION_REQUESTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM identity_creation_effect WHERE id=?", String.class, effect))
        .isEqualTo("CLEANED");
    verify(appointments, times(2)).deleteOwnedCreationConsultant(row.getAccountId(), 42L);
  }

  @Test
  void committedOrForeignAppointmentEffectNeverAcquiresCleanupAuthority() {
    var committed = row("CONSULTANT", "INVITATION");
    committed.setStatus("COMMITTED");
    attempts.saveAndFlush(committed);
    assertThatThrownBy(() -> cleanup.clean(UUID.fromString(committed.getId())))
        .isInstanceOf(AccessDeniedException.class);
    var own = row("CONSULTANT", "INVITATION");
    jdbc.update(
        "INSERT INTO identity_creation_effect(id,attempt_id,account_id,tenant_id,execution_claim,effect_kind,state,requested_target,target_id,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        own.getId(),
        own.getAccountId(),
        42L,
        own.getExecutionClaim(),
        "APPOINTMENT_CONSULTANT",
        "ACKNOWLEDGED",
        own.getAccountId(),
        UUID.randomUUID().toString(),
        "CREATED");
    assertThatThrownBy(() -> cleanup.clean(UUID.fromString(own.getId())))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(attempts.findById(own.getId()).orElseThrow().getStatus())
        .isEqualTo("LOCAL_CLEANUP_REQUESTED");
    verifyNoInteractions(appointments);
  }

  private IdentityCreationAttempt row(String kind, String origin) {
    var row = new IdentityCreationAttempt();
    row.setId(UUID.randomUUID().toString());
    row.setAccountId(UUID.randomUUID().toString());
    row.setCreationProof("native-owned-test-proof");
    row.setOriginKind(origin);
    row.setRegistrationKind(kind);
    row.setTenantId(42L);
    row.setInitialRoles(kind.equals("ANONYMOUS") ? "user" : "consultant");
    row.setProvenance("invite:7");
    row.setStatus("LOCAL_CLEANUP_REQUESTED");
    row.setExecutionClaim(UUID.randomUUID().toString());
    row.setUpdateDate(LocalDateTime.now());
    return attempts.saveAndFlush(row);
  }

  private Admin admin(String id, Long tenant) {
    return Admin.builder()
        .id(id)
        .tenantId(tenant)
        .username(id)
        .firstName("Test")
        .lastName("Fixture")
        .email(id + "@example.invalid")
        .type(Admin.AdminType.AGENCY)
        .build();
  }

  private User user(String id, Long tenant) {
    var user = new User(id, null, "Anonymous-test", "test@example.invalid", false);
    user.setTenantId(tenant);
    user.setMatrixUserId("@preexisting-or-reactivated:matrix.invalid");
    return users.save(user);
  }

  private Session guestSession(User user) {
    var session = new Session(user, 1, "00000", 8L, Session.SessionStatus.NEW, false);
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    session.setIsConsultantDirectlySet(false);
    session.setTenantId(user.getTenantId());
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    return sessions.save(session);
  }

  @TestConfiguration
  @org.springframework.context.annotation.Profile("isolated-creation-local-cleanup")
  static class DatabaseConfig {
    @Bean
    JdbcTemplate jdbcTemplate(javax.sql.DataSource source) {
      return new JdbcTemplate(source);
    }
  }
}
