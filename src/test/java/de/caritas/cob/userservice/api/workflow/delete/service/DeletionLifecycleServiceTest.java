package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DeletionLifecycleServiceTest {

  private final DeletionLifecycleService service = new DeletionLifecycleService();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "globalReadOnlyHours", 48L);
    ReflectionTestUtils.setField(service, "tenantOverrideConfig", "");
    ReflectionTestUtils.setField(service, "defaultPauseMonths", 3);
    ReflectionTestUtils.setField(service, "maxPauseMonths", 12);
  }

  private User newUser() {
    return User.builder()
        .userId("user-1")
        .username("user-1")
        .email("user-1@example.com")
        .tenantId(5L)
        .build();
  }

  private Consultant newConsultant() {
    return Consultant.builder()
        .id("consultant-1")
        .matrixUserId("rc-1")
        .username("consultant-1")
        .firstName("First")
        .lastName("Last")
        .email("consultant-1@example.com")
        .tenantId(5L)
        .build();
  }

  // ---------------------------------------------------------------------------
  // beginUserDeletion
  // ---------------------------------------------------------------------------

  @Test
  void beginUserDeletion_Should_doNothing_When_userIsNull() {
    service.beginUserDeletion(null, "actor");
    // no exception = pass
  }

  @Test
  void beginUserDeletion_Should_setDeleteDateAndTransition_When_deleteDateNull() {
    User user = newUser();

    service.beginUserDeletion(user, "actor-1");

    assertThat(user.getDeleteDate()).isNotNull();
    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    assertThat(user.getDeletionPausedBy()).isEqualTo("actor-1");
    assertThat(user.getDeletionReadOnlyUntil()).isNotNull();
  }

  @Test
  void beginUserDeletion_Should_notOverwriteDeleteDate_When_alreadySet() {
    User user = newUser();
    LocalDateTime existing = LocalDateTime.of(2020, 1, 1, 0, 0);
    user.setDeleteDate(existing);

    service.beginUserDeletion(user, "actor-1");

    assertThat(user.getDeleteDate()).isEqualTo(existing);
  }

  @Test
  void beginUserDeletion_Should_notOverwriteReadOnlyUntil_When_alreadySet() {
    User user = newUser();
    LocalDateTime existing = LocalDateTime.now(ZoneOffset.UTC).plusHours(5);
    user.setDeletionReadOnlyUntil(existing);

    service.beginUserDeletion(user, "actor-1");

    assertThat(user.getDeletionReadOnlyUntil()).isEqualTo(existing);
  }

  @Test
  void cancelUserDeletion_ShouldAtomicallyClearEveryDeletionLifecycleField() {
    User user = newUser();
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    user.setDeleteDate(now);
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(now.plusHours(48));
    user.setDeletionPausedUntil(now.plusMonths(3));
    user.setDeletionPauseReason("Mr. Burns changed his mind");
    user.setDeletionPausedBy("monty");
    user.setDeletionPauseCreatedAt(now);

    service.cancelUserDeletion(user);

    assertThat(user.getDeleteDate()).isNull();
    assertThat(user.getDeletionLifecycleState()).isEqualTo(DeletionLifecycleState.ACTIVE);
    assertThat(user.getDeletionReadOnlyUntil()).isNull();
    assertThat(user.getDeletionPausedUntil()).isNull();
    assertThat(user.getDeletionPauseReason()).isNull();
    assertThat(user.getDeletionPausedBy()).isNull();
    assertThat(user.getDeletionPauseCreatedAt()).isNull();
  }

  @Test
  void cancelUserDeletion_ShouldDoNothing_WhenUserIsNull() {
    assertThatCode(() -> service.cancelUserDeletion(null)).doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------------------
  // beginConsultantDeletion
  // ---------------------------------------------------------------------------

  @Test
  void beginConsultantDeletion_Should_doNothing_When_consultantIsNull() {
    service.beginConsultantDeletion(null, "actor");
  }

  @Test
  void beginConsultantDeletion_Should_setDeleteDateAndTransition_When_deleteDateNull() {
    Consultant consultant = newConsultant();

    service.beginConsultantDeletion(consultant, "actor-2");

    assertThat(consultant.getDeleteDate()).isNotNull();
    assertThat(consultant.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    assertThat(consultant.getDeletionPausedBy()).isEqualTo("actor-2");
    assertThat(consultant.getDeletionReadOnlyUntil()).isNotNull();
  }

  @Test
  void beginConsultantDeletion_Should_notOverwriteDeleteDate_When_alreadySet() {
    Consultant consultant = newConsultant();
    LocalDateTime existing = LocalDateTime.of(2020, 1, 1, 0, 0);
    consultant.setDeleteDate(existing);

    service.beginConsultantDeletion(consultant, "actor-2");

    assertThat(consultant.getDeleteDate()).isEqualTo(existing);
  }

  // ---------------------------------------------------------------------------
  // normalizeUserLifecycle
  // ---------------------------------------------------------------------------

  @Test
  void normalizeUserLifecycle_Should_returnNull_When_userNull() {
    assertThat(service.normalizeUserLifecycle(null)).isNull();
  }

  @Test
  void normalizeUserLifecycle_Should_returnUnchanged_When_deleteDateNull() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);

    User result = service.normalizeUserLifecycle(user);

    assertThat(result).isSameAs(user);
    assertThat(result.getDeletionLifecycleState()).isEqualTo(DeletionLifecycleState.ACTIVE);
  }

  @Test
  void normalizeUserLifecycle_Should_setPendingAndTransition_When_stateNull() {
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(null);

    service.normalizeUserLifecycle(user);

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    assertThat(user.getDeletionReadOnlyUntil()).isNotNull();
  }

  @Test
  void normalizeUserLifecycle_Should_setPendingAndTransition_When_stateActive() {
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);

    service.normalizeUserLifecycle(user);

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  @Test
  void normalizeUserLifecycle_Should_backfillReadOnlyUntil_When_stateSafeguardAndUntilNull() {
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(null);

    service.normalizeUserLifecycle(user);

    assertThat(user.getDeletionReadOnlyUntil()).isNotNull();
  }

  @Test
  void normalizeUserLifecycle_Should_notBackfill_When_readOnlyUntilAlreadySet() {
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    LocalDateTime existing = LocalDateTime.now(ZoneOffset.UTC).plusHours(10);
    user.setDeletionReadOnlyUntil(existing);

    service.normalizeUserLifecycle(user);

    assertThat(user.getDeletionReadOnlyUntil()).isEqualTo(existing);
  }

  @Test
  void normalizeUserLifecycle_Should_notLog_When_pausedUntilNull() {
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    user.setDeletionPausedUntil(null);

    service.normalizeUserLifecycle(user);
    // branch coverage only — no exception means pass
  }

  @Test
  void normalizeUserLifecycle_Should_notLog_When_pausedUntilInFuture() {
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    user.setDeletionPausedUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));

    service.normalizeUserLifecycle(user);
  }

  @Test
  void normalizeUserLifecycle_Should_logExpiredPause_When_pausedUntilInPast() {
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    user.setDeletionPausedUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));

    service.normalizeUserLifecycle(user);

    // Expired pause is logged but the pause field is NOT cleared here — the scheduler
    // is responsible for clearing it. The account is NOT yet ready for hard delete
    // because the read-only window is still open.
    assertThat(service.isReadyForHardDelete(user)).isFalse();
  }

  @Test
  void isReadyForHardDelete_Should_returnTrue_When_expiredPauseAndPastReadOnly() {
    // Verifies the critical precondition: once BOTH the read-only window has elapsed
    // AND any pause has expired, the account is eligible for permanent removal.
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    user.setDeletionPausedUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));

    assertThat(service.isReadyForHardDelete(user)).isTrue();
  }

  @Test
  void normalizeUserLifecycle_Should_transitionToReadOnlySafeguard_When_statePendingDeletion() {
    // PENDING_DELETION is a transient intermediate state. normalizeUserLifecycle must
    // advance it to READ_ONLY_SAFEGUARD so the scheduler can eventually finalize it.
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);

    service.normalizeUserLifecycle(user);

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    assertThat(user.getDeletionReadOnlyUntil()).isNotNull();
  }

  @Test
  void normalizeUserLifecycle_Should_useTenantOverride_When_configuredForTenant() {
    ReflectionTestUtils.setField(service, "tenantOverrideConfig", "5:12,6:24");
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(null);
    LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

    service.normalizeUserLifecycle(user);

    assertThat(user.getDeletionReadOnlyUntil()).isAfter(before.plusHours(11));
    assertThat(user.getDeletionReadOnlyUntil()).isBefore(before.plusHours(13));
  }

  @Test
  void normalizeUserLifecycle_Should_fallBackToGlobal_When_tenantNotInOverrideConfig() {
    ReflectionTestUtils.setField(service, "tenantOverrideConfig", "6:24");
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(null);
    LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

    service.normalizeUserLifecycle(user);

    assertThat(user.getDeletionReadOnlyUntil()).isAfter(before.plusHours(47));
  }

  @Test
  void normalizeUserLifecycle_Should_ignoreMalformedOverrideEntries() {
    ReflectionTestUtils.setField(service, "tenantOverrideConfig", "malformed,5:notanumber,,6");
    User user = newUser();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(null);
    LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

    service.normalizeUserLifecycle(user);

    // "5:notanumber" parses tenant key 5 fine, but value falls back to globalReadOnlyHours (48)
    assertThat(user.getDeletionReadOnlyUntil()).isAfter(before.plusHours(47));
  }

  @Test
  void normalizeUserLifecycle_Should_useGlobal_When_tenantIdNull() {
    User user =
        User.builder()
            .userId("user-2")
            .username("user-2")
            .email("user-2@example.com")
            .tenantId(null)
            .build();
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(null);
    ReflectionTestUtils.setField(service, "tenantOverrideConfig", "5:12");
    LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

    service.normalizeUserLifecycle(user);

    assertThat(user.getDeletionReadOnlyUntil()).isAfter(before.plusHours(47));
  }

  // ---------------------------------------------------------------------------
  // normalizeConsultantLifecycle
  // ---------------------------------------------------------------------------

  @Test
  void normalizeConsultantLifecycle_Should_returnNull_When_consultantNull() {
    assertThat(service.normalizeConsultantLifecycle(null)).isNull();
  }

  @Test
  void normalizeConsultantLifecycle_Should_returnUnchanged_When_deleteDateNull() {
    Consultant consultant = newConsultant();
    consultant.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);

    Consultant result = service.normalizeConsultantLifecycle(consultant);

    assertThat(result).isSameAs(consultant);
    assertThat(result.getDeletionLifecycleState()).isEqualTo(DeletionLifecycleState.ACTIVE);
  }

  @Test
  void normalizeConsultantLifecycle_Should_setPendingAndTransition_When_stateNull() {
    Consultant consultant = newConsultant();
    consultant.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    consultant.setDeletionLifecycleState(null);

    service.normalizeConsultantLifecycle(consultant);

    assertThat(consultant.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  @Test
  void normalizeConsultantLifecycle_Should_backfillReadOnlyUntil_When_stateSafeguardAndUntilNull() {
    Consultant consultant = newConsultant();
    consultant.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    consultant.setDeletionReadOnlyUntil(null);

    service.normalizeConsultantLifecycle(consultant);

    assertThat(consultant.getDeletionReadOnlyUntil()).isNotNull();
  }

  @Test
  void normalizeConsultantLifecycle_Should_notBackfill_When_readOnlyUntilAlreadySet() {
    Consultant consultant = newConsultant();
    consultant.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    LocalDateTime existing = LocalDateTime.now(ZoneOffset.UTC).plusHours(10);
    consultant.setDeletionReadOnlyUntil(existing);

    service.normalizeConsultantLifecycle(consultant);

    assertThat(consultant.getDeletionReadOnlyUntil()).isEqualTo(existing);
  }

  @Test
  void normalizeConsultantLifecycle_Should_logExpiredPause_When_pausedUntilInPast() {
    Consultant consultant = newConsultant();
    consultant.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    consultant.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    consultant.setDeletionPausedUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));

    service.normalizeConsultantLifecycle(consultant);
  }

  @Test
  void normalizeConsultantLifecycle_Should_notLog_When_pausedUntilNull() {
    Consultant consultant = newConsultant();
    consultant.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    consultant.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    consultant.setDeletionPausedUntil(null);

    service.normalizeConsultantLifecycle(consultant);
  }

  @Test
  void normalizeConsultantLifecycle_Should_notLog_When_pausedUntilInFuture() {
    Consultant consultant = newConsultant();
    consultant.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC));
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    consultant.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    consultant.setDeletionPausedUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));

    service.normalizeConsultantLifecycle(consultant);
  }

  // ---------------------------------------------------------------------------
  // isReadyForHardDelete
  // ---------------------------------------------------------------------------

  @Test
  void isReadyForHardDeleteUser_Should_returnFalse_When_userNull() {
    assertThat(service.isReadyForHardDelete((User) null)).isFalse();
  }

  @Test
  void isReadyForHardDeleteUser_Should_returnFalse_When_stateNotSafeguard() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);

    assertThat(service.isReadyForHardDelete(user)).isFalse();
  }

  @Test
  void isReadyForHardDeleteUser_Should_returnFalse_When_readOnlyUntilNull() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(null);

    assertThat(service.isReadyForHardDelete(user)).isFalse();
  }

  @Test
  void isReadyForHardDeleteUser_Should_returnFalse_When_readOnlyUntilInFuture() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));

    assertThat(service.isReadyForHardDelete(user)).isFalse();
  }

  @Test
  void isReadyForHardDeleteUser_Should_returnTrue_When_readOnlyPastAndNoPause() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    user.setDeletionPausedUntil(null);

    assertThat(service.isReadyForHardDelete(user)).isTrue();
  }

  @Test
  void isReadyForHardDeleteUser_Should_returnFalse_When_pausedUntilInFuture() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    user.setDeletionPausedUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));

    assertThat(service.isReadyForHardDelete(user)).isFalse();
  }

  @Test
  void isReadyForHardDeleteUser_Should_returnTrue_When_pausedUntilInPast() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    user.setDeletionPausedUntil(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));

    assertThat(service.isReadyForHardDelete(user)).isTrue();
  }

  @Test
  void isReadyForHardDeleteConsultant_Should_returnFalse_When_consultantNull() {
    assertThat(service.isReadyForHardDelete((Consultant) null)).isFalse();
  }

  @Test
  void isReadyForHardDeleteConsultant_Should_returnFalse_When_stateNotSafeguard() {
    Consultant consultant = newConsultant();
    consultant.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);

    assertThat(service.isReadyForHardDelete(consultant)).isFalse();
  }

  @Test
  void isReadyForHardDeleteConsultant_Should_returnTrue_When_readOnlyPastAndNoPause() {
    Consultant consultant = newConsultant();
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    consultant.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    consultant.setDeletionPausedUntil(null);

    assertThat(service.isReadyForHardDelete(consultant)).isTrue();
  }

  @Test
  void isReadyForHardDeleteConsultant_Should_returnFalse_When_pausedUntilInFuture() {
    Consultant consultant = newConsultant();
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    consultant.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    consultant.setDeletionPausedUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));

    assertThat(service.isReadyForHardDelete(consultant)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // pauseUserDeletion
  // ---------------------------------------------------------------------------

  @Test
  void pauseUserDeletion_Should_doNothing_When_userNull() {
    service.pauseUserDeletion(null, "reason", 2, "admin");
  }

  @Test
  void pauseUserDeletion_Should_throw_When_reasonNull() {
    User user = newUser();

    assertThrows(
        BadRequestException.class, () -> service.pauseUserDeletion(user, null, 2, "admin"));
  }

  @Test
  void pauseUserDeletion_Should_throw_When_reasonBlank() {
    User user = newUser();

    assertThrows(
        BadRequestException.class, () -> service.pauseUserDeletion(user, "   ", 2, "admin"));
  }

  @Test
  void pauseUserDeletion_Should_useDefaultMonths_When_requestedMonthsNull() {
    User user = newUser();

    service.pauseUserDeletion(user, "family emergency", null, "admin");

    assertThat(user.getDeletionPausedUntil())
        .isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMonths(2).plusDays(25));
  }

  @Test
  void pauseUserDeletion_Should_throw_When_requestedMonthsTooLow() {
    User user = newUser();

    assertThrows(
        BadRequestException.class, () -> service.pauseUserDeletion(user, "reason", 0, "admin"));
  }

  @Test
  void pauseUserDeletion_Should_throw_When_requestedMonthsTooHigh() {
    User user = newUser();

    assertThrows(
        BadRequestException.class, () -> service.pauseUserDeletion(user, "reason", 13, "admin"));
  }

  @Test
  void pauseUserDeletion_Should_setFieldsAndSafeguardState_When_stateActive() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);

    service.pauseUserDeletion(user, "  needs more time  ", 4, "admin-1");

    assertThat(user.getDeletionPauseReason()).isEqualTo("needs more time");
    assertThat(user.getDeletionPausedBy()).isEqualTo("admin-1");
    assertThat(user.getDeletionPauseCreatedAt()).isNotNull();
    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  @Test
  void pauseUserDeletion_Should_setFieldsAndSafeguardState_When_stateNull() {
    User user = newUser();
    user.setDeletionLifecycleState(null);

    service.pauseUserDeletion(user, "reason", 4, "admin-1");

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  @Test
  void pauseUserDeletion_Should_notChangeState_When_statePendingDeletion() {
    User user = newUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);

    service.pauseUserDeletion(user, "reason", 4, "admin-1");

    assertThat(user.getDeletionLifecycleState()).isEqualTo(DeletionLifecycleState.PENDING_DELETION);
  }

  // ---------------------------------------------------------------------------
  // pauseConsultantDeletion
  // ---------------------------------------------------------------------------

  @Test
  void pauseConsultantDeletion_Should_doNothing_When_consultantNull() {
    service.pauseConsultantDeletion(null, "reason", 2, "admin");
  }

  @Test
  void pauseConsultantDeletion_Should_throw_When_reasonNull() {
    Consultant consultant = newConsultant();

    assertThrows(
        BadRequestException.class,
        () -> service.pauseConsultantDeletion(consultant, null, 2, "admin"));
  }

  @Test
  void pauseConsultantDeletion_Should_throw_When_requestedMonthsTooHigh() {
    Consultant consultant = newConsultant();

    assertThrows(
        BadRequestException.class,
        () -> service.pauseConsultantDeletion(consultant, "reason", 13, "admin"));
  }

  @Test
  void pauseConsultantDeletion_Should_throw_When_requestedMonthsTooLow() {
    Consultant consultant = newConsultant();

    assertThrows(
        BadRequestException.class,
        () -> service.pauseConsultantDeletion(consultant, "reason", 0, "admin"));
  }

  @Test
  void pauseConsultantDeletion_Should_useDefaultMonths_When_requestedMonthsNull() {
    Consultant consultant = newConsultant();

    service.pauseConsultantDeletion(consultant, "reason", null, "admin");

    assertThat(consultant.getDeletionPausedUntil())
        .isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMonths(2).plusDays(25));
  }

  @Test
  void pauseConsultantDeletion_Should_setFieldsAndSafeguardState_When_stateActive() {
    Consultant consultant = newConsultant();
    consultant.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);

    service.pauseConsultantDeletion(consultant, "  needs review  ", 5, "admin-2");

    assertThat(consultant.getDeletionPauseReason()).isEqualTo("needs review");
    assertThat(consultant.getDeletionPausedBy()).isEqualTo("admin-2");
    assertThat(consultant.getDeletionPauseCreatedAt()).isNotNull();
    assertThat(consultant.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  @Test
  void pauseConsultantDeletion_Should_setFieldsAndSafeguardState_When_stateNull() {
    Consultant consultant = newConsultant();
    consultant.setDeletionLifecycleState(null);

    service.pauseConsultantDeletion(consultant, "reason", 4, "admin-2");

    assertThat(consultant.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  @Test
  void pauseConsultantDeletion_Should_notChangeState_When_statePendingDeletion() {
    Consultant consultant = newConsultant();
    consultant.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);

    service.pauseConsultantDeletion(consultant, "reason", 4, "admin-2");

    assertThat(consultant.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.PENDING_DELETION);
  }
}
